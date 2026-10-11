package com.aharou.feature.agent.domain.permission

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.vault.VaultRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.util.concurrent.Executors

/**
 * 决策级审计：每次工具调用的裁决落一行 JSONL（append-only），供事后追溯「谁在什么会话里对哪个工具
 * 做了放行/询问/拒绝、因为什么、是否被污染、发往哪里」。
 *
 * 移植自 nanoMuse `audit.py` 的 `tool_call` 事件，字段对齐 `gate.py`：时间/会话/工具名/决策/风险档/
 * 原因/是否 tainted/egress 目标/是否被批准/授权档位/耗时/输出预览（截断并脱敏）。
 *
 * 落盘位置与现有日志一致（`getExternalFilesDir/logs/`，与 [FileLogger] 同一目录同一天一份），文件名
 * `permission-audit-yyyy-MM-dd.jsonl`。写入串行化到单线程、异常吞掉不抛，绝不影响授权主流程。
 *
 * 脱敏：输出预览先经 [com.aharou.core.util.MediaRedactor] 去掉超大 base64，再截断到 [PREVIEW_LIMIT] 字符；
 * 参数不落盘（只记工具名与目标键），避免把用户数据写进审计。
 */
object DecisionAuditLog {

    private const val TAG = "PermissionAudit"
    private const val PREVIEW_LIMIT = 300

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "permission-audit").apply { isDaemon = true }
    }

    private val json = Json { encodeDefaults = false }

    @Volatile
    private var dir: File? = null

    /** 初始化落盘目录，与 [FileLogger.init] 用同一套目录解析规则（外部私有目录优先，回退内部）。重复调用安全。 */
    fun init(context: Context) {
        if (dir != null) return
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        dir = File(base, "logs").apply { mkdirs() }
        FileLogger.i(TAG, "权限决策审计初始化完成，目录: ${dir?.absolutePath}")
    }

    /**
     * 落一条 tool_call 审计。所有字段可空/缺省，缺失即不写。写入失败只记日志、不抛。
     *
     * @param outputPreview 输出预览原文（会先脱敏再截断）。
     */
    fun recordToolCall(
        sessionId: String?,
        toolName: String,
        decision: String,
        riskLevel: String?,
        reasons: List<String>,
        tainted: Boolean,
        egressTarget: String?,
        approved: Boolean?,
        approvalScope: String?,
        durationMs: Long?,
        outputPreview: String?
    ) {
        val entry = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        entry["ts"] = JsonPrimitive(java.time.Instant.now().toString())
        if (!sessionId.isNullOrBlank()) entry["session"] = JsonPrimitive(sessionId)
        entry["tool"] = JsonPrimitive(toolName)
        entry["decision"] = JsonPrimitive(decision)
        riskLevel?.let { entry["risk"] = JsonPrimitive(it) }
        if (reasons.isNotEmpty()) entry["reasons"] = JsonArray(reasons.map { JsonPrimitive(it) })
        entry["tainted"] = JsonPrimitive(tainted)
        egressTarget?.let { entry["egress_target"] = JsonPrimitive(it) }
        approved?.let { entry["approved"] = JsonPrimitive(it) }
        approvalScope?.let { entry["approval_scope"] = JsonPrimitive(it) }
        durationMs?.let { entry["duration_ms"] = JsonPrimitive(it) }
        if (!outputPreview.isNullOrEmpty()) entry["output_preview"] = JsonPrimitive(redact(outputPreview))
        write(JsonObject(entry))
    }

    private fun redact(text: String): String {
        // 与 FileLogger 同一套：媒体脱敏去掉超大 base64，再过凭据库脱敏防 vault 真值落盘。
        val safe = VaultRedactor.redact(com.aharou.core.util.MediaRedactor.redact(text))
        return if (safe.length > PREVIEW_LIMIT) safe.take(PREVIEW_LIMIT) + "…" else safe
    }

    private fun write(obj: JsonObject) {
        val target = dir ?: return // 未初始化则静默丢弃，不影响授权流程
        val line = runCatching { json.encodeToString(JsonObject.serializer(), obj) }.getOrNull() ?: return
        val day = java.time.LocalDate.now().toString()
        ioExecutor.execute {
            runCatching {
                val file = File(target, "permission-audit-$day.jsonl")
                file.appendText(line + "\n")
            }.onFailure { FileLogger.w(TAG, "写入权限审计失败: ${it.message}") }
        }
    }
}
