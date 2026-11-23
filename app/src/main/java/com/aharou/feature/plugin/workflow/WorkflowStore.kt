package com.aharou.feature.plugin.workflow

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * 工作流存储：应用私有目录下的一工作流一 JSON 文件。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`data/repository/WorkflowRepository.kt` 的读写面；
 * 差异是去掉 Room 与执行统计的数据库表，改成文件 + 一份追加式运行记录——工作流数量是个位数，
 * 为它建表不值得，而模板导入/手工排查都需要能直接看到 JSON。
 */
@Singleton
class WorkflowStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val rootDir: File by lazy { File(context.filesDir, DIR_NAME).apply { mkdirs() } }
    private val lock = Mutex()

    suspend fun list(): List<Workflow> = withContext(Dispatchers.IO) {
        lock.withLock {
            rootDir.listFiles { file -> file.isFile && file.name.endsWith(SUFFIX) }
                .orEmpty()
                .mapNotNull { readFile(it) }
                .sortedByDescending { it.updatedAt }
        }
    }

    suspend fun load(id: String): Workflow? = withContext(Dispatchers.IO) {
        lock.withLock { readFile(fileFor(id).takeIf { it.isFile }) }
    }

    suspend fun save(workflow: Workflow): Workflow = withContext(Dispatchers.IO) {
        lock.withLock {
            val normalized = if (workflow.id.isBlank()) workflow.copy(id = newId()) else workflow
            val stamped = normalized.copy(
                createdAt = normalized.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            writeFile(stamped)
            stamped
        }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        lock.withLock {
            val deleted = fileFor(id).delete()
            runsFileFor(id).delete()
            deleted
        }
    }

    /** 追加一条运行记录；文件过大时只保留最近 [MAX_RUN_LINES] 行。 */
    suspend fun appendRun(record: WorkflowRunRecord) = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = runsFileFor(record.workflowId)
            val line = WorkflowParseJson.encodeToString(record)
            runCatching { file.appendText(line + "\n") }
                .onFailure { FileLogger.w(TAG, "写工作流运行记录失败: ${record.workflowId}", it) }
            if (file.length() > MAX_RUN_FILE_BYTES) {
                runCatching {
                    val kept = file.readLines().takeLast(MAX_RUN_LINES)
                    file.writeText(kept.joinToString("\n", postfix = "\n"))
                }.onFailure { FileLogger.w(TAG, "裁剪工作流运行记录失败: ${record.workflowId}", it) }
            }
        }
    }

    suspend fun recentRuns(workflowId: String, limit: Int = DEFAULT_RUN_LIMIT): List<WorkflowRunRecord> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                val file = runsFileFor(workflowId)
                if (!file.isFile) return@withLock emptyList()
                runCatching {
                    file.readLines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { line ->
                            runCatching {
                                WorkflowParseJson.decodeFromString(WorkflowRunRecord.serializer(), line)
                            }.getOrNull()
                        }
                        .takeLast(limit)
                }.getOrElse {
                    FileLogger.w(TAG, "读取工作流运行记录失败: $workflowId", it)
                    emptyList()
                }
            }
        }

    private fun readFile(file: File?): Workflow? {
        if (file == null) return null
        return runCatching {
            WorkflowParseJson.decodeFromString(Workflow.serializer(), file.readText())
        }.getOrElse {
            // 单个文件损坏只跳过它，不影响整份工作流列表。
            FileLogger.w(TAG, "工作流文件解析失败: ${file.name}", it)
            null
        }
    }

    private fun writeFile(workflow: Workflow) {
        val target = fileFor(workflow.id)
        val temp = File(target.parentFile, "${target.name}.tmp")
        temp.writeText(WorkflowStorageJson.encodeToString(Workflow.serializer(), workflow))
        if (!temp.renameTo(target)) {
            // 某些文件系统上 rename 到已存在文件会失败，退一步覆盖写。
            target.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun fileFor(id: String): File = File(rootDir, "${safeId(id)}$SUFFIX")

    private fun runsFileFor(id: String): File = File(rootDir, "${safeId(id)}$RUNS_SUFFIX")

    /** 只允许安全字符：id 可能来自插件 API，不能让它带出目录。 */
    private fun safeId(id: String): String =
        id.map { c -> if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_') c else '_' }
            .joinToString("")
            .ifBlank { "workflow" }

    private fun newId(): String = "wf_" + java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private companion object {
        const val TAG = "WorkflowStore"
        const val DIR_NAME = "plugin-workflows"
        const val SUFFIX = ".json"
        const val RUNS_SUFFIX = ".runs.jsonl"
        const val MAX_RUN_FILE_BYTES = 256 * 1024L
        const val MAX_RUN_LINES = 100
        const val DEFAULT_RUN_LIMIT = 20
    }
}

/** 一次运行的落档记录，只留排障必需的信息，不保存整段节点输出。 */
@Serializable
data class WorkflowRunRecord(
    val runId: String,
    val workflowId: String,
    val workflowName: String,
    val triggerNodeId: String? = null,
    val startedAt: Long,
    val finishedAt: Long,
    val success: Boolean,
    val message: String,
    val failedNodes: List<String> = emptyList(),
    val skippedNodes: List<String> = emptyList(),
)
