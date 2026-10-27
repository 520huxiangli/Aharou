package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolCall
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 工具循环判重与三级熔断（[toolLoopVerdict]）。
 *
 * 判定键是「工具名 + 参数 + 结果哈希」：只看调用本身会把「反复查同一个命令但结果在变」误判成死循环，
 * 所以这里的核心用例是「结果变了要重新计数」。
 */
class ToolLoopVerdictTest {

    private fun repeatCalls(
        count: Int,
        toolName: String = "Bash",
        command: String = "ls",
        result: String = "ok",
        idPrefix: String = "call"
    ): List<AgentMessage> {
        val messages = mutableListOf<AgentMessage>()
        repeat(count) { index ->
            val callId = "$idPrefix-$index"
            messages += AgentMessage.AssistantMessage(
                content = "",
                toolCalls = listOf(
                    ToolCall(
                        id = callId,
                        name = toolName,
                        arguments = mapOf("command" to JsonPrimitive(command))
                    )
                )
            )
            messages += AgentMessage.ToolResultMessage(
                id = callId,
                toolName = toolName,
                result = result
            )
        }
        return messages
    }

    @Test
    fun empty_history_has_no_verdict() {
        assertEquals(ToolLoopLevel.NONE, toolLoopVerdict(emptyList()).level)
    }

    @Test
    fun single_call_is_not_a_loop() {
        assertEquals(ToolLoopLevel.NONE, toolLoopVerdict(repeatCalls(1)).level)
    }

    @Test
    fun below_warning_threshold_stays_silent() {
        val verdict = toolLoopVerdict(repeatCalls(ToolLoopThresholds.WARNING - 1))
        assertEquals(ToolLoopLevel.NONE, verdict.level)
        assertEquals(ToolLoopThresholds.WARNING - 1, verdict.repeatCount)
    }

    @Test
    fun reaches_each_threshold_in_order() {
        assertEquals(ToolLoopLevel.WARNING, toolLoopVerdict(repeatCalls(ToolLoopThresholds.WARNING)).level)
        assertEquals(ToolLoopLevel.CRITICAL, toolLoopVerdict(repeatCalls(ToolLoopThresholds.CRITICAL)).level)
        assertEquals(
            ToolLoopLevel.CIRCUIT_BREAK,
            toolLoopVerdict(repeatCalls(ToolLoopThresholds.CIRCUIT_BREAK)).level
        )
    }

    @Test
    fun changed_result_breaks_the_loop_chain() {
        // 前 29 次同结果，最后一次结果变了：说明拿到了新信息，不该判成循环
        val messages = repeatCalls(ToolLoopThresholds.CIRCUIT_BREAK - 1).toMutableList()
        val callId = "call-last"
        messages += AgentMessage.AssistantMessage(
            content = "",
            toolCalls = listOf(
                ToolCall(callId, "Bash", mapOf("command" to JsonPrimitive("ls")))
            )
        )
        messages += AgentMessage.ToolResultMessage(
            id = callId,
            toolName = "Bash",
            result = "different output"
        )
        val verdict = toolLoopVerdict(messages)
        assertEquals(1, verdict.repeatCount)
        assertEquals(ToolLoopLevel.NONE, verdict.level)
    }

    @Test
    fun different_arguments_are_counted_separately() {
        val messages = repeatCalls(ToolLoopThresholds.WARNING, command = "ls", idPrefix = "a") +
            repeatCalls(ToolLoopThresholds.CRITICAL, command = "pwd", idPrefix = "b")
        // 最新一条是 pwd，只数 pwd 的连续重复
        assertEquals(ToolLoopThresholds.CRITICAL, toolLoopVerdict(messages).repeatCount)
    }

    @Test
    fun wait_poll_tools_never_circuit_break() {
        // 轮询类工具（等任务/等终端）反复查询是正常行为，只提醒不熔断
        val verdict = toolLoopVerdict(repeatCalls(ToolLoopThresholds.CIRCUIT_BREAK + 5, toolName = "terminal"))
        assertEquals(ToolLoopLevel.CRITICAL, verdict.level)
        assertEquals("terminal", verdict.toolName)
    }

    @Test
    fun user_message_resets_the_window() {
        val messages = repeatCalls(ToolLoopThresholds.CRITICAL, idPrefix = "old") +
            AgentMessage.UserMessage(content = "继续") +
            repeatCalls(3, idPrefix = "new")
        // 新一轮任务只算最后一次用户消息之后的记录
        val verdict = toolLoopVerdict(messages)
        assertEquals(ToolLoopLevel.NONE, verdict.level)
        assertEquals(3, verdict.repeatCount)
    }
}
