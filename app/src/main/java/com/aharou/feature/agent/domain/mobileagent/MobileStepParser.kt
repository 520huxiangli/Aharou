package com.aharou.feature.agent.domain.mobileagent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** 模型一步的回复：想了什么、说要做什么、调了什么函数、参数是什么。 */
data class MobileStep(
    val thought: String,
    val action: String,
    val name: String,
    val arguments: Map<String, kotlinx.serialization.json.JsonElement>,
) {
    /** 参数里的 action（小写）。 */
    val kind: String
        get() = (arguments["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase().orEmpty()
}

/**
 * 把模型回复解析成一步。容错：可以没有 Thought/Action 标签、JSON 可以带代码围栏或裸写、
 * `<think>…</think>` 噪声直接丢掉、缺参数按无效处理。参考 nanoMuse 的 parse_tagged_text / parse_step。
 */
object MobileStepParser {

    private val THINK_RE = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    fun parse(text: String?, tool: String = MobilePrompt.TOOL_NAME): MobileStep? {
        if (text.isNullOrBlank()) return null
        val cleaned = THINK_RE.replace(text, "").trim()
        if (cleaned.isEmpty()) return null
        val (head, payload) = splitPayload(cleaned) ?: return null
        val obj = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject ?: return null
        val (name, arguments) = extractCall(obj, tool) ?: return null
        // 归到本函数名下却没有 action：不是一步，让调用方重问。
        if (name == tool && (arguments["action"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()) return null
        val (thinking, action) = splitHead(head)
        val conclusion = action.trim().trim('"').ifBlank { describe(arguments) }
        return MobileStep(
            thought = thinking.take(400).trim(),
            action = conclusion.take(200),
            name = name,
            arguments = arguments,
        )
    }

    /** 找出回复里的 JSON 载荷与它前面的 Thought/Action 文本；找不到载荷返回 null。 */
    private fun splitPayload(text: String): Pair<String, String>? {
        val tagStart = text.indexOf("<tool_call>")
        if (tagStart >= 0) {
            val end = text.indexOf("</tool_call>", tagStart)
            val inner = if (end > tagStart) text.substring(tagStart + 11, end) else text.substring(tagStart + 11)
            extractJsonObject(inner)?.let { return text.substring(0, tagStart) to it }
        }
        val fence = Regex("```(?:json)?\\s*", RegexOption.IGNORE_CASE).find(text)
        if (fence != null) {
            val after = text.substring(fence.range.last + 1)
            extractJsonObject(after)?.let { return text.substring(0, fence.range.first) to it }
        }
        extractJsonObject(text)?.let { return text.substring(0, text.indexOf('{')) to it }
        return null
    }

    /** 从一段文本里取出第一个完整的 JSON 对象（按括号配对，跳过字符串内的括号）。 */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }

    /**
     * 取出函数名与参数。支持 `{"name":…, "arguments":{…}}` 包装，也支持裸的参数对象
     * （此时归到 [tool] 名下）。都取不到返回 null。
     */
    private fun extractCall(obj: JsonObject, tool: String): Pair<String, Map<String, kotlinx.serialization.json.JsonElement>>? {
        val args = obj["arguments"]
        if (args is JsonObject) {
            val name = (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() } ?: tool
            return name to args
        }
        if (obj["action"] != null) return tool to obj
        return null
    }

    /** 拆出 Thought 与 Action 两句；缺标签时退化为「Action: 前面都算 Thought」。 */
    private fun splitHead(head: String): Pair<String, String> {
        val tIdx = head.indexOf("Thought:")
        val rest = if (tIdx >= 0) head.substring(tIdx + "Thought:".length) else head
        val aIdx = rest.indexOf("Action:")
        return if (aIdx >= 0) rest.substring(0, aIdx) to rest.substring(aIdx + "Action:".length)
        else rest to ""
    }

    /** 缺 Action 句时，用参数拼一句可读的描述。 */
    private fun describe(args: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val kind = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        fun int(key: String) = (args[key] as? JsonPrimitive)?.contentOrNull
        return when (kind) {
            "click", "long_press" -> "$kind (${int("x")}, ${int("y")})"
            "swipe" -> "swipe (${int("x")},${int("y")}) → (${int("x2")},${int("y2")})"
            "type" -> "type ${(args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(60)}"
            "key" -> "press ${int("key")}"
            "open" -> "open ${(args["text"] as? JsonPrimitive)?.contentOrNull.orEmpty()}"
            "wait" -> "wait ${int("time")}s"
            else -> kind.ifEmpty { "?" }
        }
    }
}
