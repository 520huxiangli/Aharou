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

/**
 * 工具结果整体脱敏：递归处理 JSON 文本叶子与消息/错误文本，命中库内值即替换为掩码。
 *
 * 唯一例外是 [ToolResult.Success.plaintextApproved]——由工具在「用户当面批准读明文」之后置位
 * （当前只有 config 的 reveal 敏感字段分支）。这类结果原样返回：用户刚亲自点头，再抹掉等于白批。
 * 该标记是结果上的类型化字段，不参与序列化，模型既看不到也无法通过在参数里塞内容来伪造。
 */
fun ToolResult.redactWith(vault: VaultRepository): ToolResult = redactUsing(vault::redact)

/** [redactWith] 的纯函数内核：脱敏动作由 [redactor] 提供，便于单测脱离 VaultRepository。 */
internal fun ToolResult.redactUsing(redactor: (String) -> String): ToolResult = when (this) {
    is ToolResult.Success ->
        if (plaintextApproved) this else copy(data = data.redactUsing(redactor))
    is ToolResult.Partial -> copy(data = data.redactUsing(redactor), message = redactor(message))
    is ToolResult.Error -> copy(message = redactor(message))
}

private fun JsonElement.redactUsing(redactor: (String) -> String): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { it.value.redactUsing(redactor) })
    is JsonArray -> JsonArray(map { it.redactUsing(redactor) })
    is JsonPrimitive -> if (isString) JsonPrimitive(redactor(content)) else this
}
