package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 折叠时保留原样的“最近工具结果”条数：更早的才换成引用。 */
private const val KEEP_RECENT_TOOL_RESULTS = 8

/**
 * 把较早且已完整落盘的工具结果换成一句引用，减少长会话里反复重发的体积。
 *
 * 只动带 `output_path` 的条目（[com.aharou.feature.agent.domain.tool.ToolOutputStore] 超长时落盘并打标），
 * 模型需要内容时能自己 readFile 取回来，语义不丢；没落盘（本来就不长）的保持原样。
 * 结果里已无 `output_path` 的条目不会被重复折叠，所以每轮调用是幂等的。
 */
fun foldStoredToolResults(messages: List<AgentMessage>): List<AgentMessage> {
    val toolIndexes = messages.indices.filter { messages[it] is AgentMessage.ToolResultMessage }
    if (toolIndexes.size <= KEEP_RECENT_TOOL_RESULTS) return messages
    val foldable = toolIndexes.dropLast(KEEP_RECENT_TOOL_RESULTS).toHashSet()
    var changed = false
    val folded = messages.mapIndexed { index, message ->
        if (index !in foldable) return@mapIndexed message
        val result = message as? AgentMessage.ToolResultMessage ?: return@mapIndexed message
        val reference = foldedReference(result) ?: return@mapIndexed message
        changed = true
        result.copy(result = reference, modelResult = reference)
    }
    return if (changed) folded else messages
}

/** 结果里带“已落盘”标记时给出折叠后的引用文本；否则 null（不动）。 */
private fun foldedReference(message: AgentMessage.ToolResultMessage): String? {
    val raw = message.result
    if (raw.isBlank() || !raw.contains("output_path")) return null
    val root = runCatching { projectionJson.parseToJsonElement(raw) }.getOrNull() ?: return null
    val path = findString(root, "output_path") ?: return null
    val total = findString(root, "output_total_chars")?.toLongOrNull()
    return buildString {
        append("[早期工具结果已折叠：")
        append(message.toolName)
        if (total != null) append(" 共 $total 字符")
        append("，完整内容在 $path，需要时用 readFile 读取]")
    }
}

/** 递归找第一个同名字符串字段（落盘标记可能落在 data 里，也可能在顶层）。 */
private fun findString(element: JsonElement, key: String): String? = when (element) {
    is JsonObject -> {
        (element[key] as? JsonPrimitive)?.contentOrNull
            ?: element.values.firstNotNullOfOrNull { findString(it, key) }
    }
    is JsonArray -> element.firstNotNullOfOrNull { findString(it, key) }
    else -> null
}

private val projectionJson = Json { ignoreUnknownKeys = true }

/**
 * 文件类工具喂给模型的精简结果文本，对齐 opencode 的 edit / write 语义：
 * - editFile：一句话确认 + 替换数 + 增删行数 + diff 预览（截断）；
 * - writeFile：一句话确认 + 行数，不回显内容。
 *
 * 只投影成功结果，其它工具 / 失败返回 null，由调用方回退用完整 result。
 * 注意：此文本仅喂模型，UI 与持久化仍走 result 的完整 diff。
 */
fun modelToolResultText(toolName: String, transportJson: String): String? {
    val raw = transportJson.trim()
    if (raw.isEmpty()) return null
    val obj = runCatching { projectionJson.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return null
    if (obj["status"]?.jsonPrimitive?.contentOrNull != "success") return null
    val data = obj["data"] as? JsonObject ?: return null
    return when (toolName) {
        "editFile" -> editProjection(data)
        "writeFile" -> writeProjection(data)
        else -> null
    }
}

private fun editProjection(data: JsonObject): String? {
    val path = data["path"]?.jsonPrimitive?.contentOrNull ?: return null
    val replacements = data["replacements"]?.jsonPrimitive?.intOrNull ?: 0
    val added = data["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = data["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val lines = buildList {
        add("Edited file successfully: $path")
        add("Replacements: $replacements")
        add("Changed lines: +$added -$removed")
        diffPreview(data)?.let { add("```diff"); addAll(it); add("```") }
    }
    return if (lines.isEmpty()) null else lines.joinToString("\n")
}

private fun writeProjection(data: JsonObject): String? {
    val path = data["path"]?.jsonPrimitive?.contentOrNull ?: return null
    val created = data["created"]?.jsonPrimitive?.contentOrNull == "true"
    val added = data["added_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val removed = data["removed_lines"]?.jsonPrimitive?.intOrNull ?: 0
    val total = data["lines_written"]?.jsonPrimitive?.intOrNull ?: data["total_lines"]?.jsonPrimitive?.intOrNull
    val verb = if (created) "Created" else "Wrote"
    val lines = buildList {
        add("$verb file successfully: $path (lines: ${total ?: "?"}, +$added -$removed)")
    }
    return lines.joinToString("\n")
}

/** 从 hunks 里取 diff 的前几行做预览，每行超长截断。hunks 为空时返回 null。 */
private fun diffPreview(data: JsonObject): List<String>? {
    val hunks = data["hunks"]?.jsonArray ?: return null
    val all = buildList {
        hunks.forEach { el ->
            val h = el.jsonObject
            (h["diff"]?.jsonPrimitive?.contentOrNull)?.let { this += it }
        }
    }
    if (all.isEmpty()) return null
    val lines = all
        .flatMap { it.split("\n") }
        .map { if (it.length > 240) it.take(240) + "..." else it }
    return lines.take(6)
}