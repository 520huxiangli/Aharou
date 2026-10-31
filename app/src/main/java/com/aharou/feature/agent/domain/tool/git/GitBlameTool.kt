package com.aharou.feature.agent.domain.tool.git

import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.workspace.domain.PathHomeResolver
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/**
 * 逐行历史（`git blame`）：告诉模型某一行是谁、在哪个提交里最后改的。
 *
 * 直接走 [CommandEngine] 而不是 [com.aharou.feature.git.domain.GitRepository]：后者是编辑器
 * git 面板用的单例，`repoCwd()` 依赖全局「当前仓库」状态，多会话并行时会串到别人的仓库；
 * 这里把 `projectPath` 钉在会话的 `context.projectRoot` 上，每个会话各看各的。
 *
 * 只读：`git blame` 不改仓库内容，按 [ToolCapability.READ_WORKSPACE] 免授权。
 */
class GitBlameTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val pathHomeResolver: PathHomeResolver,
) : AbstractContextualTool() {

    override val name = "git_blame"

    override val description =
        "查看文件每一行最后是谁、在哪个提交里改的（git blame）。整文件输出可能很长，大文件用 " +
            "start_line/end_line 限定行窗。未提交的行会标 uncommitted=true。"

    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)

    override val parameters = mapOf(
        "path" to ToolParameter(
            "path",
            ParameterType.STRING,
            "仓库内的文件路径：~/workspace/... 或容器绝对路径。",
            required = true
        ),
        "start_line" to ToolParameter(
            "start_line",
            ParameterType.INTEGER,
            "起始行号（从 1 计），与 end_line 一起限定行窗。",
            required = false
        ),
        "end_line" to ToolParameter(
            "end_line",
            ParameterType.INTEGER,
            "结束行号（含）。只给 start_line 时按其后 200 行取。",
            required = false
        ),
        "rev" to ToolParameter(
            "rev",
            ParameterType.STRING,
            "历史版本（提交号 / 分支 / tag），缺省看工作区。",
            required = false
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val path = args["path"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (path.isEmpty()) return ToolResult.Error("路径参数缺失", "MISSING_PATH")

        val start = args["start_line"]?.jsonPrimitive?.intOrNull?.coerceAtLeast(1)
        val rawEnd = args["end_line"]?.jsonPrimitive?.intOrNull
        val end = when {
            rawEnd != null -> maxOf(rawEnd, start ?: 1)
            start != null -> start + DEFAULT_WINDOW - 1
            else -> null
        }
        val rev = args["rev"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val command = buildString {
            append("git -c core.quotepath=false blame --line-porcelain")
            if (start != null && end != null) append(" -L ").append(start).append(',').append(end)
            if (rev != null) append(' ').append(shellQuote(rev))
            append(" -- ").append(shellQuote(pathHomeResolver.expandHome(path)))
        }

        return try {
            val result = commandEngine.runCommandSyncWithExit(
                command = command,
                projectPath = context.projectRoot,
                timeoutMs = TIMEOUT_MS,
            )
            val exitCode = result.exitCode
                ?: return ToolResult.Error("容器未就绪，无法执行 git blame", "CONTAINER_NOT_READY")
            if (exitCode != 0) {
                // git 的失败原因（不在仓库里 / 路径不存在）都在输出里，原样交给模型比吞掉有用。
                return ToolResult.Error(result.output.ifBlank { "git blame 失败" }, "GIT_FAILED")
            }
            val lines = parsePorcelain(result.output, MAX_LINES)
            val payload = buildJsonObject {
                put("file", JsonPrimitive(path))
                put("count", JsonPrimitive(lines.size))
                put("lines", buildJsonArray {
                    lines.forEach { entry ->
                        add(
                            buildJsonObject {
                                put("line", JsonPrimitive(entry.line))
                                put("hash", JsonPrimitive(entry.hash))
                                put("short_hash", JsonPrimitive(entry.shortHash))
                                put("author", JsonPrimitive(entry.author))
                                put("author_time", JsonPrimitive(entry.authorTimeLabel))
                                put("summary", JsonPrimitive(entry.summary))
                                put("uncommitted", JsonPrimitive(entry.uncommitted))
                            }
                        )
                    }
                })
            }
            when {
                result.outputTruncated ->
                    ToolResult.Partial(payload, "git blame 输出被截断，请用 start_line/end_line 缩小范围")
                lines.size >= MAX_LINES ->
                    ToolResult.Partial(payload, "只返回了前 $MAX_LINES 行，后面的行请用 start_line/end_line 续取")
                else -> ToolResult.Success(payload)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "git_blame 执行失败: $path", e)
            ToolResult.Error(e.message ?: "git blame 失败", "GIT_FAILED")
        }
    }

    /**
     * 解析 `--line-porcelain`：每块以 `<sha> <原行号> <最终行号> [组大小]` 开头，
     * 其后是 `author` / `author-time` / `summary` 等元数据，源码行以 TAB 开头（这里不需要）。
     *
     * 元数据要先读齐才能提交一块，所以「遇到下一个块头」时才把上一块收进结果。
     */
    private fun parsePorcelain(output: String, maxLines: Int): List<BlameEntry> {
        val entries = ArrayList<BlameEntry>()

        var hash: String? = null
        var lineNo = 0
        var author = ""
        var authorTime = 0L
        var summary = ""

        fun flush() {
            val current = hash ?: return
            entries.add(
                BlameEntry(
                    line = lineNo,
                    hash = current,
                    shortHash = current.take(7),
                    author = author,
                    authorTimeLabel = formatTime(authorTime),
                    summary = summary,
                    // 工作区里未提交的行：git 用全 0 的假哈希 + 「Not Committed Yet」占位作者。
                    uncommitted = current.all { it == '0' },
                )
            )
        }

        for (raw in output.lineSequence()) {
            if (entries.size >= maxLines) break
            if (raw.startsWith("\t")) continue

            val parts = raw.split(' ')
            val isHeader = parts.size >= 3 &&
                parts[0].length == 40 &&
                parts[0].all { it in '0'..'9' || it in 'a'..'f' } &&
                parts[1].toIntOrNull() != null &&
                parts[2].toIntOrNull() != null
            if (isHeader) {
                flush()
                hash = parts[0]
                lineNo = parts[2].toIntOrNull() ?: 0
                author = ""
                authorTime = 0L
                summary = ""
                continue
            }
            when {
                raw.startsWith("author ") -> author = raw.removePrefix("author ").trim()
                raw.startsWith("author-time ") ->
                    authorTime = raw.removePrefix("author-time ").trim().toLongOrNull() ?: 0L
                raw.startsWith("summary ") -> summary = raw.removePrefix("summary ").trim()
            }
        }
        flush()
        return entries
    }

    /** epoch 秒转本地时区的 `yyyy-MM-dd HH:mm`；缺失（0）时返回空串，不凭空造时间。 */
    private fun formatTime(epochSeconds: Long): String =
        if (epochSeconds <= 0L) ""
        else Instant.ofEpochSecond(epochSeconds)
            .atZone(ZoneId.systemDefault())
            .format(TIME_FORMAT)

    private data class BlameEntry(
        val line: Int,
        val hash: String,
        val shortHash: String,
        val author: String,
        val authorTimeLabel: String,
        val summary: String,
        val uncommitted: Boolean,
    )

    private companion object {
        const val TAG = "GitBlameTool"

        /** 只给 start_line 时默认往后看多少行。 */
        const val DEFAULT_WINDOW = 200

        /** 单次最多返回的行数，防止整文件 blame 把上下文撑爆。 */
        const val MAX_LINES = 2000

        /** 大文件整篇 blame 比普通命令慢，给足时间但仍要有界。 */
        const val TIMEOUT_MS = 20_000L

        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
