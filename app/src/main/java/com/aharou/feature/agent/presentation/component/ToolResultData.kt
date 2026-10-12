package com.aharou.feature.agent.presentation.component

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 从工具结果文本里解出 `data` 对象：先剥掉状态前缀，要求外层 `status` 为 `success`/`partial`，
 * 再取 `data`——它既可能是内嵌对象，也可能是再包一层的 JSON 字符串。
 *
 * WebSearch 与 Dependency 两张结果卡片原先各有一份等价实现，这里收敛到单处。
 * 注意：与 ToolMessageComponents 的 `parseToolTransport` 口径不同（后者只要求有 `status` 字段
 * 且不做字符串解包），两者不可互换。
 */
internal fun extractToolResultData(content: String): JsonObject? {
    val s = content.withoutToolStatusPrefix()
    val outer = Json.parseToJsonElement(s).jsonObject
    if (outer["status"]?.jsonPrimitive?.contentOrNull !in setOf("success", "partial")) return null
    return when (val data = outer["data"]) {
        is JsonObject -> data
        is JsonPrimitive -> data.contentOrNull
            ?.takeIf(String::isNotBlank)
            ?.let { Json.parseToJsonElement(it).jsonObject }
        else -> null
    }
}
