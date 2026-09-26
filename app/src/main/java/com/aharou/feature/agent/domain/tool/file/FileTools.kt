package com.aharou.feature.agent.domain.tool.file

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.core.util.FileLogger
import com.aharou.core.util.LineDiff
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

private const val TAG = "FileTools"

class ReadFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "readFile"
    override val description = "读取文件内容（工作区文件或容器绝对路径的系统文件）。单次读取有行数与字节上限，超出可用 start_line 分段续读。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "start_line" to ToolParameter("start_line", ParameterType.INTEGER, "开始行号（从 1 计）。", required = false),
        "end_line" to ToolParameter("end_line", ParameterType.INTEGER, "结束行号；与 start_line 的跨度最多 2000 行，超出按 2000 行截断。", required = false)
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "read_file 缺少 path 参数")
                return ToolResult.Error("路径参数缺失", "MISSING_PATH")
            }
            FileLogger.d(TAG, "read_file path=$path")

            if (!fileAccess.exists(path)) {
                FileLogger.w(TAG, "read_file 文件不存在: $path")
                return ToolResult.Error("文件不存在: $path", "FILE_NOT_FOUND")
            }

            val startLine = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1) ?: 1
            val requestedEnd = args["end_line"]?.jsonPrimitive?.intOrNull
            // 行窗口上界：显式 end_line 与 start_line+MAX_LINES 取较小值，缺省则按 MAX_LINES 上限。
            val lineCap = startLine + MAX_LINES - 1
            val endCap = if (requestedEnd != null) minOf(requestedEnd, lineCap) else lineCap

            // 逐行流式读取，避免 readText() 整篇进内存导致移动端 OOM；
            // 只保留 [startLine, endCap] 窗口，并在累计字节超过 MAX_BYTES 时提前停止。
            // 走 IO 线程：本地 File 读与远程 exec 流的阻塞读写都不应占着调用方线程。
            val sb = StringBuilder()
            var totalLines = 0
            var emittedLines = 0
            var byteCount = 0
            var truncatedByBytes = false
            var lineNo = 0
            withContext(Dispatchers.IO) {
                fileAccess.readLines(path).forEach { line ->
                    lineNo++
                    totalLines = lineNo
                    if (lineNo < startLine) return@forEach
                    if (lineNo > endCap) {
                        // 已越过窗口，但仍需继续计数以得到准确 total_lines。
                        return@forEach
                    }
                    if (!truncatedByBytes) {
                        val lineBytes = line.toByteArray(Charsets.UTF_8).size + 1
                        if (byteCount + lineBytes > MAX_BYTES && emittedLines > 0) {
                            truncatedByBytes = true
                        } else {
                            if (emittedLines > 0) sb.append('\n')
                            sb.append(line)
                            byteCount += lineBytes
                            emittedLines++
                        }
                    }
                }
            }

            val lastEmittedLine = startLine + emittedLines - 1
            // 用户想要的窗口末行：给了 end_line 取 min(end_line, EOF)，否则到 EOF。
            // 我们只发到了 lastEmittedLine，若它落在窗口末行之前，说明被截断、还有内容可读。
            val wantedEnd = if (requestedEnd != null) minOf(requestedEnd, totalLines) else totalLines
            val truncated = emittedLines > 0 && lastEmittedLine < wantedEnd
            val note = when {
                !truncated -> null
                truncatedByBytes -> "已达 ${MAX_BYTES / 1024}KB 上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
                else -> "已达 $MAX_LINES 行上限被截断；从第 ${lastEmittedLine + 1} 行起用 start_line 继续读取。"
            }

            FileLogger.v(TAG, "read_file 成功 path=$path total=$totalLines emitted=$emittedLines bytes=$byteCount truncated=$truncated")
            val resultMap = mutableMapOf<String, JsonElement>(
                "content" to JsonPrimitive(sb.toString()),
                "total_lines" to JsonPrimitive(totalLines),
                "start_line" to JsonPrimitive(startLine),
                "end_line" to JsonPrimitive(maxOf(lastEmittedLine, startLine - 1)),
                "read_lines" to JsonPrimitive(emittedLines),
                "truncated" to JsonPrimitive(truncated)
            )
            if (note != null) resultMap["note"] = JsonPrimitive(note)
            ToolResult.Success(JsonObject(resultMap))
        } catch (e: Exception) {
            FileLogger.e(TAG, "read_file 异常", e)
            ToolResult.Error(e.message ?: "读取文件失败", "READ_ERROR")
        }
    }

    private companion object {
        /** 单次最多返回的行数，超出截断并提示用 start_line 续读。 */
        const val MAX_LINES = 2000
        /** 单次最多返回的字节数（UTF-8，约 200KB），防止超大行撑爆内存/上下文。 */
        const val MAX_BYTES = 200 * 1024
    }
}

/**
 * 写入整个文件，文件不存在时会自动创建（含父目录）。
 *
 * 通过 overwrite 参数吸收了旧 create_file 的「不覆盖已有文件」语义：
 * overwrite=false 且目标已存在时报错，可用于安全地新建文件。
 */
class WriteFileTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {
    override val name = "writeFile"
    override val description = "写入完整文件内容，文件不存在时自动创建（含父目录）。局部修改请用 editFile。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.WRITE_WORKSPACE)
    override val parameters = mapOf(
        "path" to ToolParameter("path", ParameterType.STRING, "文件路径：~/workspace/... 为项目文件；其它绝对路径（如 /etc/...、/root/...）为容器系统文件。", required = true),
        "content" to ToolParameter("content", ParameterType.STRING, "文件内容", required = true),
        "overwrite" to ToolParameter("overwrite", ParameterType.BOOLEAN, "目标已存在时是否覆盖。默认 true；设为 false 时若文件已存在则报错。", required = false)
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val path = args["path"]?.jsonPrimitive?.contentOrNull ?: "未知文件"
        val content = args["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认写入文件",
            summary = "AI 请求写入 $path",
            details = "字符数：${content.length}\n行数：${content.lines().size}\n允许覆盖：${if (overwrite) "是" else "否"}",
            argsPreview = argsPreview
        )
    }

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        return try {
            val path = args["path"]?.jsonPrimitive?.contentOrNull ?: run {
                FileLogger.w(TAG, "write_file 缺少 path 参数")
                return ToolResult.Error("路径参数缺失", "MISSING_PATH")
            }
            val content = args["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val overwrite = args["overwrite"]?.jsonPrimitive?.booleanOrNull ?: true

            FileLogger.d(TAG, "write_file path=$path (${content.length} 字符, overwrite=$overwrite)")
            val existed = fileAccess.exists(path)
            if (existed && !overwrite) {
                FileLogger.w(TAG, "write_file 文件已存在且 overwrite=false: $path")
                return ToolResult.Error("文件已存在: $path（overwrite=false）", "FILE_EXISTS")
            }

            // 写前留存旧内容，供生成「旧→新」差异（与 edit_file 同构，UI 据此渲染彩色 diff）。
            val oldContent = if (existed) runCatching { fileAccess.readFile(path) }.getOrDefault("") else ""

            fileAccess.writeFile(path, content, overwrite = true)

            // 生成统一差异文本：新建文件按「整体新增」呈现（旧内容视为空，避免一行伪删除）；
            // 覆盖写则计算旧→新的行级增删。LineDiff 为 O(n·m) 内存，超大文件重写时跳过 LCS、
            // 退化为整体新增，防止移动端因构造 DP 表而 OOM。
            val diff = buildWriteDiff(existed, oldContent, content)
            val added = diff.lines().count { it.startsWith("+") }
            val removed = diff.lines().count { it.startsWith("-") }
            val hunksJson = JsonArray(listOf(
                JsonObject(mapOf(
                    "start_line" to JsonPrimitive(1),
                    "added" to JsonPrimitive(added),
                    "removed" to JsonPrimitive(removed),
                    "diff" to JsonPrimitive(diff)
                ))
            ))

            FileLogger.v(TAG, "write_file 成功 path=$path created=${!existed} lines=${content.lines().size} (+$added -$removed)")
            ToolResult.Success(
                JsonObject(mapOf(
                    "path" to JsonPrimitive(fileAccess.toDisplayPath(path)),
                    "created" to JsonPrimitive(!existed),
                    "bytes_written" to JsonPrimitive(content.length),
                    "lines_written" to JsonPrimitive(content.lines().size),
                    "added_lines" to JsonPrimitive(added),
                    "removed_lines" to JsonPrimitive(removed),
                    "hunks" to hunksJson
                ))
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "write_file 异常", e)
            ToolResult.Error(e.message ?: "写入文件失败", "WRITE_ERROR")
        }
    }

    /**
     * 构造 write_file 的统一差异文本（每行以 `+`/`-`/` ` 起头）：
     * - 新建文件：旧内容视为空，整篇按「全部新增」呈现，不跑 LCS；
     * - 覆盖写且规模可控：计算旧→新的行级 LCS 差异；
     * - 覆盖写但任一侧行数超过 [MAX_DIFF_LINES]：跳过 O(n·m) 的 LCS（移动端易 OOM），
     *   退化为整篇「全部新增」，仅展示落盘后的内容。
     */
    private fun buildWriteDiff(existed: Boolean, oldContent: String, newContent: String): String {
        val newLines = newContent.split("\n")
        if (!existed) return newLines.joinToString("\n") { "+$it" }
        val oldLines = oldContent.split("\n")
        if (maxOf(oldLines.size, newLines.size) > MAX_DIFF_LINES) {
            FileLogger.w(TAG, "write_file 文件过大跳过 LCS 差异 (old=${oldLines.size}, new=${newLines.size})")
            return newLines.joinToString("\n") { "+$it" }
        }
        return LineDiff.toUnified(oldContent, newContent)
    }

    private companion object {
        /** 旧/新任一侧行数超过此值即跳过 LCS：DP 表为 O(n·m) ints，过大会拖垮移动端内存。 */
        const val MAX_DIFF_LINES = 2000
    }
}
