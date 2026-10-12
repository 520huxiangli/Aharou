package com.aharou.feature.agent.domain.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * 把各家 provider 累积/回传的工具入参 JSON 文本解析为 [JsonObject]。
 *
 * 空串、纯空白或非法 JSON 一律回退为空对象（对齐 tool-call 参数缺失时的既有行为）。
 * Anthropic / Gemini / OpenAI 三家 adapter 原先各有一份等价实现，这里收敛到单处。
 */
internal fun parseToolArguments(raw: String): JsonObject {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return JsonObject(emptyMap())
    return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
}
