package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTrimmerTest {

    private fun user(text: String) = AgentMessage.UserMessage(content = text)
    private fun assistant(text: String) = AgentMessage.AssistantMessage(content = text)
    private fun toolCall(id: String) = assistant("").copy(toolCalls = listOf(ToolCall(id, "readFile", emptyMap())))
    private fun toolResult(id: String) = AgentMessage.ToolResultMessage(id = id, toolName = "readFile", result = "ok")

    private fun rounds(n: Int): List<AgentMessage> = buildList {
        repeat(n) { i ->
            add(user("u$i"))
            add(toolCall("c$i"))
            add(toolResult("c$i"))
            add(assistant("a$i"))
        }
    }

    @Test
    fun zeroRounds_keepsEverything() {
        val messages = rounds(3)
        assertEquals(messages, messages.trimToRecentRounds(0))
    }

    @Test
    fun fewerRoundsThanLimit_keepsEverything() {
        val messages = rounds(2)
        assertEquals(messages, messages.trimToRecentRounds(5))
    }

    @Test
    fun keepsOnlyLastNRounds() {
        val messages = rounds(4)
        val trimmed = messages.trimToRecentRounds(2)
        assertEquals(8, trimmed.size)
        assertTrue(trimmed.first() is AgentMessage.UserMessage)
        assertEquals("u2", (trimmed.first() as AgentMessage.UserMessage).content)
    }

    @Test
    fun neverSplitsToolCallFromItsResult() {
        val messages = rounds(3)
        val trimmed = messages.trimToRecentRounds(1)
        // 只剩最后一轮：user + toolCall + toolResult + assistant，工具对完整
        assertEquals(4, trimmed.size)
        assertTrue(trimmed[1] is AgentMessage.AssistantMessage)
        assertTrue((trimmed[1] as AgentMessage.AssistantMessage).toolCalls.isNotEmpty())
        assertEquals("c2", (trimmed[2] as AgentMessage.ToolResultMessage).id)
    }
}
