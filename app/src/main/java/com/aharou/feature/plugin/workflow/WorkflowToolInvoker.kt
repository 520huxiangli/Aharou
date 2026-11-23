package com.aharou.feature.plugin.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.runtime.pluginToolRegistryName
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * 执行节点调用的实际落点：把 Operit 的 `actionType` 换算成 Aharou 的工具并执行。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`core/workflow/WorkflowExecutor.kt` 里
 * `executeNode` 的「构造 AITool → toolHandler.executeTool」一段，改写成查 [ToolRegistry]：
 * Aharou 的工具系统就是注册表，插件工具在注册时已带上 `plugin__<插件>__<工具>` 命名空间，
 * 直接按名字取比另建一套分发更贴近现有架构。
 */
@Singleton
class WorkflowToolInvoker @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val pluginManager: PluginManager,
    private val chatBridge: WorkflowChatBridge,
) {

    suspend fun invoke(actionType: String, args: Map<String, String>): WorkflowNodeOutcome {
        val trimmed = actionType.trim()
        if (trimmed.isEmpty()) {
            return WorkflowNodeOutcome.failure("执行节点没有 actionType，无法执行")
        }
        if (trimmed.contains(':')) {
            val packageName = trimmed.substringBefore(':').trim()
            val toolName = trimmed.substringAfter(':').trim()
            if (packageName.isEmpty() || toolName.isEmpty()) {
                return WorkflowNodeOutcome.failure("actionType 格式非法: $trimmed（应为「包名:工具名」）")
            }
            if (packageName == EXTENDED_CHAT_PACKAGE) {
                return chatBridge.invoke(toolName, args)
            }
            return invokePluginTool(packageName, toolName, args)
        }
        return invokeRegisteredTool(trimmed, args)
    }

    private suspend fun invokePluginTool(
        packageName: String,
        toolName: String,
        args: Map<String, String>,
    ): WorkflowNodeOutcome {
        val activePlugins = pluginManager.plugins.value.filter { it.isActive }
        val exact = activePlugins.firstNotNullOfOrNull { plugin ->
            if (!matchesPackage(plugin.id, packageName)) return@firstNotNullOfOrNull null
            plugin.content.tools.firstOrNull { it.localId == toolName }?.let { plugin.id to it.localId }
        }
        // 只有唯一一个插件声明了这个工具名时也认：Operit 模板里的包名是插件子包名，
        // 与 Aharou 的插件 id 未必逐字相同，强行按字符串相等会全部落空。
        val fallback = exact ?: activePlugins
            .mapNotNull { plugin ->
                plugin.content.tools.firstOrNull { it.localId == toolName }?.let { plugin.id }
            }
            .distinct()
            .singleOrNull()
            ?.let { it to toolName }

        val resolved = fallback
            ?: return WorkflowNodeOutcome.failure(
                "工作流引用的插件工具未注册: $packageName:$toolName。" +
                    "请确认插件已安装并启用、且声明了该工具；已尝试的插件: " +
                    activePlugins.joinToString { it.id }.ifEmpty { "（没有启用的插件）" }
            )
        val registryName = pluginToolRegistryName(resolved.first, resolved.second)
        return invokeRegisteredTool(registryName, args, displayName = "$packageName:$toolName")
    }

    private suspend fun invokeRegisteredTool(
        name: String,
        args: Map<String, String>,
        displayName: String = name,
    ): WorkflowNodeOutcome {
        val tool = toolRegistry.getTool(name)
            ?: return WorkflowNodeOutcome.failure(
                "工作流引用的工具不存在: $displayName（注册名 $name 不在 ToolRegistry 里）"
            )
        val prepared = dropBlankOptionalArgs(tool, args)
        val jsonArgs: Map<String, JsonElement> = toJsonArgs(tool, prepared)
        return try {
            when (val result = tool.execute(jsonArgs)) {
                is ToolResult.Success -> WorkflowNodeOutcome.success(result.data.asResultText())
                is ToolResult.Partial -> WorkflowNodeOutcome.success(
                    (result.data.asResultText() + "\n" + result.message).trim()
                )
                is ToolResult.Error -> WorkflowNodeOutcome.failure(
                    "工具 $displayName 执行失败: ${result.message} (${result.code})"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "工作流调用工具异常: $displayName", e)
            WorkflowNodeOutcome.failure("工具 $displayName 执行异常: ${e.message}")
        }
    }

    /**
     * Operit 会丢掉「可选且为空」的参数；不照做会把空串当真值塞给插件工具。
     * 必填项只能从工具 JSON Schema 的 `required` 里取：插件工具的
     * [com.aharou.feature.agent.domain.tool.AgentTool.parameters] 是空表，schema 才是事实源。
     */
    private fun dropBlankOptionalArgs(
        tool: com.aharou.feature.agent.domain.tool.AgentTool,
        args: Map<String, String>,
    ): Map<String, String> {
        val required = runCatching {
            @Suppress("UNCHECKED_CAST")
            (tool.toJsonSchema()["required"] as? List<Any?>)?.mapNotNull { it as? String }?.toSet().orEmpty()
        }.getOrDefault(emptySet())
        if (required.isEmpty()) return args
        return args.filterNot { (key, value) -> value.isBlank() && key !in required }
    }

    /**
     * 工作流里的参数值一律是字符串（Operit 的 `ToolParameter` 也是如此），
     * 但落到的工具是按 JSON Schema 声明的类型接收的：布尔写成 `"false"`，
     * 在 JS 里 `!!"false"` 恰好是 true——必须按 schema 先转换再传。
     */
    private fun toJsonArgs(
        tool: com.aharou.feature.agent.domain.tool.AgentTool,
        args: Map<String, String>,
    ): Map<String, JsonElement> {
        @Suppress("UNCHECKED_CAST")
        val properties = runCatching { tool.toJsonSchema()["properties"] as? Map<String, Any?> }
            .getOrNull().orEmpty()
        return args.mapValues { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            val type = ((properties[key] as? Map<*, *>)?.get("type") as? String)?.lowercase()
            when (type) {
                "boolean" -> value.trim().toBooleanStrictOrNull()?.let { JsonPrimitive(it) }
                    ?: when (value.trim().lowercase()) {
                        "1", "yes", "on" -> JsonPrimitive(true)
                        "0", "no", "off" -> JsonPrimitive(false)
                        else -> JsonPrimitive(value)
                    }
                "integer" -> value.trim().toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
                "number" -> value.trim().toDoubleOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(value)
                "array", "object" -> runCatching { Json.parseToJsonElement(value) }.getOrNull()
                    ?: JsonPrimitive(value)
                else -> JsonPrimitive(value)
            }
        }
    }

    private fun JsonElement.asResultText(): String = when (this) {
        is JsonNull -> ""
        is JsonPrimitive -> content
        else -> toString()
    }

    private fun matchesPackage(pluginId: String, packageName: String): Boolean {
        val sanitizedId = normalizeToken(pluginId)
        val sanitizedPkg = normalizeToken(packageName)
        return sanitizedId == sanitizedPkg || sanitizedId.endsWith("_$sanitizedPkg")
    }

    /** 统一下划线/连字符/大小写：插件 id 是 `operit.com-operit-gentle-guardian`，
     * Operit 模板里写的是子包名 `gentle_guardian`，直接比字符串永远不相等。 */
    private fun normalizeToken(value: String): String =
        value.map { if (it.isLetterOrDigit()) it.lowercaseChar() else '_' }.joinToString("")

    private companion object {
        const val TAG = "WorkflowToolInvoker"

        /** Operit 内置对话能力所在的伪包名，映射到 [WorkflowChatBridge]。 */
        const val EXTENDED_CHAT_PACKAGE = "extended_chat"
    }
}

/** 单个节点的执行结果：成功带一段文本（供下游引用），失败带明确原因。 */
data class WorkflowNodeOutcome(
    val success: Boolean,
    val output: String,
    val error: String? = null,
) {
    companion object {
        fun success(text: String) = WorkflowNodeOutcome(true, text)
        fun failure(reason: String) = WorkflowNodeOutcome(false, "", reason)
    }
}
