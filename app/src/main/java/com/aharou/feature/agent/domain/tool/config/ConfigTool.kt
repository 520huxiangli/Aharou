package com.aharou.feature.agent.domain.tool.config

import android.content.Context
import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.confirm.ConfigConfirmationGate
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.maskedForDisplay
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
            "action=set 修改字段（path + value，value 为 JSON 编码，如 \"🦊\"、\"zh\"、true）；" +
            "action=add 在集合下新增一项（path=集合名、value=新项 JSON）；action=remove 删除集合下的一项（path=集合名、id）。" +
            "set/add/remove 都会弹出确认面板，用户同意后生效，并写审计、可回滚。"

    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING, "操作类型：list / get / set / add / remove", true,
            enum = listOf("list", "get", "set", "add", "remove"),
        ),
        "path" to ToolParameter("path", ParameterType.STRING, "字段路径，如 soul.name；add/remove 时为集合名，如 providers", false),
        "value" to ToolParameter("value", ParameterType.STRING, "set 的目标值 / add 的新项（JSON 编码）", false),
        "id" to ToolParameter("id", ParameterType.STRING, "remove 时集合内要删除的子项 id", false),
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
                "add" -> {
                    val raw = (args["value"] as? JsonPrimitive)?.contentOrNull
                    addChild(registry, path, raw)
                }
                "remove" -> {
                    val id = (args["id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    removeChild(registry, path, id)
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
            if (fields.isEmpty()) {
                // 空集合仍要列出来，否则使用者会以为这类配置不存在。
                if (registry.collection(topic) != null) {
                    sb.append("· ").append(topic).append("（空，暂无子项，可用 add 新增）\n")
                }
                continue
            }
            sb.append("· ").append(topic).append('\n')
            for (f in fields) {
                sb.append("  - ").append(f.path).append("（").append(f.displayName).append("）")
                if (f.access != ConfigAccess.READWRITE) sb.append(" [只读]")
                sb.append('\n')
            }
        }
        return ToolResult.Success(buildJsonObject { put("result", sb.toString().trim()) })
    }

    /** 供工具输出用的显式类型名，弥补 JSON 字面量看不出类型的短板。 */
    private fun ConfigSchema.typeName(): String = when (this) {
        ConfigSchema.Bool -> "boolean"
        is ConfigSchema.Int -> "integer"
        is ConfigSchema.Double -> "number"
        is ConfigSchema.Str -> "string"
        is ConfigSchema.StrEnum -> "string（枚举：${cases.joinToString("/")}）"
        ConfigSchema.Path -> "path"
        is ConfigSchema.Optional -> "optional(${inner.typeName()})"
        is ConfigSchema.Array -> "array(${inner.typeName()})"
        ConfigSchema.Json -> "json"
    }

    private fun getField(registry: ConfigRegistry, path: String): ToolResult {
        if (path.isBlank()) return ToolResult.Error("需要 path", "MISSING_PATH")
        val field = registry.resolveField(path)
        if (field == null) {
            // 集合本身不是字段，但能被 get：返回子项 id 列表，供继续解析
            // `<集合名>.<id>.<子字段>`。
            val coll = registry.collection(path)
            if (coll != null) {
                return ToolResult.Success(buildJsonObject {
                    put("path", path)
                    put("displayName", coll.displayName)
                    put("kind", "collection")
                    put("children", coll.childIds().joinToString(","))
                    put("description", coll.description)
                })
            }
            return ToolResult.Error("未知字段：$path（先用 action=list 看看）", "UNKNOWN_PATH")
        }
        if (field.access == ConfigAccess.HIDDEN) {
            return ToolResult.Error("该字段不可读：$path", "HIDDEN_FIELD")
        }
        val value = field.read()
        val sensitive = field.risk == ConfigRisk.SENSITIVE
        return ToolResult.Success(buildJsonObject {
            put("path", field.path)
            put("displayName", field.displayName)
            // value 是 JSON 字面量（字符串带引号、布尔裸文本），单看 value 容易
            // 误判类型，所以额外回一个显式类型名。
            put("type", field.valueSchema.typeName())
            // 敏感字段只回打码值：工具返回值会进模型上下文、发给第三方 API，
            // 真值一旦回传就等同于外泄。写/改仍可用 action=set，不需要先读真值。
            put("value", value.maskedForDisplay(sensitive).jsonString())
            if (sensitive) put("sensitive", true)
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

        val sensitive = field.risk == ConfigRisk.SENSITIVE
        val item = PendingConfigChangeItem(
            displayName = field.displayName,
            path = field.path,
            oldDisplay = preview(old.maskedForDisplay(sensitive)),
            newDisplay = preview(parsed.maskedForDisplay(sensitive)),
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

    /**
     * 在集合下新增一项。payload 走 [ConfigValue.redactingSecrets] 后进确认弹窗：
     * 真实（未打码）的值仍传给集合的 add() 落盘，只有给用户看的副本被遮住。
     */
    private suspend fun addChild(registry: ConfigRegistry, basePath: String, rawPayload: String?): ToolResult {
        if (basePath.isBlank()) return ToolResult.Error("需要 path（集合名，如 providers）", "MISSING_PATH")
        val coll = registry.collection(basePath)
            ?: return ToolResult.Error("未知集合：$basePath（先用 action=list 看看）", "UNKNOWN_COLLECTION")
        if (!coll.addable) return ToolResult.Error("该集合不可新增：$basePath", "NOT_ADDABLE")
        val payload = rawPayload?.let { ConfigValue.decode(it) }
            ?: return ToolResult.Error("add 需要 value（新项的 JSON）", "MISSING_VALUE")
        coll.addPayloadSchema.validate(payload)

        val display = payload.redactingSecrets().jsonString()
        val item = PendingConfigChangeItem(
            displayName = coll.displayName,
            path = basePath,
            oldDisplay = "—",
            newDisplay = preview(ConfigValue.Str(display)),
            verb = "新增",
            risk = coll.risk,
        )
        val change = PendingConfigChange(
            items = listOf(item),
            caption = "Aharou 想新增一项${coll.displayName}",
        )
        return when (val outcome = ConfigConfirmationGate.requestConfirmation(change)) {
            is ConfirmOutcome.Approved -> {
                val newId = coll.add(payload)
                ToolResult.Success(buildJsonObject {
                    put("message", "已在 $basePath 新增一项（用户已确认）")
                    put("id", newId)
                })
            }
            ConfirmOutcome.Rejected -> ToolResult.Error("用户驳回了这次新增：$basePath", "USER_REJECTED")
            ConfirmOutcome.TimedOut -> ToolResult.Error("确认超时（120 秒），未新增：$basePath", "CONFIRM_TIMEOUT")
        }
    }

    /** 删除集合下的一项（按 id）。
     *
     * 与 set/add 不同，这里不写审计行——审计表以「字段路径 old→new」为粒度，
     * 而删除的是一整条结构化记录，用同一形状记录反而会误导（见 [ConfigCollection] 首段说明）。
     */
    private suspend fun removeChild(registry: ConfigRegistry, basePath: String, id: String): ToolResult {
        if (basePath.isBlank()) return ToolResult.Error("需要 path（集合名，如 providers）", "MISSING_PATH")
        if (id.isBlank()) return ToolResult.Error("remove 需要 id", "MISSING_ID")
        val coll = registry.collection(basePath)
            ?: return ToolResult.Error("未知集合：$basePath（先用 action=list 看看）", "UNKNOWN_COLLECTION")
        if (!coll.removable) return ToolResult.Error("该集合不可删除：$basePath", "NOT_REMOVABLE")
        if (id !in coll.childIds()) {
            return ToolResult.Error("集合 $basePath 下没有 id=$id（先用 action=get 看子项）", "UNKNOWN_CHILD")
        }

        val item = PendingConfigChangeItem(
            displayName = coll.displayName,
            path = "$basePath.$id",
            oldDisplay = preview(ConfigValue.Str(id)),
            newDisplay = "（已删除）",
            verb = "删除",
            risk = coll.risk,
        )
        val change = PendingConfigChange(
            items = listOf(item),
            caption = "Aharou 想删除一项${coll.displayName}",
        )
        return when (val outcome = ConfigConfirmationGate.requestConfirmation(change)) {
            is ConfirmOutcome.Approved -> {
                coll.remove(id)
                ToolResult.Success(buildJsonObject {
                    put("message", "已从 $basePath 删除 $id（用户已确认）")
                })
            }
            ConfirmOutcome.Rejected -> ToolResult.Error("用户驳回了这次删除：$basePath.$id", "USER_REJECTED")
            ConfirmOutcome.TimedOut -> ToolResult.Error("确认超时（120 秒），未删除：$basePath.$id", "CONFIRM_TIMEOUT")
        }
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
