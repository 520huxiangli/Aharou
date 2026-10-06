package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentAction
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSessionState
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.AgentSideEffect
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.ToolBatchResult

/**
 * 核心 Reducer，接收旧状态与 Action，返回新状态以及触发的副作用列表 (纯函数)。
 *
 * 单独成文件的原因：StatefulAgentWorkflow.kt 已顶到架构门禁的单文件行数基线（棘轮只许降），
 * 而本次为压协程状态机体积又在其中拆出了若干函数。本函数不读写 workflow 的任何实例状态，
 * 抽到独立文件既保住基线，也省掉一层实例耦合。
 */
internal fun StatefulAgentWorkflow.reduce(
    state: AgentSessionState,
    action: AgentAction
): Pair<AgentSessionState, List<AgentSideEffect>> {
    var newState = state
    val effects = mutableListOf<AgentSideEffect>()

    when (action) {
        is AgentAction.InitRequest -> {
            newState = state.copy(messages = action.initialMessages)
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
                } else {
                    newState = newState.copy(isFinished = true)
                }
            } else {
                // 本批多个 tool_call：全部进入待权限队列，逐个弹窗收集批准；
                // 全部批准后才进入并行执行阶段（见 PermissionEvaluated / ToolBatchFinished）。
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
                    effects.add(AgentSideEffect.RequestPermission(toolCalls.first()))
                }
            }
        }
        is AgentAction.LlmError -> {
            newState = state.copy(isFinished = true, error = action.error, errorCode = action.reasonCode)
        }
        is AgentAction.PermissionEvaluated -> {
            if (action.approved) {
                // 批准：当前 toolCall 移入已批准集合；若还有待请求权限的则继续弹窗，否则开始并行执行。
                val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                val approved = newState.approvedToolCalls + action.toolCall
                newState = newState.copy(
                    pendingPermissionCalls = remaining,
                    approvedToolCalls = approved
                )
                if (remaining.isNotEmpty()) {
                    effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                } else {
                    effects.add(AgentSideEffect.ExecuteToolBatch(approved))
                }
            } else {
                if (action.errorCode == StatefulAgentWorkflow.USER_REJECTED_CODE) {
                    // 模型一次可能返回多个 tool_calls。用户拒绝批次中任意一个 → 整批取消：
                    // 按 batchToolCalls 原始顺序为所有调用补上 tool 响应（不重复不遗漏），
                    // 否则 assistant(toolCalls=N) 后只有部分 tool 消息，OpenAI 会报 400
                    // "insufficient tool messages following tool_calls"。
                    effects.add(AgentSideEffect.RejectToolBatch(state.batchToolCalls,
                        "用户拒绝了本轮工具调用，该调用未执行。", StatefulAgentWorkflow.USER_REJECTED_CODE, true, state.rejectedToolResults))
                    return newState to effects
                }
                // 策略/系统拒绝（如 PLAN 模式禁止执行）：记录拒绝结果，继续收集后续权限。
                val remaining = newState.pendingPermissionCalls.filterNot { it.id == action.toolCall.id }
                newState = newState.copy(
                    pendingPermissionCalls = remaining,
                    rejectedToolResults = newState.rejectedToolResults + (
                        action.toolCall.id to ToolBatchResult(
                            id = action.toolCall.id,
                            toolName = action.toolCall.name,
                            result = action.result,
                            isError = true
                        )
                    )
                )
                if (remaining.isNotEmpty()) {
                    effects.add(AgentSideEffect.RequestPermission(remaining.first()))
                } else {
                    effects.add(AgentSideEffect.ExecuteToolBatch(newState.approvedToolCalls))
                }
            }
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
            effects.add(AgentSideEffect.CallLlm)
        }
    }

    return Pair(newState, effects)
}
