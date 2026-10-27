package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentAction
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSessionState
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSideEffect

/**
 * 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数)。
 *
 * 单独成文件的原因：StatefulAgentWorkflow.kt 已顶到架构门禁的单文件行数基线（棘轮只许降），
 * 而本次为压协程状态机体积又在其中拆出了若干函数。本函数不读写 workflow 的任何实例状态，
 * 抽到独立文件既保住基线，也省掉一层实例耦合。
 */
internal fun StatefulAgentWorkflow.reduce(
    state: AgentSessionState,
    action: AgentAction,
    stuckNudgeText: String = STUCK_NUDGE_TEXT
): Pair<AgentSessionState, List<AgentSideEffect>> {
    var newState = state
    val effects = mutableListOf<AgentSideEffect>()

    when (action) {
        is AgentAction.InitRequest -> {
            // 恢复历史时补孤儿 tool call：assistant 声明了调用却没有结果（如审批等待期间进程被杀），
            // OpenAI / Anthropic 会直接拒绝这类历史，故先补一条结果占位再进循环。
            newState = state.copy(messages = repairDanglingToolCalls(action.initialMessages))
            effects.add(AgentSideEffect.CallLlm)
        }
        is AgentAction.LlmResponse -> {
            val assistantMsg = AgentMessage.AssistantMessage(
                id = action.messageId,
                content = action.response.content,
                toolCalls = action.response.toolCalls,
                reasoning = action.response.reasoning ?: "",
                signature = action.response.signature ?: "",
                thinkingBlocksJson = action.response.thinkingBlocksJson ?: "",
                images = action.response.images,
                inputTokens = action.response.inputTokens
            )
            newState = state.copy(
                messages = state.messages + assistantMsg,
                iterations = state.iterations + 1
            )

            if (action.response.toolCalls.isEmpty()) {
                if (action.response.isAborted) {
                    // 拒答/上下文超限：正文可能为空，静默结束会让用户看到空白气泡以为卡死。
                    newState = newState.copy(
                        isFinished = true,
                        error = action.response.stopDetail ?: "",
                        errorCode = action.response.stopReason
                    )
                } else if (action.response.isTruncated) {
                    val continuation = AgentMessage.UserMessage(content = "Your response was truncated. Continue from where it stopped.")
                    newState = newState.copy(messages = newState.messages + continuation)
                    effects.add(AgentSideEffect.PersistUser(continuation))
                    effects.add(AgentSideEffect.CallLlm)
                } else if (action.response.content.isBlank() &&
                    action.response.reasoning.isNullOrBlank() &&
                    action.response.images.isEmpty()
                ) {
                    // 空响应（无正文、无思考、无图）：静默收尾会让用户只看到一张空白气泡，
                    // 与「模型真的答完了」无法区分；交给 UI 按 errorCode 展示原因并允许重试。
                    newState = newState.copy(isFinished = true, error = "", errorCode = "empty_response")
                } else {
                    newState = newState.copy(isFinished = true)
                }
            } else {
                // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                // 全部评估完成后才进入并行执行阶段（见 PermissionBatchEvaluated / ToolBatchFinished）。
                val toolCalls = action.response.toolCalls.toList()
                newState = newState.copy(
                    batchToolCalls = toolCalls,
                    pendingPermissionCalls = toolCalls,
                    approvedToolCalls = emptyList(),
                    rejectedToolResults = emptyMap()
                )
                effects.add(AgentSideEffect.RequestPermissionBatch(toolCalls))
            }
        }
        is AgentAction.LlmError -> {
            newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
        }
        is AgentAction.PermissionBatchEvaluated -> {
            if (action.userRejected) {
                // 用户拒绝了本批（含「全部拒绝」）：整批取消，按 batchToolCalls 原始顺序为所有调用补上
                // tool 响应（不重复不遗漏），否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                // "insufficient tool messages following tool_calls"。已先行被策略拒绝的项沿用其真实拒绝原因。
                effects.add(AgentSideEffect.RejectToolBatch(state.batchToolCalls,
                    "用户拒绝了本轮工具调用，该调用未执行。", StatefulAgentWorkflow.USER_REJECTED_CODE, true,
                    action.rejectedResults))
                return newState to effects
            }
            // 批准集（含策略放行与用户放行）进入并行执行；策略拒绝项留在 rejectedResults，
            // 由 ToolBatchFinished 按 batchToolCalls 原始顺序合并组装。
            newState = state.copy(
                approvedToolCalls = action.approved,
                rejectedToolResults = action.rejectedResults,
                pendingPermissionCalls = emptyList()
            )
            effects.add(AgentSideEffect.ExecuteToolBatch(action.approved))
        }
        is AgentAction.ToolBatchRejected -> {
            newState = state.copy(messages = state.messages + action.results.map {
                AgentMessage.ToolResultMessage(it.id, it.toolName, it.result,
                    modelResult = modelToolResultText(it.toolName, it.result))
            }, batchToolCalls = emptyList(), pendingPermissionCalls = emptyList(),
                approvedToolCalls = emptyList(), rejectedToolResults = emptyMap(), isFinished = action.finish)
            if (!action.finish) effects.add(AgentSideEffect.CallLlm)
        }
        is AgentAction.ToolBatchFinished -> {
            // 本批工具全部执行完，按 batchToolCalls 原始顺序组装 tool 响应：
            // 优先取策略拒绝结果，其次取并行执行结果，保证与 assistant(toolCalls) 顺序一致。
            val resultsById = action.results.associateBy { it.id }
            val appendedMessages = mutableListOf<AgentMessage>()
            newState.batchToolCalls.forEach { call ->
                val batchResult = newState.rejectedToolResults[call.id] ?: resultsById[call.id] ?: return@forEach
                appendedMessages.add(
                    AgentMessage.ToolResultMessage(
                        id = batchResult.id,
                        toolName = batchResult.toolName,
                        result = batchResult.result,
                        images = batchResult.images,
                        modelResult = modelToolResultText(batchResult.toolName, batchResult.result)
                    )
                )
            }
            newState = state.copy(
                messages = state.messages + appendedMessages,
                batchToolCalls = emptyList(),
                pendingPermissionCalls = emptyList(),
                approvedToolCalls = emptyList(),
                rejectedToolResults = emptyMap(),
                totalToolCalls = state.totalToolCalls + newState.batchToolCalls.size
            )
            // 工具循环检测：判定从已回填的消息重建滑动窗口（键 = 工具名 + 参数 + 结果哈希），
            // 所以 reducer 仍是纯函数，不额外持有检测器实例；压缩/重启后窗口自然重建。
            val verdict = toolLoopVerdict(newState.messages)
            val crossed = verdict.level.ordinal > toolLoopVerdict(state.messages).level.ordinal
            when {
                // 熔断：工具结果已按序补完（不会留下孤儿 tool_call），直接走现有「带错误结束」通路终止本轮。
                verdict.level == ToolLoopLevel.CIRCUIT_BREAK -> {
                    FileLogger.w(StatefulAgentWorkflow.TAG,
                        "工具循环熔断：${verdict.toolName} 连续 ${verdict.repeatCount} 次同参同结果，终止本轮")
                    newState = newState.copy(
                        isFinished = true,
                        error = toolLoopCircuitBreakText(verdict.toolName, verdict.repeatCount),
                        errorCode = TOOL_LOOP_CIRCUIT_BREAK_CODE
                    )
                }
                // 警告/严重：只在刚跨过某一阈值的那一批注入一次，否则阈值之上每批都插一句会把上下文刷满。
                crossed && verdict.level != ToolLoopLevel.NONE -> {
                    val text = if (verdict.level == ToolLoopLevel.CRITICAL) {
                        toolLoopCriticalText(verdict.toolName, verdict.repeatCount)
                    } else {
                        toolLoopWarningText(verdict.toolName, verdict.repeatCount)
                    }
                    FileLogger.i(StatefulAgentWorkflow.TAG,
                        "工具循环${if (verdict.level == ToolLoopLevel.CRITICAL) "严重提醒" else "警告"}：" +
                            "${verdict.toolName} 连续 ${verdict.repeatCount} 次同参同结果")
                    // 用固定 id 前缀标记这是「循环提醒」而非用户新指令：判定窗口遇到真用户消息才清零。
                    newState = newState.copy(
                        messages = newState.messages + AgentMessage.UserMessage(
                            id = "$TOOL_LOOP_NUDGE_ID-${verdict.repeatCount}",
                            content = text
                        )
                    )
                }
            }
            // 卡死探测（零额外模型调用）：最近 3 条 assistant 整体重复时注入一次提示让模型换招。
            // 只在历史里还没有同一句提示时注入，避免每批都插、也避免重启后重复。
            if (!newState.isFinished && detectStuck(newState.messages) && !nudgedAlready(newState.messages, stuckNudgeText)) {
                newState = newState.copy(messages = newState.messages + AgentMessage.UserMessage(content = stuckNudgeText))
                FileLogger.i(StatefulAgentWorkflow.TAG, "检测到连续重复动作，注入卡死提示")
            }
            // 熔断时不再请求下一轮，本轮就到此结束（终止原因已写进 state.error，由主循环统一上抛）。
            if (!newState.isFinished) effects.add(AgentSideEffect.CallLlm)
        }
    }

    return Pair(newState, effects)
}

/** 卡死判定窗口：取最近这么多条 assistant 消息做整体比对。 */
private const val STUCK_WINDOW = 3

/**
 * 卡死提示的兜底文案：连续重复同一动作时注入给模型，让它停下换招或直接向用户求助。
 *
 * 正常路径由 `StatefulAgentWorkflow` 从 `assets/prompts/agent/stuck-nudge.md` 读入后传进 reduce
 * （用户可改该文件定制）；reducer 是纯函数、不做文件读取，这里只留一份默认值供未传入时使用。
 * 参考 nanomuse prompts.py 的 STUCK_PROMPT。
 */
internal const val STUCK_NUDGE_TEXT =
    "你在重复同一个动作，停下来换个策略，或向用户提问。如果确实推进不下去，就直接向用户说明卡在哪里，不要继续重复。"

/**
 * 循环警告（重复达 [ToolLoopThresholds.WARNING] 级）：只提醒不打断，措辞里留一条「真在等外部任务」的出口，
 * 免得模型把合法的等待当成错误而去写一些花活。与 [STUCK_NUDGE_TEXT] 一样是给模型看的，不进 UI 文案。
 */
internal fun toolLoopWarningText(toolName: String, repeatCount: Int): String =
    "循环检测提醒：你已用完全相同的参数调用 $toolName $repeatCount 次，返回结果也一模一样，这一步没有产生任何新信息。" +
        "换一种做法（改参数、换工具，或先根据已有结果往下走），或者在回复里说明为什么必须继续重复。" +
        "如果你确实在等外部任务（子代理、长命令、界面变化）完成，请明确说明，并改用带超时的等待，而不是反复轮询。"

/**
 * 循环严重提醒（重复达 [ToolLoopThresholds.CRITICAL] 级）：上一级提醒没能让它换招，
 * 这一级不再只是建议，而是要求下一步必须改变策略或明确结束。
 */
internal fun toolLoopCriticalText(toolName: String, repeatCount: Int): String =
    "循环检测严重提醒：$toolName 已连续 $repeatCount 次返回完全相同的结果，再重复下去只会白白耗费时间和额度。" +
        "下一步必须改变策略（换成不同参数/不同工具/重新观察当前状态），或者直接结束本轮并向用户说明卡在哪里；" +
        "不允许再用同样的参数调用它。"

/**
 * 熔断终止原因（会作为 [AgentEvent.Failed.error] 展示给用户，因此是可读中文而不是错误码）。
 * 本地化文案的字符串键 reserved：`agent_stop_tool_loop_circuit_break`，由展示层按
 * [TOOL_LOOP_CIRCUIT_BREAK_CODE] 映射（领域层拿不到 Context，不能直接用 R.string）。
 */
internal fun toolLoopCircuitBreakText(toolName: String, repeatCount: Int): String =
    "检测到工具循环：$toolName 连续 $repeatCount 次以相同参数调用并返回相同结果，已自动终止本轮，避免继续空转。"

/** 孤儿工具调用的结果占位文案。 */
internal const val ORPHAN_TOOL_RESULT_TEXT = "(无结果：App 在这条调用完成前重启了)"

/**
 * 卡死探测：最近 [STUCK_WINDOW] 条 assistant 消息（文本 + 工具调用整体）完全相同即判定卡死。
 * 参考 nanomuse core.py 的 _is_stuck()，零额外模型调用，纯本地判定。
 */
internal fun detectStuck(messages: List<AgentMessage>): Boolean {
    val assistants = messages.filterIsInstance<AgentMessage.AssistantMessage>().takeLast(STUCK_WINDOW)
    if (assistants.size < STUCK_WINDOW) return false
    return assistants.map { it.stuckSignature() }.toSet().size == 1
}

/** 卡死提示只注入一次：历史里已存在同一句提示就不再注入。 */
private fun nudgedAlready(messages: List<AgentMessage>, stuckNudgeText: String): Boolean =
    messages.any { it is AgentMessage.UserMessage && it.content == stuckNudgeText }

/** 一条 assistant 消息的稳定签名：文本 + 每个工具调用的名字与（按键排序的）参数。 */
private fun AgentMessage.AssistantMessage.stuckSignature(): String = buildString {
    append(content)
    toolCalls.forEach { call ->
        append('\u0000').append(call.name)
        call.arguments.toSortedMap().forEach { (key, value) -> append('\u0001').append(key).append('=').append(value) }
    }
}

/**
 * 重启补孤儿 tool call：assistant 声明了 tool_calls 却缺少对应结果时（例如审批等待期间进程被杀），
 * 给每个孤儿补一条结果占位，避免 OpenAI / Anthropic 拒绝这类历史。
 * 参考 nanomuse core.py 的 _repair_dangling_tool_calls()，返回新列表、不改原对象；
 * 无需修补时原样返回入参（省一次分配）。
 */
internal fun repairDanglingToolCalls(messages: List<AgentMessage>): List<AgentMessage> {
    if (messages.isEmpty()) return messages
    val repaired = ArrayList<AgentMessage>(messages.size + 1)
    var index = 0
    while (index < messages.size) {
        val message = messages[index]
        repaired.add(message)
        index++
        if (message !is AgentMessage.AssistantMessage || message.toolCalls.isEmpty()) continue
        val answered = HashSet<String>()
        while (index < messages.size && messages[index] is AgentMessage.ToolResultMessage) {
            val result = messages[index] as AgentMessage.ToolResultMessage
            repaired.add(result)
            answered.add(result.id)
            index++
        }
        message.toolCalls.forEach { call ->
            if (call.id !in answered) {
                repaired.add(
                    AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = ORPHAN_TOOL_RESULT_TEXT)
                )
            }
        }
    }
    return if (repaired.size == messages.size) messages else repaired
}
