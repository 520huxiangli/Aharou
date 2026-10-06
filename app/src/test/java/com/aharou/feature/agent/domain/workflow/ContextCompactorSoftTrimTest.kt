package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompactorSoftTrimTest {
    private fun toolResult(result: String, id: String = "old", modelResult: String? = null) =
        AgentMessage.ToolResultMessage(id = id, toolName = "readFile", result = result, modelResult = modelResult)

    private fun batch(id: String) = AgentMessage.AssistantMessage(content = "",
        toolCalls = listOf(ToolCall(id, "readFile", emptyMap())))

    @Test
    fun trimsOlderCompletedOutputWithReadableReferenceAndBothEnds() {
        val big = "BEGIN" + "x".repeat(5_000) + "END"
        val messages = listOf(batch("old"), toolResult(big), batch("latest"), toolResult(big, "latest"))
        val trimmed = ContextCompactor.softTrimToolOutputs(messages) { "/root/.aharou/tool-output/original.txt" }
        val first = trimmed[1] as AgentMessage.ToolResultMessage
        assertTrue(first.modelResult!!.startsWith("BEGIN"))
        assertTrue(first.modelResult!!.contains("/root/.aharou/tool-output/original.txt"))
        assertTrue(first.modelResult!!.contains("END"))
        assertEquals(big, first.result)
        assertSame(messages[3], trimmed[3])
        assertNull((trimmed[3] as AgentMessage.ToolResultMessage).modelResult)
    }

    @Test
    fun latestParallelBatchIsNeverTrimmed() {
        val big = "x".repeat(5_000)
        val messages = listOf(AgentMessage.AssistantMessage(content = "", toolCalls = listOf(
            ToolCall("one", "readFile", emptyMap()), ToolCall("two", "readFile", emptyMap()))),
            toolResult(big, "one"), toolResult(big, "two"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages) { "saved.txt" })
    }

    @Test
    fun unfinishedEarlierBatchProtectsItsResultsEvenAfterANewerBatch() {
        val big = "x".repeat(5_000)
        val messages = listOf(AgentMessage.AssistantMessage(content = "", toolCalls = listOf(
            ToolCall("one", "readFile", emptyMap()), ToolCall("missing", "readFile", emptyMap()))),
            toolResult(big, "one"), batch("latest"), toolResult(big, "latest"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages) { "saved.txt" })
    }

    @Test
    fun archiveFailureKeepsOriginalOutput() {
        val messages = listOf(batch("old"), toolResult("x".repeat(5_000)), batch("latest"), toolResult("ok", "latest"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages) { null })
    }

    @Test
    fun existingModelProjectionAndRepeatedTrimStayUnchanged() {
        val big = "y".repeat(5_000)
        val messages = listOf(batch("old"), toolResult(big, modelResult = "already compact"),
            batch("latest"), toolResult("ok", "latest"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages) { "saved.txt" })
        val once = ContextCompactor.softTrimToolOutputs(listOf(batch("old"), toolResult(big),
            batch("latest"), toolResult("ok", "latest"))) { "saved.txt" }
        assertSame(once, ContextCompactor.softTrimToolOutputs(once) { "saved.txt" })
    }

    @Test
    fun noReadableReferenceAndShortOutputsAreUnchanged() {
        val messages = listOf(batch("old"), toolResult("x".repeat(5_000)), batch("latest"), toolResult("ok", "latest"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages))
        val short = listOf(toolResult("short"), AgentMessage.UserMessage(content = "hi"))
        assertSame(short, ContextCompactor.softTrimToolOutputs(short))
    }

    @Test
    fun existingStoredOutputPathSurvivesProjection() {
        val raw = "{\"data\":{\"content\":\"" + "x".repeat(5_000) +
            "\",\"output_path\":\"/root/.aharou/tool-output/old.txt\"}}"
        val messages = listOf(batch("old"), toolResult(raw), batch("latest"), toolResult("ok", "latest"))
        val trimmed = ContextCompactor.softTrimToolOutputs(messages)
        assertTrue((trimmed[1] as AgentMessage.ToolResultMessage).modelResult!!.contains("/root/.aharou/tool-output/old.txt"))
    }
}
