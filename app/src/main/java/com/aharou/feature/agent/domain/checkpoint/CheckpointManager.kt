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

    /** 撤销恢复所需的现场记录。 */
    @Serializable
    private data class UndoEntry(val filePath: String, val content: String?, val existed: Boolean)

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

                    // 冲突：AI 写过这个文件，但当前内容既不是它写下的样子、也不是原始内容
                    if (snapshot.writtenHash != null && current != null &&
                        sha256(current) != snapshot.writtenHash && current != original
                    ) {
                        conflicts.add(snapshot.filePath)
                        FileLogger.w(TAG, "跳过被外部改动的文件: ${snapshot.filePath}")
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
        saveUndo(sessionId, undoEntries)
        FileLogger.i(TAG, "还原结束: session=$sessionId 共处理 $restoredFileCount 个文件，冲突 ${conflicts.size} 个")
        RestoreResult(restoredFileCount, conflicts)
    }

    /** 撤销最近一次恢复：把当时的现场写回去。返回还原的文件数；没有可撤销的现场时返回 0。 */
    suspend fun undoLastRestore(sessionId: String): Int = withContext(Dispatchers.IO) {
        val file = undoFile(sessionId)
        if (!file.isFile) return@withContext 0
        val entries = runCatching {
            Json.decodeFromString<List<UndoEntry>>(file.readText())
        }.getOrElse {
            FileLogger.w(TAG, "撤销记录解析失败: ${it.message}", it)
            return@withContext 0
        }

        var count = 0
        entries.forEach { entry ->
            runCatching {
                if (entry.existed && entry.content != null) {
                    fileAccess.writeFile(entry.filePath, entry.content, overwrite = true)
                } else {
                    fileAccess.delete(entry.filePath)
                }
            }.onSuccess { count++ }
                .onFailure { FileLogger.w(TAG, "撤销恢复失败: ${entry.filePath}", it) }
        }
        file.delete()
        FileLogger.i(TAG, "已撤销最近一次恢复: session=$sessionId 处理 $count 个文件")
        count
    }

    /** 是否还有可撤销的恢复现场。 */
    fun hasUndoRecord(sessionId: String): Boolean = undoFile(sessionId).isFile

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

    private fun saveUndo(sessionId: String, entries: List<UndoEntry>) {
        val file = undoFile(sessionId)
        runCatching {
            if (entries.isEmpty()) {
                file.delete()
            } else {
                file.parentFile?.mkdirs()
                file.writeText(Json.encodeToString(entries))
            }
        }.onFailure { FileLogger.w(TAG, "写入撤销记录失败", it) }
    }

    private fun undoFile(sessionId: String): File = File(baseCheckpointDir, "$sessionId/.undo.json")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val TAG = "CheckpointManager"
    }
}
