package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
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

    fun process(toolName: String, callId: String, result: ToolResult): ToolResult {
        if (textTransportString(result).length <= MAX_INLINE_CHARS) return result

        val fullText = result.toTransportString()
        val stored = writeFullOutput(toolName, callId, fullText).toStoredOutput(fullText.length)
        val structured = fitResult { chars -> previewResult(result, stored, chars, structured = true) }
        return structured ?: fitResult { chars -> previewResult(result, stored, chars, structured = false) }
            ?: metadataFallback(result, stored)
    }

    fun boundText(toolName: String, callId: String, text: String): StoredToolOutput {
        if (ToolResult.Success(JsonPrimitive(text)).toTransportString().length <= MAX_INLINE_CHARS) {
            return StoredToolOutput(text, false, text.length.toLong())
        }
        val stored = writeFullOutput(toolName, callId, text).toStoredOutput(text.length)
        val result = requireNotNull(fitResult { chars ->
            ToolResult.Success(stored.copy(preview = buildPreview(text, chars)).toJsonObject("output"))
        }) { "Tool output storage metadata exceeds the text transport budget" } as ToolResult.Success
        val preview = (result.data as JsonObject).getValue("output") as JsonPrimitive
        return stored.copy(preview = preview.content)
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
        is ToolResult.Partial -> result.copy(
            data = previewData(result.data, stored, chars, structured),
            message = buildPreview(result.message, chars)
        )
        is ToolResult.Error -> result.copy(
            message = buildPreview(result.message, chars) + "\n" + stored.toJsonObject()
        )
    }

    private fun previewData(
        data: JsonElement,
        stored: StoredToolOutput,
        chars: Int,
        structured: Boolean
    ): JsonObject {
        if (!structured) {
            return stored.copy(preview = buildPreview(data.toString(), chars)).toJsonObject("content")
        }
        if (data is JsonPrimitive && data.isString) {
            return stored.copy(preview = buildPreview(data.content, chars)).toJsonObject("output")
        }
        val preview = previewElement(data, chars)
        val fields = if (preview is JsonObject) preview.toMutableMap() else mutableMapOf("content" to preview)
        addMetadata(fields, stored)
        return JsonObject(fields)
    }

    private fun previewElement(element: JsonElement, chars: Int): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { previewElement(it.value, chars) })
        is JsonArray -> JsonArray(element.map { previewElement(it, chars) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(buildPreview(element.content, chars)) else element
    }

    private fun addMetadata(target: MutableMap<String, JsonElement>, stored: StoredToolOutput) {
        target["output_truncated"] = JsonPrimitive(stored.truncated)
        target["output_total_chars"] = JsonPrimitive(stored.totalChars)
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
        return buildString {
            append(text, 0, headEnd)
            append("\n\n...[output truncated; omitted ")
            append(tailStart - headEnd)
            append(" characters]...\n\n")
            append(text, tailStart, text.length)
        }
    }

    private fun StoredPathResult.toStoredOutput(totalChars: Int) = StoredToolOutput(
        preview = "",
        truncated = true,
        totalChars = totalChars.toLong(),
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
}
