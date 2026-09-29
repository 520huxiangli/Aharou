package com.aharou.feature.agent.domain.tool.explorer

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileEntry
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 以 ls 风格列出目录内容——纯只读探索工具。
 *
 * 不进容器，通过 [WorkspacePathMapper] 映射容器路径到宿主文件系统直接遍历，
 * 与 `readFile` 一致的路径解析方式。两种模式下均可使用。
 */
class ListFilesTool @Inject constructor(
    private val fileAccess: FileAccessProvider
) : AgentTool() {

    private companion object {
        const val TAG = "ListTool"
        const val MAX_ENTRIES = 500
    }

    override val name = "list"
    override val description = "按 ls 风格列出文件和目录。例：args=\"-la ~/workspace/app\"。支持追加 `| head [-n N]` 截断输出。"
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.STRING,
            description = "ls 风格参数。不填等同 ~/workspace。支持 -a -A -l -R -d -1 -h -r -t -S -v -f --。支持末尾追加 `| head [-n N]` 截断输出；其它管道命令不支持。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        // 会话绑定的工作区：本会话的文件操作只落在自己的工作区，不跟着全局切换走
        val access = (fileAccess).forWorkspace(context.projectRoot)
        return try {
            val rawArgs = args["args"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val tokens = parseShellWords(rawArgs)
                ?: return ToolResult.Error("args 中存在未闭合的引号", "INVALID_ARGS")
            val (lsTokens, pipeSegments) = splitPipes(tokens)
                ?: return ToolResult.Error("管道符前缺少 ls 参数", "INVALID_PIPE")
            val headLimits = pipeSegments.map { seg ->
                headLinesOf(seg)
                    ?: return ToolResult.Error("list 仅支持 | head [-n N] 截断输出，不支持其它管道命令", "INVALID_PIPE")
            }
            val options = parseLsOptions(lsTokens)
                ?: return ToolResult.Error("不支持的 ls 参数。支持：-a, -A, -l, -R, -d, -1, -h, -r, -t, -f, --", "UNSUPPORTED_OPTION")
            options.maxLines = headLimits.minOrNull()

            FileLogger.d(TAG, "list args=$rawArgs paths=${options.paths}")

            val output = StringBuilder()
            var entryCount = 0
            var truncated = false

            fun appendLine(line: String = "") {
                if (entryCount >= (options.maxLines ?: MAX_ENTRIES)) {
                    truncated = true
                    return
                }
                output.append(line).append('\n')
                entryCount++
            }

            fun listPath(path: String, showHeader: Boolean) {
                if (entryCount >= (options.maxLines ?: MAX_ENTRIES)) {
                    truncated = true
                    return
                }

                val containerPath = access.toDisplayPath(path)

                if (!access.exists(path)) {
                    appendLine("ls: cannot access '$path': No such file or directory")
                    return
                }

                if (!access.isDirectory(path) || options.directoryOnly) {
                    val name = path.substringAfterLast('/').ifBlank { containerPath }
                    appendEntry(LsEntry(name, path), options, ::appendLine)
                    return
                }

                if (showHeader) appendLine("${containerPath.trimEnd('/')}:")
                val children = listEntries(access, path, options)
                for (entry in children) {
                    if (entryCount >= (options.maxLines ?: MAX_ENTRIES)) {
                        truncated = true
                        return
                    }
                    appendEntry(entry, options, ::appendLine)
                }

                if (options.recursive) {
                    for (entry in children) {
                        if (entryCount >= (options.maxLines ?: MAX_ENTRIES)) {
                            truncated = true
                            return
                        }
                        if (!access.isDirectory(entry.fullPath) || entry.name == "." || entry.name == "..") continue
                        appendLine()
                        listPath(entry.fullPath, showHeader = true)
                    }
                }
            }

            val showHeaders = options.paths.size > 1 || options.recursive
            for ((index, path) in options.paths.withIndex()) {
                if (index > 0) appendLine()
                listPath(path, showHeaders)
            }

            if (truncated && options.maxLines == null) {
                output.append("... (已达 $MAX_ENTRIES 条上限，剩余条目未列出)\n")
            }

            FileLogger.v(TAG, "list 完成 entries=$entryCount truncated=$truncated")
            ToolResult.Success(JsonObject(mapOf(
                "content" to JsonPrimitive(output.toString()),
                "entries" to JsonPrimitive(entryCount),
                "truncated" to JsonPrimitive(truncated)
            )))
        } catch (e: Exception) {
            FileLogger.e(TAG, "list 异常", e)
            ToolResult.Error(e.message ?: "列出目录失败", "LIST_ERROR")
        }
    }

    private fun parseLsOptions(tokens: List<String>): LsOptions? {
        val options = LsOptions()
        var parseOptions = true
        for (token in tokens) {
            when {
                parseOptions && token == "--" -> parseOptions = false
                parseOptions && token.startsWith("--") -> {
                    when (token) {
                        "--all" -> options.showAll = true
                        "--almost-all" -> options.showAlmostAll = true
                        "--long" -> options.longFormat = true
                        "--recursive" -> options.recursive = true
                        "--directory" -> options.directoryOnly = true
                        "--human-readable" -> options.humanReadable = true
                        "--reverse" -> options.reverse = true
                        "--time" -> options.sortByTime = true
                        "--size" -> options.sortBySize = true
                        else -> return null
                    }
                }
                parseOptions && token.startsWith("-") && token.length > 1 -> {
                    for (flag in token.drop(1)) {
                        when (flag) {
                            'a' -> options.showAll = true
                            'A' -> options.showAlmostAll = true
                            'l' -> options.longFormat = true
                            'R' -> options.recursive = true
                            'd' -> options.directoryOnly = true
                            '1' -> options.onePerLine = true
                            'h' -> options.humanReadable = true
                            'r' -> options.reverse = true
                            't' -> options.sortByTime = true
                            'S' -> options.sortBySize = true
                            'v' -> options.naturalSort = true
                            'f' -> {
                                options.noSort = true
                                options.showAll = true
                            }
                            else -> return null
                        }
                    }
                }
                else -> options.paths.add(token)
            }
        }
        if (options.paths.isEmpty()) options.paths.add(WorkspacePathMapper.CONTAINER_ROOT)
        return options
    }

    private fun listEntries(access: FileAccessProvider, dirPath: String, options: LsOptions): List<LsEntry> {
        val entries = mutableListOf<LsEntry>()
        if (options.showAll && !options.showAlmostAll) {
            entries.add(LsEntry(".", dirPath, isDir = true, size = 0, lastModified = 0, permissions = "rwx"))
            access.parentPath(dirPath)?.let { parent ->
                entries.add(LsEntry("..", parent, isDir = true, size = 0, lastModified = 0, permissions = "rwx"))
            }
        }
        val children = access.listFiles(dirPath)
            .filter { options.showAll || options.showAlmostAll || !it.name.startsWith(".") }
            .map { LsEntry(it.name, "$dirPath/${it.name}".trimEnd('/'), it.isDirectory, it.size, it.lastModified, it.permissions) }
        entries.addAll(children)

        if (!options.noSort) {
            val comparator = when {
                options.sortByTime -> compareBy<LsEntry> { -it.lastModified }
                options.sortBySize -> compareBy<LsEntry> { -it.size }
                options.naturalSort -> compareBy<LsEntry> { naturalOrderKey(it.name) }
                else -> null
            }?.thenBy { it.name }
            if (comparator != null) entries.sortWith(comparator)
        }
        if (options.reverse) entries.reverse()
        return entries
    }

    private fun appendEntry(
        entry: LsEntry,
        options: LsOptions,
        appendLine: (String) -> Unit
    ) {
        if (options.longFormat) {
            appendLine(longFormat(entry, options))
        } else {
            appendLine(entry.name)
        }
    }

    private fun longFormat(entry: LsEntry, options: LsOptions): String {
        val type = if (entry.isDir) "d" else "-"
        val perms = entry.permissions
        val owner = perms
        val group = "---"
        val other = "---"
        val size = if (options.humanReadable) humanSize(entry.size) else entry.size.toString()
        val time = SimpleDateFormat("MMM dd HH:mm", Locale.US).format(Date(entry.lastModified))
        return "$type$owner$group$other 1 user group ${size.padStart(8)} $time ${entry.name}"
    }

    private fun naturalOrderKey(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i].isDigit()) {
                val start = i
                while (i < s.length && s[i].isDigit()) i++
                // 数字段补零到固定宽度，使字典序等价数值序；不 toInt 以避免超长数字段溢出崩溃。
                sb.append(s.substring(start, i).padStart(20, '0'))
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = arrayOf("K", "M", "G", "T")
        var value = bytes.toDouble() / 1024.0
        var unit = 0
        while (value >= 1024.0 && unit < units.lastIndex) {
            value /= 1024.0
            unit++
        }
        return if (value >= 10) "%.0f%s".format(Locale.US, value, units[unit])
        else "%.1f%s".format(Locale.US, value, units[unit])
    }

    private data class LsOptions(
        var showAll: Boolean = false,
        var showAlmostAll: Boolean = false,
        var longFormat: Boolean = false,
        var recursive: Boolean = false,
        var directoryOnly: Boolean = false,
        var onePerLine: Boolean = false,
        var humanReadable: Boolean = false,
        var reverse: Boolean = false,
        var sortByTime: Boolean = false,
        var sortBySize: Boolean = false,
        var naturalSort: Boolean = false,
        var noSort: Boolean = false,
        var maxLines: Int? = null,
        val paths: MutableList<String> = mutableListOf()
    )

    private data class LsEntry(
        val name: String,
        val fullPath: String,
        val isDir: Boolean = false,
        val size: Long = 0,
        val lastModified: Long = 0,
        val permissions: String = "---"
    )
}
