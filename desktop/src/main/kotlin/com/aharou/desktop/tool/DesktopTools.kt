package com.aharou.desktop.tool

import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 桌面端的工具集。
 *
 * 不复用手机端的文件工具：那套建立在「容器路径 / FileAccessProvider / 工作区映射」之上，
 * 桌面端就是本地文件系统，直接用 java.nio 更直接。所有路径都被限制在 [root] 内。
 */
class DesktopToolbox(private val root: File) {

    private val canonicalRoot: File = root.canonicalFile

    val tools: List<AgentTool> = listOf(
        DesktopListTool(),
        DesktopReadFileTool(),
        DesktopWriteFileTool(),
        DesktopShellTool()
    )

    fun find(name: String): AgentTool? = tools.firstOrNull { it.name == name }

    /** 把 AI 给的路径解析成工作区内的真实文件，越界直接抛。 */
    private fun resolve(raw: String): File {
        var path = raw.trim().removePrefix("~/workspace").removePrefix("~")
        if (path.isBlank()) path = "."
        val candidate = if (path.startsWith("/") || path.matches(Regex("^[A-Za-z]:[\\\\/].*"))) {
            File(path)
        } else {
            File(canonicalRoot, path)
        }
        val canonical = candidate.canonicalFile
        if (canonical != canonicalRoot && !canonical.path.startsWith(canonicalRoot.path + File.separator)) {
            throw SecurityException("路径越界：$raw（只能操作 $canonicalRoot 之内）")
        }
        return canonical
    }

    private fun relative(file: File): String =
        file.relativeTo(canonicalRoot).path.ifBlank { "." }

    inner class DesktopListTool : AgentTool() {
        override val name = "list"
        override val description = "列出工作区目录内容。path 省略时列根目录。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        override val parameters: Map<String, ToolParameter> = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "相对路径，省略为工作区根目录", required = false)
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = runCatching {
            val dir = resolve((args["path"] as? JsonPrimitive)?.contentOrNull ?: ".")
            if (!dir.isDirectory) return@runCatching ToolResult.Error("不是目录：${relative(dir)}", "NOT_DIRECTORY")
            val entries = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name })).orEmpty()
            val json = buildJsonArray {
                entries.take(500).forEach { f ->
                    add(
                        buildJsonObject {
                            put("name", f.name)
                            put("dir", f.isDirectory)
                            put("size", if (f.isDirectory) 0 else f.length())
                            put("path", relative(f))
                        }
                    )
                }
            }
            ToolResult.Success(json)
        }.getOrElse { ToolResult.Error(it.message ?: "列出目录失败", "LIST_FAILED") }
    }

    inner class DesktopReadFileTool : AgentTool() {
        override val name = "readFile"
        override val description = "读取工作区内的文本文件。大文件可给 offset/limit 按行读取。"
        override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
        override val parameters: Map<String, ToolParameter> = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "相对路径"),
            "offset" to ToolParameter("offset", ParameterType.INTEGER, "起始行号（1 起）", required = false),
            "limit" to ToolParameter("limit", ParameterType.INTEGER, "最多读多少行", required = false)
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = runCatching {
            val file = resolve((args["path"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching ToolResult.Error("缺少 path", "BAD_ARGS"))
            if (!file.isFile) return@runCatching ToolResult.Error("不是文件：${relative(file)}", "NOT_FILE")
            if (file.length() > MAX_READ_BYTES) {
                return@runCatching ToolResult.Error("文件太大（${file.length()} 字节），请用 offset/limit 分段读", "TOO_LARGE")
            }
            val lines = file.readLines()
            val offset = (args["offset"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.coerceAtLeast(1) ?: 1
            val limit = (args["limit"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: lines.size
            val slice = lines.drop(offset - 1).take(limit)
            ToolResult.Success(
                buildJsonObject {
                    put("path", relative(file))
                    put("totalLines", lines.size)
                    put("content", slice.joinToString("\n"))
                }
            )
        }.getOrElse { ToolResult.Error(it.message ?: "读取失败", "READ_FAILED") }
    }

    inner class DesktopWriteFileTool : AgentTool() {
        override val name = "writeFile"
        override val description = "写入工作区内的文本文件，父目录不存在会自动创建，文件已存在则覆盖。"
        override val permissionPolicy = ToolPermissionPolicy.ASK
        override val parameters: Map<String, ToolParameter> = mapOf(
            "path" to ToolParameter("path", ParameterType.STRING, "相对路径"),
            "content" to ToolParameter("content", ParameterType.STRING, "完整文件内容")
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = runCatching {
            val path = (args["path"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching ToolResult.Error("缺少 path", "BAD_ARGS")
            val content = (args["content"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching ToolResult.Error("缺少 content", "BAD_ARGS")
            val file = resolve(path)
            file.parentFile?.mkdirs()
            file.writeText(content)
            ToolResult.Success(
                buildJsonObject {
                    put("path", relative(file))
                    put("bytes", file.length())
                }
            )
        }.getOrElse { ToolResult.Error(it.message ?: "写入失败", "WRITE_FAILED") }
    }

    inner class DesktopShellTool : AgentTool() {
        override val name = "shell"
        override val description = "在工作区目录下执行一条 shell 命令（Windows 上是 cmd）。有超时限制。"
        override val permissionPolicy = ToolPermissionPolicy.ASK
        override val parameters: Map<String, ToolParameter> = mapOf(
            "command" to ToolParameter("command", ParameterType.STRING, "要执行的命令")
        )

        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = runCatching {
            val command = (args["command"] as? JsonPrimitive)?.contentOrNull ?: return@runCatching ToolResult.Error("缺少 command", "BAD_ARGS")
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val process = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("/bin/sh", "-c", command)
            }.directory(canonicalRoot).redirectErrorStream(true).start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@runCatching ToolResult.Error("命令超时（${COMMAND_TIMEOUT_SECONDS}s）", "TIMEOUT")
            }
            ToolResult.Success(
                buildJsonObject {
                    put("exitCode", process.exitValue())
                    put("output", output.take(MAX_OUTPUT_CHARS))
                }
            )
        }.getOrElse { ToolResult.Error(it.message ?: "执行失败", "SHELL_FAILED") }
    }

    private companion object {
        const val MAX_READ_BYTES = 2L * 1024 * 1024
        const val MAX_OUTPUT_CHARS = 32 * 1024
        const val COMMAND_TIMEOUT_SECONDS = 120L
    }
}

