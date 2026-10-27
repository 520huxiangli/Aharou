package com.aharou.feature.agent.domain.tool.container

import com.aharou.feature.agent.domain.container.BoundedOutput
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandEvent
import com.aharou.feature.agent.domain.container.ContainerOutputSanitizer
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.StreamingAgentTool
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.ToolStreamEvent
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import javax.inject.Inject

/**
 * Tool that allows the AI agent to execute commands inside the Linux container.
 *
 * 命令在当前选中工作区目录下执行，使 AI 的 shell 操作（npm install、git 等）
 * 与文件工具作用于同一目录。
 *
 * 同时实现 [StreamingAgentTool]：优先逐行流式输出，让聊天里能实时看到命令执行过程；
 * [execute] 作为非流式兜底保留，最终聚合结果两者一致（喂回模型不变）。
 */
class ExecuteCommandTool @Inject constructor(
    private val commandEngine: CommandEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val terminalSessionProvider: TerminalSessionProvider
) : AgentTool(), StreamingAgentTool {
    private companion object {
        const val TAG = "ExecuteCommandTool"

        /** 默认超时（秒），与 [LinuxContainerEngine.DEFAULT_TIMEOUT_MS] 对齐。 */
        const val DEFAULT_TIMEOUT_SECONDS = 120L

        /** 超时上限（秒），与 [LinuxContainerEngine.MAX_TIMEOUT_MS] 对齐。 */
        const val MAX_TIMEOUT_SECONDS = 1_800L
    }

    override val name = "Bash"
    override val description = "在当前执行环境（本地容器或远程 SSH）中执行 Shell 命令。" +
        "预计超过 30 秒的命令（构建、下载、部署）请加 background=true 提交到后台终端，" +
        "或改用 `terminal`；**不要**用 `&`/`nohup` 挂后台——容器会随本次调用结束把后台进程一起杀掉。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.EXECUTE_COMMANDS)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "要执行的 shell 命令",
            required = true
        ),
        "timeout" to ToolParameter(
            name = "timeout",
            type = ParameterType.INTEGER,
            description = "命令最长执行时间（秒），超时将被强制终止。默认 $DEFAULT_TIMEOUT_SECONDS 秒，上限 $MAX_TIMEOUT_SECONDS 秒。耗时命令（如安装依赖）可适当调大。",
            required = false
        ),
        "background" to ToolParameter(
            name = "background",
            type = ParameterType.BOOLEAN,
            description = "置 true 时把命令提交到常驻后台终端并立即返回（返回 tab 号），命令结束时自动通知；适合构建/下载/部署这类长任务。默认 false（同步等结果，超过 timeout 会被强制终止）。",
            required = false
        ),
        "elevate" to ToolParameter(
            name = "elevate",
            type = ParameterType.BOOLEAN,
            description = "提权重试：命令因内置安全防护（灾难性删除等）被拒且确有必要时，置为 true 重试，会弹窗请用户一次性授权（不可记忆）。置 true 时必须同时给 reason。PLAN 模式下无效。",
            required = false
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "提权理由：elevate=true 时必填。写清这次为什么非执行不可（影响什么、不做会怎样），" +
                "会连同命令一起展示给用户确认。不要写「因为需要」这类空话。",
            required = false
        )
    )

    /** 解析 timeout（秒）参数并钳到合法范围，返回毫秒；缺省用默认值。 */
    private fun resolveTimeoutMs(args: Map<String, JsonElement>): Long {
        val seconds = args["timeout"]?.jsonPrimitive?.longOrNull ?: DEFAULT_TIMEOUT_SECONDS
        return seconds.coerceIn(1L, MAX_TIMEOUT_SECONDS) * 1000L
    }

    private fun isBackground(args: Map<String, JsonElement>): Boolean =
        args["background"]?.jsonPrimitive?.booleanOrNull ?: false

    private fun isElevation(args: Map<String, JsonElement>): Boolean =
        args["elevate"]?.jsonPrimitive?.booleanOrNull ?: false

    /**
     * 把命令提交到常驻后台终端（[TerminalSessionProvider]）并立即返回 tab 号。
     *
     * 一次性 Bash 跑的是带 `--kill-on-exit` 的 proot 进程，调用一结束整棵子进程树都会被回收，
     * 所以长任务必须挂到常驻会话里才不会半途死掉。
     */
    private suspend fun startBackground(
        command: String,
        args: Map<String, JsonElement>,
        context: com.aharou.feature.agent.domain.model.AgentContext
    ): ToolResult =
        try {
            // 工作区按发起会话给：后台标签的 proot 挂载在启动那刻写死，不能用全局当前工作区。
            // TerminalSession 构造时内部会 new Handler()（绑当前线程 Looper），必须在有 Looper 的
            // 线程（主线程）构造，否则在协程后台线程上会抛 "Can't create handler..."。
            val tabId = withContext(Dispatchers.Main) {
                terminalSessionProvider.startBackgroundCommand(
                    command = command,
                    title = command.take(40),
                    notify = true,
                    workspacePath = context.projectRoot
                )
            }
            FileLogger.i(TAG, "已提交后台任务 tab=$tabId: $command")
            ToolResult.Success(
                JsonPrimitive(
                    "已提交到后台终端（tab $tabId），命令结束时会自动通知；" +
                        "要看输出或交互就用 terminal 工具（read/send）。"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "提交后台任务失败: $command", e)
            ToolResult.Error("提交后台任务失败: ${e.message}")
        }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val command = args["command"]?.jsonPrimitive?.contentOrNull ?: "未知命令"
        val timeoutSeconds = resolveTimeoutMs(args) / 1000L
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认执行命令",
            summary = command,
            details = "将在当前执行环境中执行。\n超时：${timeoutSeconds} 秒",
            argsPreview = argsPreview
        )
    }

    /** 会话绑定的工作区；会话没绑定时回退全局当前工作区。 */
    private fun sessionWorkdir(context: com.aharou.feature.agent.domain.model.AgentContext): String =
        context.projectRoot.takeIf { it.isNotBlank() } ?: workspaceRepository.currentPath()

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: com.aharou.feature.agent.domain.model.AgentContext
    ): ToolResult {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.Error("缺少必需参数: command")

        // 提权必须说明理由：理由会随命令一起进确认弹窗，避免「反正能提权就先试一下」的随手重试。
        val elevateReason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
        if (isElevation(args) && elevateReason.isNullOrEmpty()) {
            return ToolResult.Error(
                "提权必须说明理由：在 reason 参数里写清这次为什么非执行不可（会展示给用户确认）。",
                "ELEVATE_REASON_REQUIRED"
            )
        }

        if (isBackground(args)) return startBackground(command, args, context)

        return try {
            // 在会话绑定的工作区目录内执行，与文件工具保持同一根目录
            val workdir = sessionWorkdir(context)
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command (timeout=${timeoutMs}ms): $command")
            val result = commandEngine.runCommandSyncWithExit(command, workdir, timeoutMs)
            FileLogger.v(TAG, "execute_command 完成，输出 ${result.output.length} 字符，退出码 ${result.exitCode}")
            ToolResult.Success(JsonPrimitive(toModelOutput(result.output, result.exitCode)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "execute_command 失败: $command", e)
            ToolResult.Error("执行命令失败: ${e.message}")
        }
    }

    /**
     * 流式执行：逐行 emit [ToolStreamEvent.Progress]，命令结束 emit [ToolStreamEvent.Completed]，
     * 其最终结果与 [execute] 等价（同样经 [BoundedOutput] 限幅：超大输出仅保留开头+结尾，
     * 再经 [toModelOutput] 清洗），保证喂回模型的内容一致且不会撑爆上下文。
     *
     * 注意 [ToolStreamEvent.Progress] 逐行发给的是原始行（未清洗），供聊天里实时看进度；
     * 清洗只作用于最终回给模型的文本。
     */
    override fun executeStream(
        args: Map<String, JsonElement>,
        context: com.aharou.feature.agent.domain.model.AgentContext
    ): Flow<ToolStreamEvent> = flow {
        val command = args["command"]?.jsonPrimitive?.contentOrNull
        if (command == null) {
            emit(ToolStreamEvent.Completed(ToolResult.Error("缺少必需参数: command")))
            return@flow
        }

        if (isBackground(args)) {
            emit(ToolStreamEvent.Completed(startBackground(command, args, context)))
            return@flow
        }

        // 限幅累积：喂回模型的最终结果只保留开头+结尾，避免超大输出撑爆上下文。
        val accumulated = BoundedOutput()
        var exitCode: Int? = null
        try {
            val workdir = sessionWorkdir(context)
            val timeoutMs = resolveTimeoutMs(args)
            FileLogger.d(TAG, "execute_command(流式, timeout=${timeoutMs}ms): $command")
            commandEngine.runCommandStream(command, workdir, timeoutMs).collect { event ->
                when (event) {
                    is CommandEvent.Line -> {
                        accumulated.append(event.text)
                        accumulated.append("\n")
                        emit(ToolStreamEvent.Progress(event.text))
                    }
                    is CommandEvent.Exit -> exitCode = event.code
                }
            }
            FileLogger.v(TAG, "execute_command(流式) 完成，输出 ${accumulated.totalChars} 字符，退出码 $exitCode")
            emit(ToolStreamEvent.Completed(ToolResult.Success(JsonPrimitive(toModelOutput(accumulated.build(), exitCode)))))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 兜底：底层 flow 异常终止时，已逐行 emit 给用户的 Progress 仍应作为最终结果保留，
            // 而不是被这里抛出的空 Error 覆盖掉（否则模型只看到“执行失败”，之前展示的输出全丢）。
            FileLogger.e(TAG, "execute_command(流式) 异常(已保留此前输出 ${accumulated.totalChars} 字符): $command", e)
            val saved = accumulated.build()
            val result = if (saved.isNotEmpty()) {
                ToolResult.Success(JsonPrimitive(toModelOutput(saved, exitCode)))
            } else {
                ToolResult.Error("执行命令失败: ${e.message}")
            }
            emit(ToolStreamEvent.Completed(result))
        }
    }

    /**
     * 命令输出交给模型的**唯一出口**（[executeWithContext] 与 [executeStream] 共用）。
     *
     * 为什么清洗放在这里而不是引擎层：[LinuxContainerEngine] 的同一条输出通道还供 git diff 预览、
     * 文件内容读取等**按行解析**的调用方使用，在那里吞行或压空行等于改动被解析的数据本身；
     * 本出口只承担命令 stdout/stderr（stderr 已合并），不涉及 JSON、文件内容这类结构化结果。
     *
     * 顺序（截断 → 清洗）：长度截断已在引擎与流式累积器（[BoundedOutput]）内部完成，出口拿到的是
     * 已限幅文本，清洗天然发生在截断之后。反过来的「先清洗再截断」只有流式路径做得到（非流式在引擎
     * 内部就截断了），两条路径会对同一输出给出不同文本；而清洗只会让文本变短、不会把内容挤出长度
     * 预算。代价是截断点可能落在转义序列中间，残留的 ESC 字节会被清洗器的控制字符规则剥掉，最多
     * 留下少许参数碎片（如 `[38;5`），不影响可读性。
     *
     * 退出码拼在清洗之后的末尾，让模型能分辨「跑完没输出」和「失败没输出」；拿不到退出码（超时、
     * 引擎异常、远程通道断开）就不拼，别让模型把未知当成成功。退出码行是本函数生成的，不参与清洗。
     */
    private fun toModelOutput(output: String, exitCode: Int?): String {
        val cleaned = ContainerOutputSanitizer.sanitize(output)
        val line = exitCode?.let { "[退出码: $it]" } ?: return cleaned
        return if (cleaned.isBlank()) line else "$cleaned\n$line"
    }
}
