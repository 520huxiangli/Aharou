package com.aharou.feature.vault

import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具执行的最终参数：仅当工具声明 [com.aharou.feature.agent.domain.tool.AgentTool.acceptsSecrets] 时
 * 才把占位符换成真值（审批通过之后、执行之前）；其余工具保持原样——这类调用已在权限关卡被拒绝，
 * 绝不拿字面量去跑。
 */
fun VaultRepository.execArgs(
    toolAcceptsSecrets: Boolean,
    args: Map<String, JsonElement>,
): Map<String, JsonElement> =
    if (toolAcceptsSecrets && hasPlaceholders(args)) resolveMap(args) else args

/** 工具结果整体脱敏：递归处理 JSON 文本叶子与消息/错误文本，命中库内值即替换为掩码。 */
fun ToolResult.redactWith(vault: VaultRepository): ToolResult = when (this) {
    is ToolResult.Success -> copy(data = data.redactWith(vault))
    is ToolResult.Partial -> copy(data = data.redactWith(vault), message = vault.redact(message))
    is ToolResult.Error -> copy(message = vault.redact(message))
}

private fun JsonElement.redactWith(vault: VaultRepository): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { it.value.redactWith(vault) })
    is JsonArray -> JsonArray(map { it.redactWith(vault) })
    is JsonPrimitive -> if (isString) JsonPrimitive(vault.redact(content)) else this
}
