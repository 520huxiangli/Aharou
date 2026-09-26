package com.aharou.feature.agent.domain.tool.config

import android.content.Context
import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.confirm.ConfigConfirmationGate
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.audit.ConfigAuditActor
import com.aharou.core.config.audit.ConfigAuditEntry
import com.aharou.core.config.audit.ConfigAuditLog
import com.aharou.core.config.audit.ConfigAuditStatus
import com.aharou.core.config.confirm.ConfirmOutcome
import com.aharou.core.config.confirm.PendingConfigChange
import com.aharou.core.config.confirm.PendingConfigChangeItem
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject

/**
 * 配置通道工具（自 上游项目 的 minis-config 移植 · Aharou 版）。
 *
 * 让 Agent 读写「Aharou 自身设置」：list 列字段 / get 读值 / set 写值。
 * set 全流程：模式校验 → 最多 120 秒的确认弹窗（用户批）→ 生效并落审计（可回滚）。
 * 注：人格 SOUL.md 与记忆等外置文件（容器内 /root/.aharou/ 目录）无需本工具，文件与 shell 可直接读写。
 */
class ConfigTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AgentTool() {

    private companion object {
        const val TAG = "ConfigTool"
    }

    override val name = "config"

    override val description =
        "读写 Aharou 自身设置（配置通道）。action=list 列出可配字段；action=get 读字段（path）；" +
            "action=set 修改字段（path + value，value 为 JSON 编码，如 \"🦊\"、\"zh\"、true）。" +
            "set 会弹出确认面板，用户同意后生效，并写审计、可回滚。"

    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "操作类型：list / get / set", true,
            enum = listOf("list", "get", "set"),
        ),
        "path" to ToolParameter("path", ParameterType.STRING, "字段路径，如 soul.name", false),
        "value" to ToolParameter("value", ParameterType.STRING, "set 的目标值（JSON 编码）", false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少 action（list / get / set）", "MISSING_ACTION")
        val path = (args["path"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        return try {
            val registry = ConfigRegistry.get()
            when (action) {
                "list" -> listFields(registry)
                "get" -> getField(registry, path)
                "set" -> {
                    val raw = (args["value"] as? JsonPrimitive)?.contentOrNull
                        ?: return ToolResult.Error("set 需要 value（JSON 编码）", "MISSING_VALUE")
                    setField(registry, path, raw)
                }
                else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "config tool failed: ${e.message}", e)
            ToolResult.Error("配置操作失败：${e.message}")
        }
    }

    private fun listFields(registry: ConfigRegistry): ToolResult {
        val sb = StringBuilder("可用配置字段：\n")
        for (topic in registry.topics()) {
            val fields = registry.fields(topic).filter { it.access != ConfigAccess.HIDDEN }
            if (fields.isEmpty()) continue
            sb.append("· ").append(topic).append('\n')
            for (f in fields) {
                sb.append("  - ").append(f.path).append("（").append(f.displayName).append("）")
                if (f.access != ConfigAccess.READWRITE) sb.append(" [只读]")
                sb.append('\n')
            }
        }
        return ToolResult.Success(buildJsonObject { put("result", sb.toString().trim()) })
    }

    private fun getField(registry: ConfigRegistry, path: String): ToolResult {
        if (path.isBlank()) return ToolResult.Error("需要 path", "MISSING_PATH")
        val field = registry.resolveField(path)
            ?: return ToolResult.Error("未知字段：$path（先用 action=list 看看）", "UNKNOWN_PATH")
        if (field.access == ConfigAccess.HIDDEN) {
            return ToolResult.Error("该字段不可读：$path", "HIDDEN_FIELD")
        }
        val value = field.read()
        return ToolResult.Success(buildJsonObject {
            put("path", field.path)
            put("displayName", field.displayName)
            put("value", value.jsonString())
        })
    }

    private suspend fun setField(registry: ConfigRegistry, path: String, rawValue: String): ToolResult {
        if (path.isBlank()) return ToolResult.Error("需要 path", "MISSING_PATH")
        val field = registry.resolveField(path)
            ?: return ToolResult.Error("未知字段：$path", "UNKNOWN_PATH")
        if (field.access != ConfigAccess.READWRITE) {
            return ToolResult.Error("字段不可写（${field.access.name}）：$path", "NOT_WRITABLE")
        }
        val parsed = ConfigValue.decode(rawValue)
            ?: return ToolResult.Error("value 不是合法 JSON：$rawValue", "BAD_JSON")
        field.valueSchema.validate(parsed)
        val old = field.read()

        val item = PendingConfigChangeItem(
            displayName = field.displayName,
            path = field.path,
            oldDisplay = preview(old),
            newDisplay = preview(parsed),
            verb = "修改",
            risk = field.risk,
        )
        val change = PendingConfigChange(
            items = listOf(item),
            caption = "Aharou 想修改自己的设置",
        )
        return when (val outcome = ConfigConfirmationGate.requestConfirmation(change)) {
            is ConfirmOutcome.Approved -> {
                field.write(parsed)
                appendAudit(field, old, parsed, ConfigAuditStatus.APPLIED, System.currentTimeMillis())
                ToolResult.Success(buildJsonObject {
                    put("message", "已修改 ${field.path}（用户已确认）")
                    put("value", parsed.jsonString())
                })
            }
            ConfirmOutcome.Rejected -> {
                appendAudit(field, old, parsed, ConfigAuditStatus.REJECTED, null)
                ToolResult.Error("用户驳回了这次修改：${field.path}", "USER_REJECTED")
            }
            ConfirmOutcome.TimedOut -> {
                appendAudit(field, old, parsed, ConfigAuditStatus.TIMEOUT, null)
                ToolResult.Error("确认超时（120 秒），修改未生效：${field.path}", "CONFIRM_TIMEOUT")
            }
        }
    }

    private fun preview(v: ConfigValue): String {
        val s = v.jsonString()
        return if (s.length <= 80) s else s.take(77) + "…"
    }

    private fun appendAudit(
        field: ConfigField,
        old: ConfigValue,
        new: ConfigValue,
        status: ConfigAuditStatus,
        confirmedAt: Long?,
    ) {
        runCatching {
            ConfigAuditLog.get().append(
                ConfigAuditEntry(
                    id = UUID.randomUUID().toString(),
                    at = System.currentTimeMillis(),
                    actor = ConfigAuditActor.AGENT,
                    sessionId = null,
                    scope = field.scope.ifBlank { field.path.substringBefore('.') },
                    key = field.path,
                    oldValueJSON = old.jsonString(),
                    newValueJSON = new.jsonString(),
                    confirmedAt = confirmedAt,
                    status = status,
                    revertOf = null,
                    caption = null,
                ),
            )
        }
    }
}
