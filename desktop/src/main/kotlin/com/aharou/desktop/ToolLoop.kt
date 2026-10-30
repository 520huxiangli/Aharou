package com.aharou.desktop

import com.aharou.desktop.tool.DesktopToolbox
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

/** 一轮交互对外推送的事件。 */
sealed class TurnEvent {
    data class Text(val delta: String) : TurnEvent()
    data class ToolStart(val name: String, val args: String) : TurnEvent()
    data class ToolDone(val name: String, val ok: Boolean, val summary: String) : TurnEvent()
    data class Done(val assistant: AgentMessage.AssistantMessage) : TurnEvent()
    data class Failed(val message: String) : TurnEvent()
}

/**
 * 桌面端的工具执行循环。
 *
 * 手机端这套由 `StatefulAgentWorkflow` + `AgentTurnRunner` 承担（含权限审批、子代理、上下文压缩等
 * 一整套机制）。桌面端第一版只做最朴素的部分：模型要工具就执行、把结果喂回去、直到它不再要为止。
 * 工具全部在 [toolbox] 里，路径越界由 toolbox 自己拦。
 */
fun runAgentTurn(
    provider: AIProvider,
    systemPrompt: String,
    history: MutableList<AgentMessage>,
    toolbox: DesktopToolbox,
    approve: suspend (name: String, args: String) -> Boolean,
    maxRounds: Int = 8
): Flow<TurnEvent> = channelFlow {
    for (round in 0 until maxRounds) {
        val streamed = StringBuilder()
        var response: AIResponse? = null

        provider.completeStream(systemPrompt, history.toList(), toolbox.tools).collect { chunk ->
            when (chunk) {
                is AIStreamChunk.TextDelta -> {
                    streamed.append(chunk.text)
                    send(TurnEvent.Text(chunk.text))
                }
                is AIStreamChunk.Final -> response = chunk.response
                else -> Unit
            }
        }

        val result = response ?: run {
            send(TurnEvent.Failed("模型没有返回结果"))
            return@channelFlow
        }

        val assistant = AgentMessage.AssistantMessage(
            content = result.content.ifEmpty { streamed.toString() },
            toolCalls = result.toolCalls,
            reasoning = result.reasoning ?: "",
            signature = result.signature ?: "",
            thinkingBlocksJson = result.thinkingBlocksJson ?: "",
            inputTokens = result.inputTokens
        )
        history += assistant

        if (result.toolCalls.isEmpty()) {
            send(TurnEvent.Done(assistant))
            return@channelFlow
        }

        for (call in result.toolCalls) {
            send(TurnEvent.ToolStart(call.name, call.arguments.toString()))
            val tool = toolbox.find(call.name)
            val toolResult = if (tool == null) {
                ToolResult.Error("未知工具：${call.name}", "UNKNOWN_TOOL")
            } else {
                val allowed = tool.permissionPolicy != ToolPermissionPolicy.ASK ||
                    approve(call.name, call.arguments.toString())
                if (!allowed) {
                    ToolResult.Error("用户拒绝了这次调用", "DENIED")
                } else {
                    try {
                        tool.execute(call.arguments)
                    } catch (error: Exception) {
                        ToolResult.Error(error.message ?: "工具执行异常", "TOOL_CRASH")
                    }
                }
            }
            history += AgentMessage.ToolResultMessage(
                toolName = call.name,
                result = toolResult.toTransportString()
            )
            send(
                TurnEvent.ToolDone(
                    name = call.name,
                    ok = toolResult is ToolResult.Success,
                    summary = toolResult.toTransportString().take(160)
                )
            )
        }
    }
    send(TurnEvent.Failed("工具调用超过 $maxRounds 轮，已停下"))
}
