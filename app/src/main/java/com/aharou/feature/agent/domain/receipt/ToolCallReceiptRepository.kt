package com.aharou.feature.agent.domain.receipt

import com.aharou.feature.agent.data.local.dao.ToolCallReceiptDao
import com.aharou.feature.agent.data.local.entity.ReceiptCategory
import com.aharou.feature.agent.data.local.entity.ReceiptStatus
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.vault.VaultRedactor
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工具调用回执的落库入口。
 *
 * 三条纪律：
 * - **写库失败不能影响工具返回**：回执是旁路记录，记不下来顶多面板缺一条，绝不能把异常抛回执行链。
 * - **落库前必须脱敏**：参数里常有密钥、令牌，摘要同样要过 [VaultRedactor]。
 * - **只存摘要**：完整大输出本来就已经由 tool-output 存档接管，这里只用 `output_path` 指过去。
 */
@Singleton
class ToolCallReceiptRepository @Inject constructor(
    private val dao: ToolCallReceiptDao
) {
    companion object {
        /** 摘要长度上限。够看清这一步做了什么，又不至于把整段输出塞进数据库。 */
        private const val PREVIEW_LIMIT = 300

        private const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000

        /** 清理节流：进程内每 6 小时最多扫一次旧记录。 */
        private const val PRUNE_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        /** 编译回执只认两分钟内的审计行，避免并发编译时读到上一次授权。 */
        private const val BUILD_LOG_FRESH_MILLIS = 120_000L

        private val GRADLEW_REGEX = Regex("""(^|[/\s])gradlew(\.bat)?(\s|$)""")
        private val OUTPUT_PATH_REGEX = Regex(""""output_path"\s*:\s*"([^"]+)"""")
        private val BUILD_TASK_REGEX = Regex("""task=(\S+)""")
        private val BUILD_TIME_REGEX = Regex("""^\[(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})]""")
        private val BUILD_LOG_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        private fun classify(toolName: String, args: Map<String, JsonElement>): ReceiptCategory {
            if (toolName != "Bash") return ReceiptCategory.TOOL
            val command = (args["command"] as? JsonPrimitive)?.content ?: return ReceiptCategory.TOOL
            return if (GRADLEW_REGEX.containsMatchIn(command)) ReceiptCategory.BUILD else ReceiptCategory.TOOL
        }

        private fun statusOf(result: ToolResult): ReceiptStatus = when (result) {
            is ToolResult.Success -> ReceiptStatus.SUCCESS
            is ToolResult.Partial -> ReceiptStatus.PARTIAL
            is ToolResult.Error -> ReceiptStatus.ERROR
        }

        /**
         * 结果摘要。Success 直接取 data 的文本形式：走传输串会把换行和引号全转义掉，
         * 面板上就是一行乱码。图片的 base64 一律不取，只记张数。
         */
        private fun summarize(result: ToolResult): String = when (result) {
            is ToolResult.Success -> buildString {
                append(result.data.toString())
                if (result.images.isNotEmpty()) append(" [images:").append(result.images.size).append(']')
            }
            is ToolResult.Partial -> result.message + " " + result.data
            is ToolResult.Error -> result.message
        }

        private fun truncate(text: String): String {
            val redacted = VaultRedactor.redact(text)
            return if (redacted.length <= PREVIEW_LIMIT) redacted else redacted.take(PREVIEW_LIMIT) + "…"
        }
    }

    @Volatile
    private var lastPruneAt = 0L

    private val recoveryMutex = Mutex()
    private val recoveryCutoff = System.currentTimeMillis()
    private var recoveryAttempted = false

    private suspend fun recoverOnce() = recoveryMutex.withLock {
        if (!recoveryAttempted) {
            dao.recoverRunning(recoveryCutoff)
            recoveryAttempted = true
        }
    }

    private suspend fun <T> sideChannel(block: suspend () -> T): T? = withContext(NonCancellable) {
        try { withTimeoutOrNull(2_000) { block() } } catch (_: Exception) { null }
    }

    fun observeSession(sessionId: String, limit: Int = 300): Flow<List<ToolCallReceiptEntity>> = flow {
        sideChannel { recoverOnce() }
        emitAll(dao.observeSession(sessionId, limit))
    }

    suspend fun beginExecution(sessionId: String?, toolName: String, args: Map<String, JsonElement>): Long? {
        if (sessionId.isNullOrBlank()) return null
        return sideChannel {
            recoverOnce()
            dao.insert(ToolCallReceiptEntity(sessionId = sessionId, toolName = toolName,
                category = classify(toolName, args).name, argsPreview = truncate(ReceiptPrivacy.arguments(toolName, args)),
                status = ReceiptStatus.RUNNING.name, createdAt = maxOf(System.currentTimeMillis(), recoveryCutoff + 1)))
        }
    }

    suspend fun finishExecution(id: Long?, toolName: String, args: Map<String, JsonElement>, result: ToolResult?,
        durationMs: Long, projectRoot: String?, interrupted: Boolean = false) {
        if (id == null) return
        sideChannel {
            val category = classify(toolName, args)
            val status = when {
                interrupted -> ReceiptStatus.INTERRUPTED
                result == null -> ReceiptStatus.UNKNOWN
                else -> statusOf(result)
            }
            val summary = if (result == null || ReceiptPrivacy.omitResult(toolName, args)) "" else ReceiptPrivacy.structuredText(summarize(result))
            dao.finishRunning(id, status.name, (result as? ToolResult.Error)?.code,
                truncate(summary), OUTPUT_PATH_REGEX.find(summary)?.groupValues?.get(1)?.let(ReceiptPrivacy::text),
                durationMs.coerceAtLeast(0), if (category == ReceiptCategory.BUILD) readBuildAuth(projectRoot)?.let(ReceiptPrivacy::text) else null)
            pruneIfDue()
        }
    }

    /**
     * 记一条工具执行回执。
     *
     * [sessionId] 为空表示这条调用不属于任何会话（如脱机脚本），直接跳过——
     * 回执按会话归档，没有会话就没有查询入口，存下来只是死数据。
     */
    suspend fun recordExecution(
        sessionId: String?,
        toolName: String,
        args: Map<String, JsonElement>,
        result: ToolResult,
        durationMs: Long,
        projectRoot: String?
    ) {
        if (sessionId.isNullOrBlank()) return
        sideChannel {
        val summary = if (ReceiptPrivacy.omitResult(toolName, args)) "" else ReceiptPrivacy.structuredText(summarize(result))
        val category = classify(toolName, args)
        val entity = ToolCallReceiptEntity(
            sessionId = sessionId,
            category = category.name,
            toolName = toolName,
            argsPreview = truncate(ReceiptPrivacy.arguments(toolName, args)),
            status = statusOf(result).name,
            errorCode = (result as? ToolResult.Error)?.code,
            resultPreview = truncate(summary),
            outputPath = OUTPUT_PATH_REGEX.find(summary)?.groupValues?.get(1),
            durationMs = durationMs,
            buildAuthJson = if (category == ReceiptCategory.BUILD) readBuildAuth(projectRoot)?.let(ReceiptPrivacy::text) else null,
            createdAt = System.currentTimeMillis()
        )
        recoverOnce()
        dao.insert(entity)
        pruneIfDue()
        }
    }

    /** 记一条「审批被拒」回执。被拒的调用根本没执行，只能从审批侧补进来。 */
    suspend fun recordDenied(
        sessionId: String?,
        toolName: String,
        argsPreview: String?
    ) {
        if (sessionId.isNullOrBlank()) return
        sideChannel {
        persist(
            ToolCallReceiptEntity(
                sessionId = sessionId,
                category = ReceiptCategory.DENIED.name,
                toolName = toolName,
                argsPreview = truncate(ReceiptPrivacy.structuredText(argsPreview.orEmpty())),
                // DENIED 本身就是终态，再分成败没有意义，status 留空由面板按类别渲染。
                status = "",
                createdAt = System.currentTimeMillis()
            )
        )
        }
    }

    private suspend fun persist(entity: ToolCallReceiptEntity) {
        sideChannel {
            recoverOnce()
            dao.insert(entity)
            pruneIfDue()
        }
    }

    private suspend fun pruneIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastPruneAt < PRUNE_INTERVAL_MILLIS) return
        lastPruneAt = now
        try {
            dao.deleteOlderThan(now - RETENTION_MILLIS)
        } catch (_: Exception) {
            // 清理失败只是旧记录多留一会儿，下个周期会再试。
        }
    }

    /**
     * 从门阀的审计文件末尾取本次编译的授权明细。
     *
     * gradlew 闸门在放行时把「谁批的、原话、任务名」追加进 `.aharou/build-audit.log`，
     * 但它只写不读；回执把这一行捞出来，编译记录才能自己说明授权出处。
     * 远程工作区下这里的路径不是本地文件，读不到就算了，不回退成伪造信息。
     */
    private fun readBuildAuth(projectRoot: String?): String? {
        if (projectRoot.isNullOrBlank()) return null
        val log = File(projectRoot, ".aharou/build-audit.log")
        if (!log.isFile) return null
        val line = try {
            log.useLines { lines -> lines.lastOrNull { it.isNotBlank() } }
        } catch (_: Exception) {
            return null
        } ?: return null

        val loggedAt = BUILD_TIME_REGEX.find(line)?.groupValues?.get(1)?.let { raw ->
            try {
                BUILD_LOG_TIME_FORMAT.parse(raw)?.time
            } catch (_: Exception) {
                null
            }
        } ?: return null
        if (System.currentTimeMillis() - loggedAt > BUILD_LOG_FRESH_MILLIS) return null

        val task = BUILD_TASK_REGEX.find(line)?.groupValues?.get(1).orEmpty()
        val authorization = line.substringAfter('|', "").trim()
        if (task.isBlank() && authorization.isBlank()) return null
        return JsonObject(
            mapOf(
                "task" to JsonPrimitive(task),
                "authorization" to JsonPrimitive(authorization)
            )
        ).toString()
    }
}
