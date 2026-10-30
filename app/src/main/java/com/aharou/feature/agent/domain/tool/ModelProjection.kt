package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
 * 交给 JSON 解析器之前的单值上限（字符数）。
 *
 * 正常工具结果落库前已被 ToolOutputStore 截到 40k 字符，正文 / 入参字段另有 150KB 字节上限，
 * 40 万字符足够覆盖所有正常值；超过它的只可能是历史遗留或异常膨胀的整串。
 * 为什么必须在解析前截：即便换成流式 JsonReader，解析这一层也必然把字符串整段造出来
 * （`nextString()` / `decodeFromString` 都要一份连续字符串），几百 KB 的文本会连带撑出
 * 数倍的 JsonElement 树，堆上同时存在好几份。
 */
const val MAX_PARSED_JSON_CHARS = 400_000

/**
 * 把待解析的 JSON 文本限长到 [maxChars]，超限时截断并追加可读标记。
 *
 * 截断后的文本不再是合法 JSON——所有调用方都按 `runCatching{}.getOrNull()` 消费，
 * 结果只是这一条降级（工具调用 / 附件解析为空、投影退回原文），不会抛异常。
 * 需要从超长结果里取短字段的场景不要走解析，见 [findStoredField]。
 */
fun boundedJsonForParse(raw: String, maxChars: Int = MAX_PARSED_JSON_CHARS): String {
    if (raw.length <= maxChars) return raw
    var end = maxChars.coerceAtLeast(0)
    // 别把代理对切一半：半个字符会变成替换符，白占一个位置还破坏可读性。
    if (end > 0 && end < raw.length && raw[end - 1].isHighSurrogate() && raw[end].isLowSurrogate()) end--
    return raw.substring(0, end) + "\n[truncated ${raw.length - end} chars]"
}

/**
 * 把较早且已完整落盘的工具结果换成一句引用，减少长会话里反复重发的体积。
 *
 * 只动带 `output_path` 的条目（[com.aharou.feature.agent.domain.tool.ToolOutputStore] 超长时落盘并打标），
 * 模型需要内容时能自己 readFile 取回来，语义不丢；没落盘（本来就不长）的保持原样。
 * 结果里已无 `output_path` 的条目不会被重复折叠，所以每轮调用是幂等的。
 */
fun foldStoredToolResults(messages: List<AgentMessage>, keepRecent: Int = KEEP_RECENT_TOOL_RESULTS): List<AgentMessage> {
    val toolIndexes = messages.indices.filter { messages[it] is AgentMessage.ToolResultMessage }
    if (toolIndexes.size <= keepRecent) return messages
    val foldable = toolIndexes.dropLast(keepRecent).toHashSet()
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
    if (raw.isBlank() || !raw.contains("\"output_path\"")) return null
    val path = findStoredField(raw, OUTPUT_PATH_FIELD) ?: return null
    val total = findStoredField(raw, OUTPUT_TOTAL_CHARS_FIELD)?.toLongOrNull()
    return buildString {
        append("[早期工具结果已折叠：")
        append(message.toolName)
        if (total != null) append(" 共 $total 字符")
        append("，完整内容在 $path，需要时用 readFile 读取]")
    }
}

private val OUTPUT_PATH_FIELD = Regex("\"output_path\"\\s*:\\s*\"([^\"]*)\"")
private val OUTPUT_TOTAL_CHARS_FIELD = Regex("\"output_total_chars\"\\s*:\\s*\"?([0-9]+)\"?")

/**
 * 从结果文本里线性扫出某个短字段的值，不建 JsonElement 树。
 *
 * 落盘标记（output_path / output_total_chars）总在预览正文之后，而预览正文恰恰是结果里最长的一段：
 * 为取两个短字段把整串解析成树，等于白造一棵几百 KB 的对象树，超长结果尤其亏。
 * 这两个值都是 ToolOutputStore 直接写入的纯字符串 / 整数，不含需要反转义的转义序列。
 */
private fun findStoredField(raw: String, pattern: Regex): String? =
    pattern.find(raw)?.groupValues?.get(1)

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
    // 解析前先限长：超长结果直接降级成「不投影」，由调用方回退用原文，比先造树再说安全。
    val bounded = boundedJsonForParse(raw)
    val obj = runCatching { projectionJson.parseToJsonElement(bounded) as? JsonObject }.getOrNull() ?: return null
    if (obj["status"]?.jsonPrimitive?.contentOrNull != "success") return null
    val data = obj["data"] as? JsonObject ?: return null
    val written = (when (toolName) {
        "editFile" -> editProjection(data)
        "writeFile" -> writeProjection(data)
        else -> null
    }) ?: return null
    // 搭车通知（后台任务/子代理完成等）只存在于工具结果顶层，且结果落库后立刻 ack，
    // 投影把它丢掉就等于永久丢失，所以这里必须把整段 notifications 原样补回。
    val notifications = (obj["notifications"] as? JsonArray)?.takeIf { it.isNotEmpty() } ?: return written
    return written + "\n\n" + NOTIFICATION_HINT + "\n" + notifications.toString()
}

private const val NOTIFICATION_HINT =
    "以下是随本次工具结果搭车送达的系统通知（后台任务完成、子代理完成等，原样 JSON）："

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