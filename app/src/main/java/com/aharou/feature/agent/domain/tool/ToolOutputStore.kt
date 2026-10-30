package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class StoredToolOutput(
    val preview: String,
    val truncated: Boolean,
    val totalChars: Long,
    /**
     * 完整输出的行数，口径与 `readFile` 一致（`\n` / `\r` / `\r\n` 各算一次换行，末尾换行不额外
     * 多出一行）。模型据此配合预览里的行号直接决定 `start_line`，不必靠猜。
     */
    val totalLines: Int = 0,
    val outputPath: String? = null,
    val storageError: String? = null
)

@Singleton
class ToolOutputStore @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val fileAccess: FileAccessProvider,
    private val pathHomeResolver: PathHomeResolver
) {
    private companion object {
        const val TAG = "ToolOutputStore"
        const val OUTPUT_DIR = "tool-output"
        const val MAX_INLINE_CHARS = 40_000
        const val MAX_STORAGE_ERROR_CHARS = 512
        val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
    }

    /**
     * 设备本地存档目录（宿主路径），仅供本机存储占用统计与清理。
     * 写入落在当前执行环境（本地即此目录；远程经 SFTP 落到服务器 home 下的 `.aharou/tool-output`），
     * 故远程模式下本目录为空、实际存档在远端。
     */
    val outputDir: File get() = File(containerInstaller.aharouDir, OUTPUT_DIR)

    /**
     * 最近一次行数统计，按字符串实例命中。预览要在 [fitResult] 的二分搜索里反复构建，
     * 同一段正文会被量十几遍；缓存把全量遍历压到每次调用一次。写成不可变的 Pair 一次性替换，
     * 并发调用时读到的字符数/行数始终成对，不会出现「新文本配旧行数」。
     */
    @Volatile
    private var measuredCache: Pair<String, TextMeasure>? = null

    fun process(toolName: String, callId: String, result: ToolResult): ToolResult {
        if (textTransportString(result).length <= MAX_INLINE_CHARS) return result

        try {
            val fullText = result.toArchiveText()
            val stored = writeFullOutput(toolName, callId, fullText).toStoredOutput(measureText(fullText))
            val structured = fitResult { chars -> previewResult(result, stored, chars, structured = true) }
            return structured ?: fitResult { chars -> previewResult(result, stored, chars, structured = false) }
                ?: metadataFallback(result, stored)
        } finally {
            // 缓存只为一次调用内部复用，别长期攥着几 MB 的正文。
            measuredCache = null
        }
    }

    fun boundText(toolName: String, callId: String, text: String): StoredToolOutput {
        try {
            val measured = measureText(text)
            if (ToolResult.Success(JsonPrimitive(text)).toTransportString().length <= MAX_INLINE_CHARS) {
                return StoredToolOutput(text, false, measured.chars, measured.lines)
            }
            val stored = writeFullOutput(toolName, callId, text).toStoredOutput(measured)
            val result = requireNotNull(fitResult { chars ->
                ToolResult.Success(stored.copy(preview = buildPreview(text, chars)).toJsonObject("output"))
            }) { "Tool output storage metadata exceeds the text transport budget" } as ToolResult.Success
            val preview = (result.data as JsonObject).getValue("output") as JsonPrimitive
            return stored.copy(preview = preview.content)
        } finally {
            measuredCache = null
        }
    }

    /** 存档 JSON：字段顺序与传输串一致，只是带缩进、换行不再被转义。 */
    private val archiveJson = Json {
        classDiscriminator = "status"
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * 存档用的可读文本：纯文本结果直接存正文（换行保留，`readFile` 能按行定位），
     * 结构化结果存带缩进的 JSON——存传输串会把换行全转义成 `\n`，整份存档只剩一行，
     * 截断标记里给出的行号就没法用来续读。
     */
    private fun ToolResult.toArchiveText(): String {
        // 跨模块时 public val 无法 smart cast，先取到局部变量
        val primitive = (this as? ToolResult.Success)?.data as? JsonPrimitive
        return if (primitive != null && primitive.isString) {
            primitive.content
        } else {
            archiveJson.encodeToString(this)
        }
    }

    private fun metadataFallback(result: ToolResult, stored: StoredToolOutput): ToolResult {
        val metadata = stored.toJsonObject().toMutableMap().apply {
            put("output_metadata_truncated", JsonPrimitive(true))
        }
        return when (result) {
            is ToolResult.Success -> ToolResult.Success(JsonObject(metadata))
            is ToolResult.Partial -> ToolResult.Partial(JsonObject(metadata), "Original metadata exceeds the inline budget")
            is ToolResult.Error -> ToolResult.Error(JsonObject(metadata).toString(), "OUTPUT_METADATA_TOO_LARGE")
        }
    }

    private fun textTransportString(result: ToolResult): String {
        val textResult = if (result is ToolResult.Success && result.images.isNotEmpty()) {
            result.copy(images = result.images.map { it.copy(base64Data = "") })
        } else {
            result
        }
        return textResult.toTransportString()
    }

    private fun fitResult(candidate: (Int) -> ToolResult): ToolResult? {
        var best = candidate(0)
        if (textTransportString(best).length > MAX_INLINE_CHARS) return null
        var low = 1
        var high = MAX_INLINE_CHARS
        while (low <= high) {
            val chars = low + (high - low) / 2
            val result = candidate(chars)
            if (textTransportString(result).length <= MAX_INLINE_CHARS) {
                best = result
                low = chars + 1
            } else {
                high = chars - 1
            }
        }
        return best
    }

    private fun previewResult(
        result: ToolResult,
        stored: StoredToolOutput,
        chars: Int,
        structured: Boolean
    ): ToolResult = when (result) {
        is ToolResult.Success -> result.copy(data = previewData(result.data, stored, chars, structured))
        is ToolResult.Partial -> {
            val previewedData = previewData(result.data, stored, chars, structured)
            result.copy(
                data = withMessageLines(previewedData, measureText(result.message).lines),
                message = buildPreview(result.message, chars)
            )
        }
        is ToolResult.Error -> result.copy(
            // 错误结果里正文就是 message，行数跟着 message 走；JSON 传输串本身是单行，拿它当行数没意义。
            message = buildPreview(result.message, chars) + "\n" +
                stored.copy(totalLines = measureText(result.message).lines).toJsonObject()
        )
    }

    /**
     * Partial 的正文可能主要落在 message 里（data 只有零星字段），取两者中行数多的那个，
     * 别让 `output_total_lines` 报一个比正文小得多、看着像坏了的数。
     */
    private fun withMessageLines(data: JsonObject, messageLines: Int): JsonObject {
        val current = (data["output_total_lines"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        if (messageLines <= current) return data
        return JsonObject(data + ("output_total_lines" to JsonPrimitive(messageLines)))
    }

    private fun previewData(
        data: JsonElement,
        stored: StoredToolOutput,
        chars: Int,
        structured: Boolean
    ): JsonObject {
        if (!structured) {
            val text = data.toString()
            return stored.copy(preview = buildPreview(text, chars), totalLines = measureText(text).lines)
                .toJsonObject("content")
        }
        if (data is JsonPrimitive && data.isString) {
            return stored.copy(
                preview = buildPreview(data.content, chars),
                totalLines = measureText(data.content).lines
            ).toJsonObject("output")
        }
        val mostLines = MostLines()
        val preview = previewElement(data, chars, mostLines)
        val fields = if (preview is JsonObject) preview.toMutableMap() else mutableMapOf("content" to preview)
        addMetadata(fields, stored.copy(totalLines = mostLines.lines.takeIf { it > 0 } ?: stored.totalLines))
        return JsonObject(fields)
    }

    /**
     * 递归截断各字段的字符串。多字段结果没有唯一的「正文」，顺手取行数最多的那个字段作为
     * `output_total_lines`——各字段自己的行数已经写在各自的截断标记里。
     */
    private fun previewElement(element: JsonElement, chars: Int, mostLines: MostLines): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { previewElement(it.value, chars, mostLines) })
        is JsonArray -> JsonArray(element.map { previewElement(it, chars, mostLines) })
        is JsonPrimitive -> if (element.isString) {
            mostLines.lines = maxOf(mostLines.lines, measureText(element.content).lines)
            JsonPrimitive(buildPreview(element.content, chars))
        } else element
    }

    private fun addMetadata(target: MutableMap<String, JsonElement>, stored: StoredToolOutput) {
        target["output_truncated"] = JsonPrimitive(stored.truncated)
        target["output_total_chars"] = JsonPrimitive(stored.totalChars)
        target["output_total_lines"] = JsonPrimitive(stored.totalLines)
        target.remove("output_path")
        target.remove("output_storage_error")
        stored.outputPath?.let { target["output_path"] = JsonPrimitive(it) }
        stored.storageError?.let { target["output_storage_error"] = JsonPrimitive(it) }
    }

    private fun StoredToolOutput.toJsonObject(primaryField: String? = null): JsonObject {
        val data = mutableMapOf<String, JsonElement>()
        primaryField?.let { data[it] = JsonPrimitive(preview) }
        addMetadata(data, this)
        return JsonObject(data)
    }

    /**
     * 字符数与行数一次遍历算完：[chars] 直接取 `String.length`（O(1)，不必再扫一遍），
     * 循环里只累计换行。行数口径与 `readFile` 的分行一致：`\n`、`\r`、`\r\n` 都是一次换行，
     * 文末的换行不额外算一行（`"a\nb\n"` 是 2 行，`""` 是 0 行）。
     */
    private fun measureText(text: String): TextMeasure {
        val cached = measuredCache
        if (cached != null && cached.first === text) return cached.second
        var lineBreaks = 0
        var trailingLine = false
        var index = 0
        val length = text.length
        while (index < length) {
            when (text[index]) {
                '\n' -> {
                    lineBreaks++
                    trailingLine = false
                    index++
                }
                '\r' -> {
                    lineBreaks++
                    trailingLine = false
                    index += if (index + 1 < length && text[index + 1] == '\n') 2 else 1
                }
                else -> {
                    trailingLine = true
                    index++
                }
            }
        }
        val measured = TextMeasure(length.toLong(), lineBreaks, trailingLine)
        measuredCache = text to measured
        return measured
    }

    /** [offset] 处字符所在行号（1 起）；[offset] 允许等于文本长度。 */
    private fun lineIndexAt(text: String, offset: Int): Int {
        val limit = offset.coerceIn(0, text.length)
        var line = 1
        var index = 0
        while (index < limit) {
            val c = text[index]
            if (c == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
                // CRLF 正好被切在 offset 上时不算换行：结尾的 '\n' 仍属于当前行。
                if (index + 1 >= limit) break
                line++
                index += 2
            } else if (c == '\r' || c == '\n') {
                line++
                index++
            } else {
                index++
            }
        }
        return line
    }

    /**
     * [lastOmitted] 处字符所在行号。用「全文换行数 - 从该字符起往后的换行数」倒推，
     * 这样尾部那半段只扫一遍短区间，不必为了这一个行号再把前半段全量走一遍。
     */
    private fun lineIndexBefore(text: String, lastOmitted: Int, measured: TextMeasure): Int {
        if (lastOmitted < 0) return 1
        var breaksInTail = 0
        var index = lastOmitted
        while (index < text.length) {
            val c = text[index]
            if (c == '\r') {
                breaksInTail++
                index += if (index + 1 < text.length && text[index + 1] == '\n') 2 else 1
            } else {
                // 前面紧邻 '\r' 的 '\n' 也算一次换行，否则上一个字符串切开的 CRLF 会漏数。
                if (c == '\n') breaksInTail++
                index++
            }
        }
        return 1 + (measured.lineBreaks - breaksInTail)
    }

    private fun lineRangeLabel(startLine: Int, endLine: Int, totalLines: Int): String {
        val start = startLine.coerceAtLeast(1)
        val end = endLine.coerceIn(start, totalLines.coerceAtLeast(start))
        val total = totalLines.coerceAtLeast(end)
        return if (start == end) "line $start of $total" else "lines $start-$end of $total"
    }

    private fun buildPreview(text: String, chars: Int): String {
        if (text.length <= chars) return text
        var headEnd = (chars + 1) / 2
        var tailStart = text.length - chars / 2
        if (headEnd > 0 && headEnd < text.length &&
            text[headEnd - 1].isHighSurrogate() && text[headEnd].isLowSurrogate()
        ) headEnd--
        if (tailStart > 0 && tailStart < text.length &&
            text[tailStart - 1].isHighSurrogate() && text[tailStart].isLowSurrogate()
        ) tailStart++
        val measured = measureText(text)
        val omittedStartLine = lineIndexAt(text, headEnd)
        val omittedEndLine = lineIndexBefore(text, tailStart - 1, measured)
        return buildString {
            append(text, 0, headEnd)
            append("\n\n...[output truncated; omitted ")
            // 行号给人/模型定位（缺了哪几行），字符数给下游按体积判断，两者都留着。
            append(lineRangeLabel(omittedStartLine, omittedEndLine, measured.lines))
            append(", ")
            append(tailStart - headEnd)
            append(" characters]...\n\n")
            append(text, tailStart, text.length)
        }
    }

    private fun StoredPathResult.toStoredOutput(measured: TextMeasure) = StoredToolOutput(
        preview = "",
        truncated = true,
        totalChars = measured.chars,
        totalLines = measured.lines,
        outputPath = outputPath,
        storageError = storageError
    )

    private fun writeFullOutput(toolName: String, callId: String, text: String): StoredPathResult {
        return try {
            val dir = "${pathHomeResolver.aharouRoot()}/$OUTPUT_DIR"
            fileAccess.mkdirs(dir)
            val path = uniqueOutputPath(dir, toolName, callId)
            fileAccess.writeFile(path, text, overwrite = false)
            runCatching { FileLogger.i(TAG, "工具输出已保存: $path (${text.length} chars)") }
            StoredPathResult(outputPath = path)
        } catch (e: Exception) {
            runCatching { FileLogger.w(TAG, "保存工具输出失败: ${e.message}", e) }
            StoredPathResult(storageError = buildPreview(
                "Full output could not be saved: ${e.message ?: e.javaClass.simpleName}. Check storage access and retry.",
                MAX_STORAGE_ERROR_CHARS
            ))
        }
    }

    private fun uniqueOutputPath(dir: String, toolName: String, callId: String): String {
        val timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT)
        val baseName = buildString {
            append(timestamp)
            append('-')
            append(sanitize(toolName).take(80).ifBlank { "tool" })
            val id = sanitize(callId).take(12)
            if (id.isNotBlank()) {
                append('-')
                append(id)
            }
        }

        val dirSlash = dir.trimEnd('/')
        var candidate = "$dirSlash/$baseName.log"
        var index = 1
        while (fileAccess.exists(candidate)) {
            candidate = "$dirSlash/$baseName-$index.log"
            index++
        }
        return candidate
    }

    private fun sanitize(value: String): String {
        return value.map { ch ->
            if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-'
        }.joinToString("").trim('-')
    }

    private data class StoredPathResult(
        val outputPath: String? = null,
        val storageError: String? = null
    )

    private class TextMeasure(val chars: Long, val lineBreaks: Int, val trailingLine: Boolean) {
        /** 行数：每次换行开一行，文末没有换行的残余内容再算一行。 */
        val lines: Int get() = lineBreaks + if (trailingLine) 1 else 0
    }

    private class MostLines {
        var lines = 0
    }
}
