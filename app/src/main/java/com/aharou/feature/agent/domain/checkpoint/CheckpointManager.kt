package com.aharou.feature.agent.domain.checkpoint

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.entity.CheckpointEntity
import com.aharou.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.aharou.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val checkpointDao: CheckpointDao,
    private val fileAccess: FileAccessProvider
) {
    /** 回退结果：实际还原的文件数，以及因「被检查点之外的操作改过」而跳过的文件。 */
    data class RestoreResult(val restoredCount: Int, val conflicts: List<String>) {
        val hasConflict: Boolean get() = conflicts.isNotEmpty()
    }

    /** 撤销恢复所需的现场记录：单个文件在那一刻的样子。 */
    @Serializable
    private data class UndoEntry(val filePath: String, val content: String?, val existed: Boolean)

    /**
     * 一份可回滚现场：哪次操作留下的、什么时候、涉及哪些文件。
     *
     * 带上 kind 与时间，界面才能像配置审计那样列出时间线、按节点挑一个撤销，
     * 而不是只留一份「最近一次」。
     */
    @Serializable
    private data class UndoRecord(
        val kind: String,
        val createdAt: Long,
        val files: List<UndoEntry>
    )

    /** 现场摘要，供界面列时间线（不含文件内容）。 */
    data class UndoRecordInfo(
        /** 现场文件在磁盘上的名字，撤销时按它定位。 */
        val id: String,
        /** [KIND_RESTORE]（一次回退留下的）或 [KIND_UNDO]（一次撤销留下的）。 */
        val kind: String,
        val createdAt: Long,
        val fileCount: Int
    )

    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    // 活动 checkpoint 必须按会话隔离：多个会话（含父会话与其子代理）会并行跑 workflow，
    // 用单一字段会被后创建的会话覆盖，导致文件快照挂到别的会话名下，
    // 那个会话一撤销就会把本会话刚写好的文件还原/删除。
    private val activeCheckpointIds = ConcurrentHashMap<String, String>()

    /**
     * 在用户发送新消息时调用，创建一个新的 Checkpoint 节点
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        val entity = CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAt = System.currentTimeMillis()
        )
        checkpointDao.insertCheckpoint(entity)
        activeCheckpointIds[sessionId] = checkpointId
        entity
    }

    fun setActiveCheckpointId(sessionId: String, checkpointId: String?) {
        if (checkpointId == null) {
            activeCheckpointIds.remove(sessionId)
        } else {
            activeCheckpointIds[sessionId] = checkpointId
        }
    }

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String
    ) = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext

        // 查重：同一个 checkpointId 内对同一文件只保留最原始的一次快照
        if (checkpointDao.countSnapshot(checkpointId, filePath) > 0) {
            return@withContext
        }

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        if (changeType == "MODIFY") {
            val originalContent = fileAccess.readFile(filePath)
            snapshotFile.writeText(originalContent)
        } else {
            snapshotFile.writeText("") // 标示创建空记录
        }

        val snapshotEntity = CheckpointFileSnapshotEntity(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType
        )
        checkpointDao.insertFileSnapshot(snapshotEntity)
        FileLogger.i(TAG, "快照: session=$sessionId cp=$checkpointId type=$changeType path=$filePath")
    }

    /**
     * 文件被 AI **成功写入后**调用：记下写入后的内容摘要。
     * 回退时用它区分「文件仍是 AI 写下的样子」与「已被检查点之外的操作改过」。
     */
    suspend fun afterFileModified(sessionId: String, filePath: String) = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointIds[sessionId] ?: return@withContext
        val content = runCatching { fileAccess.readFile(filePath) }.getOrNull() ?: return@withContext
        runCatching { checkpointDao.updateWrittenHash(checkpointId, filePath, sha256(content)) }
            .onFailure { FileLogger.w(TAG, "记录写入摘要失败: $filePath", it) }
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态。
     *
     * 每个文件在还原前比对当前内容摘要与快照记录的写入摘要：不一致说明它被检查点之外的操作改过，
     * 这时**不覆盖**它，而是记进 [RestoreResult.conflicts] 交由上层提示用户（避免把人家的改动冲掉）。
     * 还原前的现场会另存一份，供 [undoLastRestore] 反悔。
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): RestoreResult = withContext(Dispatchers.IO) {
        val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext RestoreResult(0, emptyList())

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0
        val conflicts = mutableListOf<String>()
        val undoEntries = mutableListOf<UndoEntry>()
        FileLogger.i(
            TAG,
            "还原开始: session=$sessionId target=$targetCheckpointId 涉及 ${checkpointsToRollback.size} 个检查点"
        )

        for (cp in checkpointsToRollback) {
            val snapshots = checkpointDao.getFileSnapshotsForCheckpoint(cp.id)
            for (snapshot in snapshots) {
                if (snapshot.changeType == "MODIFY") {
                    val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)
                    if (!snapshotFile.exists()) continue
                    val original = snapshotFile.readText()
                    val current = runCatching { fileAccess.readFile(snapshot.filePath) }.getOrNull()

                    // 冲突：内容既不是 AI 写下的样子、也不是原始内容，说明被检查点之外的操作动过，跳过不覆盖。
                    // 写入摘要缺失时同样按冲突处理：来历无从判断的文件宁可少还原一个，也不能无声盖掉别人的改动。
                    val externallyModified = current != null && current != original &&
                        (snapshot.writtenHash == null || sha256(current) != snapshot.writtenHash)
                    if (externallyModified) {
                        conflicts.add(snapshot.filePath)
                        val reason = if (snapshot.writtenHash == null) "写入摘要缺失" else "内容与 AI 写入不符"
                        FileLogger.w(TAG, "跳过被外部改动的文件（$reason）: ${snapshot.filePath}")
                        continue
                    }

                    undoEntries.add(UndoEntry(snapshot.filePath, current, existed = current != null))
                    fileAccess.writeFile(snapshot.filePath, original, overwrite = true)
                    restoredFileCount++
                } else if (snapshot.changeType == "CREATE") {
                    // 若是原先新建的文件，回滚时安全删除
                    if (fileAccess.exists(snapshot.filePath)) {
                        val current = runCatching { fileAccess.readFile(snapshot.filePath) }.getOrNull()
                        undoEntries.add(UndoEntry(snapshot.filePath, current, existed = true))
                        fileAccess.delete(snapshot.filePath)
                        restoredFileCount++
                    }
                }
            }
        }
        saveUndo(sessionId, KIND_RESTORE, undoEntries)
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件，冲突 ${conflicts.size} 个")
        RestoreResult(restoredFileCount, conflicts)
    }

    /**
     * 预览还原到 [targetCheckpointId] 会涉及多少个文件快照，供回退前的确认提示展示。
     *
     * 只数不动文件；范围与 [restoreCodeToCheckpoint] 同源，但它是上限——被判定为冲突的文件会被跳过。
     */
    suspend fun countFilesToRestore(sessionId: String, targetCheckpointId: String): Int =
        withContext(Dispatchers.IO) {
            val allCheckpoints = checkpointDao.getCheckpointsForSession(sessionId)
            val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
            if (targetIndex == -1) return@withContext 0
            allCheckpoints.subList(targetIndex, allCheckpoints.size)
                .sumOf { checkpointDao.getFileSnapshotsForCheckpoint(it.id).size }
        }

    /** 列出现场时间线，新的在前；界面据此让用户挑一个时间节点撤销。 */
    fun listUndoRecords(sessionId: String): List<UndoRecordInfo> =
        undoRecords(sessionId).mapNotNull { file ->
            val record = readUndo(file) ?: return@mapNotNull null
            UndoRecordInfo(file.name, record.kind, record.createdAt, record.files.size)
        }

    /** 撤销最近一次恢复。 */
    suspend fun undoLastRestore(sessionId: String): Int = undoRestoreTo(sessionId, null)

    /**
     * 撤销到指定现场（[recordId] 取自 [listUndoRecords]；为空即最新一份），返回还原的文件数。
     *
     * 撤销前先把当前现场也存一份——撤销本身同样会盖文件，得给它留个反向的后悔键，
     * 于是来回跳都不丢东西。目标现场用过即删，不会再出现在时间线里。
     */
    suspend fun undoRestoreTo(sessionId: String, recordId: String?): Int = withContext(Dispatchers.IO) {
        val records = undoRecords(sessionId)
        val target = recordId?.let { id -> records.firstOrNull { it.name == id } }
            ?: records.firstOrNull()
            ?: return@withContext 0
        val record = readUndo(target) ?: return@withContext 0

        saveUndo(
            sessionId,
            KIND_UNDO,
            record.files.map { entry ->
                val content = runCatching { fileAccess.readFile(entry.filePath) }.getOrNull()
                UndoEntry(entry.filePath, content, existed = content != null)
            }
        )

        var count = 0
        record.files.forEach { entry ->
            runCatching {
                if (entry.existed && entry.content != null) {
                    fileAccess.writeFile(entry.filePath, entry.content, overwrite = true)
                } else {
                    fileAccess.delete(entry.filePath)
                }
            }.onSuccess { count++ }
                .onFailure { FileLogger.w(TAG, "撤销恢复失败: ${entry.filePath}", it) }
        }
        target.delete()
        FileLogger.i(TAG, "已撤销到现场 ${target.name}: session=$sessionId 处理 $count 个文件，剩余 ${undoRecordCount(sessionId)} 个")
        count
    }

    /** 还能撤销几次（现场条数），供界面提示。 */
    fun undoRecordCount(sessionId: String): Int = undoRecords(sessionId).size

    /** 是否还有可撤销的现场。 */
    fun hasUndoRecord(sessionId: String): Boolean = undoRecords(sessionId).isNotEmpty()

    /**
     * 删除 Session 关联的所有 Checkpoint 快照与记录
     */
    suspend fun clearSessionCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        activeCheckpointIds.remove(sessionId)
        checkpointDao.deleteFileSnapshotsForSession(sessionId)
        checkpointDao.deleteCheckpointsForSession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    /**
     * 存一份可回滚现场。每次恢复/撤销各存一份、各自成文件，于是能像时间线一样往回选，
     * 而不是只能撤最近一次。
     *
     * 超过 [MAX_UNDO_RECORDS] 就删最旧的：现场里放的是整份文件内容，不设上限会把磁盘吃掉。
     */
    private fun saveUndo(sessionId: String, kind: String, entries: List<UndoEntry>) {
        if (entries.isEmpty()) return
        runCatching {
            val dir = undoDir(sessionId)
            dir.mkdirs()
            val now = System.currentTimeMillis()
            val name = "$now-${UUID.randomUUID().toString().take(6)}.json"
            File(dir, name).writeText(Json.encodeToString(UndoRecord(kind, now, entries)))
            undoRecords(sessionId).drop(MAX_UNDO_RECORDS).forEach { it.delete() }
        }.onFailure { FileLogger.w(TAG, "写入撤销记录失败", it) }
    }

    /** 现场目录：一条一次，文件名以时间戳开头。 */
    private fun undoDir(sessionId: String): File = File(baseCheckpointDir, "$sessionId/undo")

    /** 可回滚现场，新的在前。[legacyUndoFile] 是升级前留下的单份现场，排到最后。 */
    private fun undoRecords(sessionId: String): List<File> {
        val records = undoDir(sessionId).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            .orEmpty()
        val legacy = legacyUndoFile(sessionId)
        return if (legacy.isFile) records + legacy else records
    }

    /** 读一份现场；升级前的老格式是裸的 `List<UndoEntry>`，没有 kind/时间，用文件修改时间兜底。 */
    private fun readUndo(file: File): UndoRecord? = runCatching {
        val text = file.readText()
        runCatching { Json.decodeFromString<UndoRecord>(text) }.getOrElse {
            UndoRecord(KIND_RESTORE, file.lastModified(), Json.decodeFromString<List<UndoEntry>>(text))
        }
    }.getOrElse { e ->
        FileLogger.w(TAG, "读取撤销记录失败: ${file.name}", e)
        null
    }

    /** 升级前的老格式：单份现场直接放在会话目录下。 */
    private fun legacyUndoFile(sessionId: String): File =
        File(baseCheckpointDir, "$sessionId/.undo.json")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "CheckpointManager"

        /** 最多保留多少份可回滚现场：每份装着整批文件的原文，放开会吃磁盘。 */
        const val MAX_UNDO_RECORDS = 20

        /** 现场来源：一次回退留下的。 */
        const val KIND_RESTORE = "restore"

        /** 现场来源：一次撤销留下的。 */
        const val KIND_UNDO = "undo"
    }
}
