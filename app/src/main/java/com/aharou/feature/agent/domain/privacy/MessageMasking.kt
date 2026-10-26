package com.aharou.feature.agent.domain.privacy

import com.aharou.core.privacy.OutboundMasker
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 出站脱敏在「消息层」的落地：把将要发出去的历史消息里的文本换成占位符。
 *
 * 只覆盖会进入模型上下文的文本（用户消息、工具结果、助手历史正文与思考、历史工具参数）。
 * 图片/附件不动——脱敏规则是文本正则，碰到 base64 只会白跑一遍。
 */
fun List<AgentMessage>.maskedForOutbound(masker: OutboundMasker): List<AgentMessage> = map { msg ->
    when (msg) {
        is AgentMessage.UserMessage -> msg.copy(content = masker.mask(msg.content))
        is AgentMessage.ToolResultMessage -> msg.copy(
            result = masker.mask(msg.result),
            modelResult = msg.modelResult?.let(masker::mask)
        )
        is AgentMessage.AssistantMessage -> msg.copy(
            content = masker.mask(msg.content),
            reasoning = masker.mask(msg.reasoning),
            toolCalls = msg.toolCalls.map { it.copy(arguments = transformArguments(it.arguments, masker::mask)) }
        )
    }
}

/**
 * 回填：把模型回复里的占位符换回真值。
 *
 * 必须作用在**完整文本**上（流式收流结束后的 Final），不能逐 chunk 处理——
 * 占位符可能被切在两个 chunk 之间，逐片替换永远匹配不上。
 */
fun AIResponse.restoredFromOutbound(masker: OutboundMasker): AIResponse = copy(
    content = masker.restore(content),
    toolCalls = toolCalls.map { it.copy(arguments = transformArguments(it.arguments, masker::restore)) }
)

/** 工具参数的字符串值递归替换（对象/数组都进去，非字符串原样保留）。 */
private fun transformArguments(args: Map<String, JsonElement>, f: (String) -> String): Map<String, JsonElement> =
    args.mapValues { (_, value) -> transformElement(value, f) }

private fun transformElement(element: JsonElement, f: (String) -> String): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.mapValues { (_, v) -> transformElement(v, f) })
    is JsonArray -> JsonArray(element.map { transformElement(it, f) })
    is JsonPrimitive -> if (element.isString) JsonPrimitive(f(element.content)) else element
}
