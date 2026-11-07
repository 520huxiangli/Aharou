package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.memory.MemoryRepository
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ToolCallGuardTest {
    private val receipts = mockk<ToolCallReceiptRepository>(relaxed = true)
    private val guard = ToolCallGuard(mockk<MemoryRepository>(relaxed = true), receipts)
    private val context = AgentContext(currentFile = null, selectedCode = null, projectRoot = "", language = null, sessionId = "session")

    private fun failing(error: Throwable) = object : AgentTool() {
        override val name = "fixture"
        override val description = "fixture"
        override val parameters = emptyMap<String, ToolParameter>()
        override suspend fun execute(args: Map<String, JsonElement>): ToolResult = throw error
    }

    @Test fun thrownErrorIsPreservedAndRecordedUnknown() = runTest {
        coEvery { receipts.beginExecution(any(), any(), any()) } returns 9L
        val error = IllegalStateException("fixture")
        try { guard.wrap(failing(error)).executeWithContext(emptyMap(), context) }
        catch (actual: Throwable) { assertSame(error, actual) }
        coVerify { receipts.finishExecution(9L, "fixture", any(), null, any(), any(), false) }
    }

    @Test fun cancellationIsPreservedAndRecordedInterrupted() = runTest {
        coEvery { receipts.beginExecution(any(), any(), any()) } returns 9L
        val error = CancellationException("fixture")
        try { guard.wrap(failing(error)).executeWithContext(emptyMap(), context) }
        catch (actual: Throwable) { assertSame(error, actual) }
        coVerify { receipts.finishExecution(9L, "fixture", any(), null, any(), any(), true) }
    }

    @Test fun streamWithoutCompletedRecordsUnknownOnce() = runTest {
        coEvery { receipts.beginExecution(any(), any(), any()) } returns 9L
        val tool = object : AgentTool(), StreamingAgentTool {
            override val name = "fixture"
            override val description = "fixture"
            override val parameters = emptyMap<String, ToolParameter>()
            override suspend fun execute(args: Map<String, JsonElement>) = ToolResult.Success(JsonPrimitive("ok"))
            override fun executeStream(args: Map<String, JsonElement>, context: AgentContext): Flow<ToolStreamEvent> = flow {}
        }
        val values = (guard.wrap(tool) as StreamingAgentTool).executeStream(emptyMap(), context).toList()
        assertEquals(0, values.size)
        coVerify(exactly = 1) { receipts.finishExecution(9L, "fixture", any(), null, any(), any(), false) }
    }
}
