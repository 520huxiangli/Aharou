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
                if (newState.totalToolCalls >= StatefulAgentWorkflow.MAX_TOTAL_TOOL_CALLS) {
                    // 到上限：不再执行，但要按 assistant(toolCalls) 的顺序补齐 tool 响应后收尾。
                    // 第一次触发先回一条提示让模型收口，再触发就直接结束——
                    // 否则提示本身又变成一轮模型调用，循环还是停不下来。
                    effects.add(AgentSideEffect.RejectToolBatch(toolCalls,
                        "本次任务的工具调用已达上限（${StatefulAgentWorkflow.MAX_TOTAL_TOOL_CALLS} 次），该调用未执行。请基于已有信息给出结论，或向用户说明还差什么。",
                        "TOOL_CALL_LIMIT", newState.toolLimitNotified))
                    newState = newState.copy(toolLimitNotified = true)
                } else {
                    newState = newState.copy(
                        batchToolCalls = toolCalls,
                        pendingPermissionCalls = toolCalls,
                        approvedToolCalls = emptyList(),
                        rejectedToolResults = emptyMap()
                    )
                    effects.add(AgentSideEffect.RequestPermissionBatch(toolCalls))
                }
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
            // 卡死探测（零额外模型调用）：最近 3 条 assistant 整体重复时注入一次提示让模型换招。
            // 只在历史里还没有同一句提示时注入，避免每批都插、也避免重启后重复。
            if (detectStuck(newState.messages) && !nudgedAlready(newState.messages, stuckNudgeText)) {
                newState = newState.copy(messages = newState.messages + AgentMessage.UserMessage(content = stuckNudgeText))
                FileLogger.i(StatefulAgentWorkflow.TAG, "检测到连续重复动作，注入卡死提示")
            }
            effects.add(AgentSideEffect.CallLlm)
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
