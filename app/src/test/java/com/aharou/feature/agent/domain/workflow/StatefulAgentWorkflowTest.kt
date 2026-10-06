package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.ToolOutputStore
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.hasSnapshotData
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow.Companion.persistenceConfirmed
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.ProviderType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class StatefulAgentWorkflowTest {
    private val registry = com.aharou.feature.agent.domain.tool.ToolRegistry()
    private val providers = mockk<AIProviderRepository>()
    private val prompts = mockk<SystemPromptProvider>()
    private val sessions = mockk<SessionUseCase>()
    private val persistence = mockk<MessagePersistenceUseCase>(relaxed = true)
    private val compactor = mockk<ContextCompactor>()
    private val metadata = mockk<ModelMetadataService>()
    private val compactionSettings = mockk<CompactionModelSettingsRepository>()
    private val outputStore = mockk<ToolOutputStore>()
    private val provider = mockk<AIProvider>(relaxed = true)
    private val config = AIProviderConfig("provider", "Provider", ProviderType.OPENAI, "test-key",
        baseUrl = "https://example.invalid", defaultModel = "model", models = listOf("model"))
    private val context = AgentContext(null, null, "/workspace", null, sessionId = "session")
    private val toolCall = ToolCall("call", "readFile", emptyMap())
    private val image = AgentImage("image/png", "image-data")
    private val attachment = AgentAttachment("image.png", "/image.png", "/local/image.png", "image/png", 1, true)
    private val response = AIResponse("final text", listOf(toolCall), reasoning = "final reasoning",
        signature = "signature", thinkingBlocksJson = "[blocks]", inputTokens = 100, outputTokens = 20,
        cachedInputTokens = 40, images = listOf(image))
    private val workflow = spyk(StatefulAgentWorkflow(
        toolRegistry = registry,
        aiProviderRepository = providers,
        openAIApi = mockk(),
        anthropicApi = mockk(),
        geminiApi = mockk(),
        promptProvider = prompts,
        permissionManager = mockk(),
        policyEngine = mockk<ToolPermissionPolicyEngine>(),
        contextCompactor = compactor,
        planApprovalManager = mockk(),
        toolOutputStore = outputStore,
        modelMetadataService = metadata,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = mockk(),
        generalSettingsRepository = mockk(),
        sessionUseCase = sessions,
        messagePersistenceUseCase = persistence,
        checkpointManager = mockk(),
        llmCallRecordDao = mockk<LlmCallRecordDao>(relaxed = true),
        keyRotator = mockk(),
        agentNotificationCenter = mockk(),
        eventInjector = mockk(),
        memoryCurator = mockk(),
        fileAccess = mockk(),
        ocrEngine = mockk()
    ), recordPrivateCalls = true)

    private fun prepare(chunks: Flow<AIStreamChunk>) {
        coEvery { sessions.getSessionById("session") } returns ChatSessionEntity("session", "Session", 0, 0,
            workspacePath = "/workspace", providerId = "provider", model = "model")
        coEvery { providers.getProviderById("provider") } returns config
        coEvery { workflow["createStandaloneProvider"](config, "session") } returns provider
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        every { provider.providerId } returns "provider"
        every { provider.model } returns "model"
        every { provider.completeStream(any(), any(), any(), any()) } returns chunks
        every { prompts.build(any()) } returns "system"
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("model", contextTokens = 100_000, supportsVision = true)
        coEvery { compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompactionResult(firstArg(), false)
        }
        coEvery { workflow["persistModelImages"](listOf(image)) } returns (listOf(image.copy(path = "/image.png")) to listOf(attachment))
    }

    private fun verifySaved(snapshotId: String? = null) {
        coVerify(exactly = 1) { persistence.persist("session", MessageRole.ASSISTANT, response.content,
            id = snapshotId ?: any(), toolCalls = response.toolCalls, reasoning = response.reasoning,
            signature = response.signature, thinkingBlocksJson = response.thinkingBlocksJson,
            attachments = listOf(attachment), inputTokens = response.inputTokens, outputTokens = response.outputTokens,
            cachedInputTokens = response.cachedInputTokens) }
    }

    @Test
    fun finalThenExceptionRetainsAllMetadataAndDoesNotRewriteConfirmedPersistence() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response)); error("stream failed after final") })
        val events = mutableListOf<AgentEvent>()
        workflow.executeEvents("request", context, emptyList()).collect {
            events.add(it)
            if (it is AgentEvent.AssistantText) it.persisted!!.complete(Unit)
        }
        val snapshot = events.filterIsInstance<AgentEvent.AssistantText>().single()
        assertEquals(response.content, snapshot.content)
        assertEquals(response.toolCalls, snapshot.toolCalls)
        assertEquals(response.reasoning, snapshot.reasoning)
        assertEquals(response.signature, snapshot.signature)
        assertEquals(response.thinkingBlocksJson, snapshot.thinkingBlocksJson)
        assertEquals(listOf(attachment), snapshot.attachments)
        assertEquals(100, snapshot.inputTokens)
        assertEquals(20, snapshot.outputTokens)
        assertEquals(40, snapshot.cachedInputTokens)
        assertTrue(events.any { it is AgentEvent.Failed })
        coVerify(exactly = 0) { persistence.persist(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun finalThenExceptionWithoutPersistenceAckFallsBackToCompleteSnapshot() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response)); error("stream failed after final") })
        var snapshot: AgentEvent.AssistantText? = null
        workflow.executeEvents("request", context, emptyList()).collect {
            if (it is AgentEvent.AssistantText) snapshot = it
        }
        verifySaved(requireNotNull(snapshot).messageId)
        assertTrue(requireNotNull(snapshot).persistenceConfirmed())
    }

    @Test
    fun cancellationWhileAwaitingPersistenceSavesCompleteSnapshot() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response)); error("stream failed after final") })
        val received = CompletableDeferred<AgentEvent.AssistantText>()
        val job = launch {
            workflow.executeEvents("request", context, emptyList()).collect {
                if (it is AgentEvent.AssistantText) received.complete(it)
            }
        }
        val snapshot = received.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        verifySaved(snapshot.messageId)
    }

    @Test
    fun cancellationDuringSendBackpressureSavesCompleteSnapshot() = runTest {
        val finalSeen = CompletableDeferred<Unit>()
        val blocked = CompletableDeferred<Unit>()
        prepare(flow { emit(AIStreamChunk.Final(response)); finalSeen.complete(Unit) })
        val job = launch {
            workflow.executeEvents("request", context, emptyList()).buffer(0).collect {
                if (it is AgentEvent.ContextUsage && finalSeen.isCompleted) {
                    blocked.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        blocked.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        verifySaved()
    }

    @Test
    fun providerCancellationAfterFinalIsRethrownAfterSavingCompleteSnapshot() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response)); throw CancellationException("cancelled") })
        try {
            workflow.executeEvents("request", context, emptyList()).collect {}
            fail("Cancellation must propagate")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
        verifySaved()
    }

    @Test
    fun normalCompletionDoesNotRewriteConfirmedPersistence() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response.copy(toolCalls = emptyList()))) })
        var snapshot: AgentEvent.AssistantText? = null
        workflow.executeEvents("request", context, emptyList()).collect {
            if (it is AgentEvent.AssistantText) {
                snapshot = it
                it.persisted!!.complete(Unit)
            }
        }
        assertTrue(requireNotNull(snapshot).persistenceConfirmed())
        coVerify(exactly = 0) { persistence.persist(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun partialStreamFailurePreservesTextAndReasoning() = runTest {
        prepare(flow {
            emit(AIStreamChunk.TextDelta("partial text"))
            emit(AIStreamChunk.ReasoningDelta("partial reasoning"))
            error("stream failed")
        })
        var snapshot: AgentEvent.AssistantText? = null
        workflow.executeEvents("request", context, emptyList()).collect {
            if (it is AgentEvent.AssistantText) snapshot = it
        }
        coVerify(exactly = 1) { persistence.persist("session", MessageRole.ASSISTANT, "partial text",
            id = requireNotNull(snapshot).messageId, reasoning = "partial reasoning", toolCalls = emptyList(),
            signature = "", thinkingBlocksJson = "", attachments = emptyList(), inputTokens = 0,
            outputTokens = 0, cachedInputTokens = 0) }
    }

    @Test
    fun signatureBlocksAndUsageAloneAreNotDiscarded() {
        assertTrue(AgentEvent.AssistantText("", signature = "signature").hasSnapshotData())
        assertTrue(AgentEvent.AssistantText("", thinkingBlocksJson = "[redacted]").hasSnapshotData())
        assertTrue(AgentEvent.AssistantText("", cachedInputTokens = 1).hasSnapshotData())
        assertFalse(AgentEvent.AssistantText("").hasSnapshotData())
    }

    @Test
    fun failedAcknowledgementIsNotPersistenceConfirmation() {
        val ack = CompletableDeferred<Unit>()
        val snapshot = AgentEvent.AssistantText("response", persisted = ack)
        assertFalse(snapshot.persistenceConfirmed())
        ack.completeExceptionally(IllegalStateException("storage failed"))
        assertFalse(snapshot.persistenceConfirmed())
    }

    @Test
    fun successfulAndCancelledAcknowledgementsAreDistinguished() {
        val confirmed = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val cancelled = CompletableDeferred<Unit>().also { it.cancel() }
        assertTrue(AgentEvent.AssistantText("response", persisted = confirmed).persistenceConfirmed())
        assertFalse(AgentEvent.AssistantText("response", persisted = cancelled).persistenceConfirmed())
        assertFalse(AgentEvent.AssistantText("response").persistenceConfirmed())
    }

    @Test
    fun toolErrorIsProcessedBeforeReturningTransportText() {
        val processed = ToolResult.Error("bounded", "BOUNDED")
        every { outputStore.process(toolCall.name, toolCall.id, any()) } returns processed
        val method = StatefulAgentWorkflow::class.java.getDeclaredMethod("toolError", ToolCall::class.java,
            String::class.java, String::class.java)
        method.isAccessible = true
        assertEquals(processed.toTransportString(), method.invoke(workflow, toolCall, "original", "ORIGINAL"))
        verify(exactly = 1) { outputStore.process(toolCall.name, toolCall.id, ToolResult.Error("original", "ORIGINAL")) }
    }

    @Test
    fun toolErrorDoesNotSwallowCancellationFromOutputProcessing() {
        every { outputStore.process(any(), any(), any()) } throws CancellationException("cancelled")
        val method = StatefulAgentWorkflow::class.java.getDeclaredMethod("toolError", ToolCall::class.java,
            String::class.java, String::class.java)
        method.isAccessible = true
        try {
            method.invoke(workflow, toolCall, "original", "ORIGINAL")
            fail("Cancellation must propagate")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is CancellationException)
        }
    }

    @Test
    fun toolLimitProducesSideEffectWithoutProcessingOutputInReducer() {
        val state = StatefulAgentWorkflow.AgentSessionState(totalToolCalls = 200)
        val action = StatefulAgentWorkflow.AgentAction.LlmResponse(response, "message")
        val method = StatefulAgentWorkflow::class.java.getDeclaredMethod("reduce",
            StatefulAgentWorkflow.AgentSessionState::class.java, StatefulAgentWorkflow.AgentAction::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, state, action) as Pair<*, *>
        assertTrue((result.first as StatefulAgentWorkflow.AgentSessionState).toolLimitNotified)
        assertEquals("RejectToolBatch", (result.second as List<*>).single()!!.javaClass.simpleName)
        verify(exactly = 0) { outputStore.process(any(), any(), any()) }
    }

    @Test
    fun userRejectionProducesSideEffectWithoutProcessingOutputInReducer() {
        val state = StatefulAgentWorkflow.AgentSessionState(batchToolCalls = listOf(toolCall),
            pendingPermissionCalls = listOf(toolCall))
        val action = StatefulAgentWorkflow.AgentAction.PermissionEvaluated(toolCall, false, "args")
        val method = StatefulAgentWorkflow::class.java.getDeclaredMethod("reduce",
            StatefulAgentWorkflow.AgentSessionState::class.java, StatefulAgentWorkflow.AgentAction::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, state, action) as Pair<*, *>
        assertEquals("RejectToolBatch", (result.second as List<*>).single()!!.javaClass.simpleName)
        verify(exactly = 0) { outputStore.process(any(), any(), any()) }
    }

    @Test
    fun reducerUsesProcessedDenialWithoutTouchingOutputStore() {
        val denied = ToolResult.Error("bounded", "TOOL_NOT_ALLOWED").toTransportString()
        val state = StatefulAgentWorkflow.AgentSessionState(batchToolCalls = listOf(toolCall),
            pendingPermissionCalls = listOf(toolCall))
        val action = StatefulAgentWorkflow.AgentAction.PermissionEvaluated(toolCall, false, "args",
            "unbounded", "TOOL_NOT_ALLOWED", denied)
        val method = StatefulAgentWorkflow::class.java.getDeclaredMethod("reduce",
            StatefulAgentWorkflow.AgentSessionState::class.java, StatefulAgentWorkflow.AgentAction::class.java)
        method.isAccessible = true
        val result = method.invoke(workflow, state, action) as Pair<*, *>
        val next = result.first as StatefulAgentWorkflow.AgentSessionState
        assertEquals(denied, next.rejectedToolResults.getValue(toolCall.id).result)
        verify(exactly = 0) { outputStore.process(any(), any(), any()) }
    }

    @Test
    fun manualCompactionWithoutDefinitionStillExcludesNestedTask() = runTest {
        verifyManualToolFiltering(parentId = "parent", expectedNames = listOf("readFile", "messageParent"))
    }

    @Test
    fun manualCompactionForMainSessionExcludesParentMessaging() = runTest {
        verifyManualToolFiltering(parentId = null, expectedNames = listOf("readFile", "task"))
    }

    private suspend fun verifyManualToolFiltering(parentId: String?, expectedNames: List<String>) {
        prepare(flow {})
        val allTools = listOf("readFile", "task", "messageParent").map { toolName ->
            mockk<AgentTool> { every { name } returns toolName }.also { registry.register(toolName, it) }
        }
        coEvery { sessions.getSessionById("session") } returns ChatSessionEntity("session", "Session", 0, 0,
            workspacePath = "/workspace", providerId = "provider", model = "model", parentId = parentId)
        coEvery { persistence.buildHistory("session", "__manual_compress__") } returns listOf(
            AgentMessage.UserMessage(content = "one"), AgentMessage.AssistantMessage(content = "two"),
            AgentMessage.UserMessage(content = "three"))
        val expectedTools = allTools.filter { it.name in expectedNames }
        coEvery { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "system", expectedTools, 0, any()) } answers { CompactionResult(firstArg(), true) }
        assertTrue(workflow.compactSession("session") {})
        coVerify(exactly = 1) { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "system", expectedTools, 0, any()) }
    }

    @Test
    fun manualCompactionRestoresDisabledSubagentDefinitionModeAndFilteredTools() = runTest {
        prepare(flow {})
        val definition = mockk<AgentDefinition>()
        val readTool = mockk<AgentTool> { every { name } returns "readFile" }
        val taskTool = mockk<AgentTool> { every { name } returns "task" }
        registry.register("readFile", readTool)
        registry.register("task", taskTool)
        coEvery { sessions.getSessionById("session") } returns ChatSessionEntity("session", "Session", 0, 0,
            workspacePath = "/workspace", providerId = "provider", model = "model", mode = "AUTO",
            parentId = "parent", subagentType = "disabled-agent")
        every { prompts.findAgentDefinitionIncludingDisabled("disabled-agent") } returns definition
        every { definition.filterToolNames(listOf("readFile", "task")) } returns listOf("readFile")
        coEvery { persistence.buildHistory("session", "__manual_compress__") } returns listOf(
            AgentMessage.UserMessage(content = "one"), AgentMessage.AssistantMessage(content = "two"),
            AgentMessage.UserMessage(content = "three"))
        val promptContext = slot<AgentContext>()
        every { prompts.build(capture(promptContext)) } returns "subagent system"
        coEvery { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "subagent system", listOf(readTool), 0, any()) } answers { CompactionResult(firstArg(), true) }
        assertTrue(workflow.compactSession("session") {})
        assertEquals(AgentMode.AUTO, promptContext.captured.mode)
        assertEquals("/workspace", promptContext.captured.projectRoot)
        assertSame(definition, promptContext.captured.agentDefinition)
        coVerify(exactly = 1) { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "subagent system", listOf(readTool), 0, any()) }
    }
}
