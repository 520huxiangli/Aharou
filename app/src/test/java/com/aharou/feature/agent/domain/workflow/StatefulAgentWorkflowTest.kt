package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.memory.MemoryCompactor
import com.aharou.feature.agent.domain.model.AgentContext
import dagger.Lazy
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.receipt.ToolCallReceiptRepository
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.provider.ProviderFailoverCoordinator
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.ToolCallIdempotencyStore
import com.aharou.feature.agent.domain.tool.ToolOutputStore
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import com.aharou.feature.agent.domain.workflow.GeneratedImagePersister
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
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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
    // 授权策略：工具批用例要显式放行，否则 PermissionGate 会按 ASK 走弹窗路径（本类没有真实审批环境）。
    private val policyEngine = mockk<ToolPermissionPolicyEngine>()
    // 规则提醒：这些用例不验证提醒，relaxed mock 下所有方法不抛异常（decorate 在 init 里打桩成原样返回）。
    private val reminderEngine = mockk<com.aharou.feature.agent.domain.reminder.ReminderEngine>(relaxed = true)
    private val provider = mockk<AIProvider>(relaxed = true)
    // 直出图片落盘已抽成独立类；这些用例只关心「图片被换成路径」，行为用 stub 保留
    private val imagePersister = mockk<GeneratedImagePersister>(relaxed = true)
    // 回退协调器：用例不覆盖跨供应商回退，relaxed 下 isActive=false、activate 返回 null
    private val failoverCoordinator = mockk<ProviderFailoverCoordinator>(relaxed = true)
    private val notifications = mockk<AgentNotificationCenter>()
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
        policyEngine = policyEngine,
        contextCompactor = compactor,
        planApprovalManager = mockk(),
        toolOutputStore = outputStore,
        modelMetadataService = metadata,
        compactionModelSettingsRepository = compactionSettings,
        titleModelSettingsRepository = mockk(),
        defaultModelSettingsRepository = mockk(),
        generalSettingsRepository = mockk(relaxed = true),
        sessionUseCase = sessions,
        messagePersistenceUseCase = persistence,
        checkpointManager = mockk(),
        llmCallRecordDao = mockk<LlmCallRecordDao>(relaxed = true),
        keyRotator = mockk(),
        generatedImagePersister = imagePersister,
        failoverCoordinator = failoverCoordinator,
        agentNotificationCenter = notifications,
        eventInjector = mockk(),
        memoryCurator = mockk(),
        fileAccess = mockk(),
        // 回执是旁路记录，这些用例不断言它，relaxed mock 兜住全部写入路径。
        receiptRepository = mockk(relaxed = true),
        ocrEngine = mockk(),
        // vault 在结果脱敏与占位符把关路径上会被调到。用 relaxed mock 兜住全部方法（未打桩的
        // hasPlaceholders 默认即 false），只把 redact 显式打桩成原样返回 —— 等价于空库的真实行为。
        vault = mockk(relaxed = true) {
            every { redact(any()) } answers { firstArg<String>() }
        },
        // 规则提醒：保持工具结果不变，与未接提醒时一致。
        reminderEngine = reminderEngine,
        // 协作开关：这些用例不碰跨设备协作，relaxed mock 下 current().enabled 为 false（等价关闭）。
        remoteDeviceSettings = mockk(relaxed = true),
        // 记忆整理入口：这些用例不会走到自动整理，Lazy 里放个 relaxed mock 即可（注入形式是 dagger.Lazy 为了断环）。
        memoryCompactor = Lazy { mockk<MemoryCompactor>(relaxed = true) },
        // 幂等表无依赖、纯内存，直接给真实例：这些用例不覆盖重复调用回放。
        idempotencyStore = ToolCallIdempotencyStore(),
        // 服务档位：这些用例不覆盖档位注入，relaxed mock 下 apiValueFor 返回 null（等价不发送）。
        modelServiceTierRepository = mockk(relaxed = true)
    ), recordPrivateCalls = true)

    init {
        coEvery { reminderEngine.decorate(any(), any(), any(), any()) } answers {
            arg<com.aharou.feature.agent.domain.tool.ToolResult>(2).toTransportString()
        }
        coEvery { reminderEngine.turnEndReminder(any(), any(), any()) } returns null
        // relaxed mock 对「返回对象」的可空方法会给个空壳配置，导致 getEffectiveProvider 认为没填 Key；
        // 这些用例不覆盖跨供应商回退，显式打成未激活。
        coEvery { failoverCoordinator.overrideConfig(any()) } returns null
        coEvery { failoverCoordinator.activate(any(), any()) } returns null
        every { failoverCoordinator.isActive(any()) } returns false
    }

    private fun prepare(chunks: Flow<AIStreamChunk>) {
        coEvery { sessions.getSessionById("session") } returns ChatSessionEntity("session", "Session", 0, 0,
            workspacePath = "/workspace", providerId = "provider", model = "model")
        coEvery { providers.getProviderById("provider") } returns config
        coEvery { workflow["createStandaloneProvider"](config, "session") } returns provider
        coEvery { compactionSettings.getCompactionProviderId() } returns ""
        every { provider.providerId } returns "provider"
        every { provider.model } returns "model"
        every { provider.completeStream(any(), any(), any(), any()) } returns chunks
        every { prompts.build(any(), any()) } returns "system"
        // 卡死提示改为文件驱动后，workflow 首次用到时会 resolvePrompt；不打桩会抛 MockKException。
        every { prompts.resolvePrompt(any()) } returns "（测试）你在重复同一个动作"
        coEvery { metadata.resolve(any(), any(), any()) } returns ModelMetadata("model", contextTokens = 100_000, supportsVision = true)
        coEvery { compactor.compactIfNeeded(any(), any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            CompactionResult(firstArg(), false)
        }
        coEvery { imagePersister.persist(listOf(image)) } returns (listOf(image.copy(path = "/image.png")) to listOf(attachment))
        // 软打断每收一个流块都会查一次通知中心；严格 mock 不给 stub 会直接抛 MockKException。
        every { notifications.peek(any()) } returns emptyList()
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

    /**
     * 回归：取消展开时落库与失败日志同时抛异常（实测在堆将满时会触发），异常绝不能逃出 finally。
     * 逃出就会与待传播的取消异常叠加，ART 直接 AssertNoPendingException 中止进程。
     */
    @Test
    fun persistenceAndLogFailureDuringCancellationNeverEscapeTheUnwind() = runTest {
        prepare(flow { emit(AIStreamChunk.Final(response)); error("stream failed after final") })
        coEvery { persistence.persist(any(), any(), any(), any(), any(), any(), any(), any(), any(),
            any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws OutOfMemoryError("heap exhausted")
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any()) } throws OutOfMemoryError("log failed")
        try {
            val received = CompletableDeferred<AgentEvent.AssistantText>()
            val job = launch {
                workflow.executeEvents("request", context, emptyList()).collect {
                    if (it is AgentEvent.AssistantText) received.complete(it)
                }
            }
            received.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally {
            unmockkObject(FileLogger)
        }
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
    fun userRejectionProducesSideEffectWithoutProcessingOutputInReducer() {
        val state = StatefulAgentWorkflow.AgentSessionState(batchToolCalls = listOf(toolCall),
            pendingPermissionCalls = listOf(toolCall))
        val action = StatefulAgentWorkflow.AgentAction.PermissionBatchEvaluated(emptyList(), emptyMap(), userRejected = true)
        val result = workflow.reduce(state, action)
        assertEquals("RejectToolBatch", (result.second as List<*>).single()!!.javaClass.simpleName)
        verify(exactly = 0) { outputStore.process(any(), any(), any()) }
    }

    @Test
    fun reducerUsesProcessedDenialWithoutTouchingOutputStore() {
        val denied = ToolResult.Error("bounded", "TOOL_NOT_ALLOWED").toTransportString()
        val state = StatefulAgentWorkflow.AgentSessionState(batchToolCalls = listOf(toolCall),
            pendingPermissionCalls = listOf(toolCall))
        val action = StatefulAgentWorkflow.AgentAction.PermissionBatchEvaluated(
            approved = emptyList(),
            rejectedResults = mapOf(toolCall.id to StatefulAgentWorkflow.ToolBatchResult(toolCall.id, toolCall.name, denied, true))
        )
        val result = workflow.reduce(state, action)
        val next = result.first as StatefulAgentWorkflow.AgentSessionState
        assertEquals(denied, next.rejectedToolResults.getValue(toolCall.id).result)
        verify(exactly = 0) { outputStore.process(any(), any(), any()) }
    }

    @Test
    fun manualCompactionWithoutDefinitionIncludesNestedTask() = runTest {
        verifyManualToolFiltering(parentId = "parent", expectedNames = listOf("readFile", "task", "messageParent"))
    }

    @Test
    fun manualCompactionForMainSessionExcludesParentMessaging() = runTest {
        verifyManualToolFiltering(parentId = null, expectedNames = listOf("readFile", "task"))
    }

    private suspend fun verifyManualToolFiltering(parentId: String?, expectedNames: List<String>) {
        prepare(flow {})
        val allTools = listOf("readFile", "task", "messageParent").map { toolName ->
            mockk<AgentTool> {
                every { name } returns toolName
                every { deferredLoading } returns false
            }.also { registry.register(toolName, it) }
        }
        coEvery { sessions.getSessionById("session") } returns ChatSessionEntity("session", "Session", 0, 0,
            workspacePath = "/workspace", providerId = "provider", model = "model", parentId = parentId)
        coEvery { persistence.buildHistory("session", "__manual_compress__") } returns listOf(
            AgentMessage.UserMessage(content = "one"), AgentMessage.AssistantMessage(content = "two"),
            AgentMessage.UserMessage(content = "three"))
        // ToolRegistry.getAvailableTools() 按名字排序返回（工具列表是前缀缓存最靠前的一段，顺序必须稳定），
        // 所以这里不能按注册顺序断言。
        val expectedTools = allTools.filter { it.name in expectedNames }.sortedBy { it.name }
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
        val readTool = mockk<AgentTool> {
            every { name } returns "readFile"
            every { deferredLoading } returns false
        }
        val taskTool = mockk<AgentTool> {
            every { name } returns "task"
            every { deferredLoading } returns false
        }
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
        every { prompts.build(capture(promptContext), any()) } returns "subagent system"
        coEvery { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "subagent system", listOf(readTool), 0, any()) } answers { CompactionResult(firstArg(), true) }
        assertTrue(workflow.compactSession("session") {})
        assertEquals(AgentMode.AUTO, promptContext.captured.mode)
        assertEquals("/workspace", promptContext.captured.projectRoot)
        assertSame(definition, promptContext.captured.agentDefinition)
        coVerify(exactly = 1) { compactor.compactIfNeeded(any(), provider, "session", true, provider,
            "subagent system", listOf(readTool), 0, any()) }
    }

    // ───────────────────────── 批内去重（executeToolBatchEffect） ─────────────────────────

    /**
     * 批内去重：同一响应里两条同名同参调用只执行第一次，第二条补 DUPLICATE_TOOL_CALL 错误。
     * 两条结果都必须按 toolCalls 原顺序回填——tool 消息条数必须与 assistant(toolCalls) 对齐，否则 API 报 400。
     */
    @Test
    fun duplicateToolCallsInOneBatchExecuteTheFirstOnlyAndKeepResultOrder() = runTest {
        val tool = FakeEchoTool()
        val turn = runToolBatchTurn(tool, listOf(
            ToolCall("dup-1", "echo", mapOf("text" to JsonPrimitive("same"))),
            ToolCall("dup-2", "echo", mapOf("text" to JsonPrimitive("same")))
        ))

        assertEquals("同名同参只应执行第一次", 1, tool.executions.size)
        assertEquals(listOf("dup-1", "dup-2"), turn.finished.map { it.id })
        assertFalse("第一条是真实执行结果", turn.finished[0].isError)
        assertTrue("第二条应带重复调用错误码", turn.finished[1].result.contains("DUPLICATE_TOOL_CALL"))
        assertTrue(turn.finished[1].isError)

        val toolMessages = turn.secondRoundMessages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals("tool 消息条数必须等于 toolCalls 条数", 2, toolMessages.size)
        assertEquals(listOf("dup-1", "dup-2"), toolMessages.map { it.id })
        assertTrue(toolMessages[1].result.contains("DUPLICATE_TOOL_CALL"))
    }

    /** 参数不同就是两个不同调用，去重不能把它们合并。 */
    @Test
    fun sameBatchWithDifferentArgumentsExecutesEveryCall() = runTest {
        val tool = FakeEchoTool()
        val turn = runToolBatchTurn(tool, listOf(
            ToolCall("arg-1", "echo", mapOf("text" to JsonPrimitive("one"))),
            ToolCall("arg-2", "echo", mapOf("text" to JsonPrimitive("two")))
        ))

        assertEquals("参数不同应为两次真调用", 2, tool.executions.size)
        assertEquals(listOf("arg-1", "arg-2"), turn.finished.map { it.id })
        assertFalse(turn.finished.any { it.isError })

        val toolMessages = turn.secondRoundMessages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(2, toolMessages.size)
        assertFalse(toolMessages.any { it.result.contains("DUPLICATE_TOOL_CALL") })
    }

    // ───────────────────── Reducer：ToolBatchFinished 结果回填 ─────────────────────

    /**
     * 回填必须按 batchToolCalls 原顺序，而不是执行结果返回的顺序：
     * assistant(toolCalls) 与后续 tool 消息一旦错位，OpenAI 会直接 400。
     */
    @Test
    fun toolBatchFinishedBackfillsEveryResultInBatchOrder() {
        val first = ToolCall("call-1", "readFile", emptyMap())
        val second = ToolCall("call-2", "grep", emptyMap())
        val third = ToolCall("call-3", "list", emptyMap())
        val state = StatefulAgentWorkflow.AgentSessionState(
            messages = listOf(AgentMessage.UserMessage(content = "hi")),
            batchToolCalls = listOf(first, second, third),
            pendingPermissionCalls = listOf(first, second, third),
            approvedToolCalls = listOf(first, second, third)
        )
        val shuffledResults = listOf(
            StatefulAgentWorkflow.ToolBatchResult("call-3", "list", "third", false),
            StatefulAgentWorkflow.ToolBatchResult("call-1", "readFile", "first", false),
            StatefulAgentWorkflow.ToolBatchResult("call-2", "grep", "second", false)
        )

        val (next, effects) = workflow.reduce(
            state, StatefulAgentWorkflow.AgentAction.ToolBatchFinished(shuffledResults)
        )

        val toolMessages = next.messages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(3, toolMessages.size)
        assertEquals(listOf("call-1", "call-2", "call-3"), toolMessages.map { it.id })
        assertEquals(listOf("first", "second", "third"), toolMessages.map { it.result })
        assertEquals(4, next.messages.size)
        assertTrue("批次结束后待办状态必须清空", next.batchToolCalls.isEmpty())
        assertTrue(next.pendingPermissionCalls.isEmpty())
        assertTrue(next.approvedToolCalls.isEmpty())
        assertTrue(next.rejectedToolResults.isEmpty())
        assertEquals(3, next.totalToolCalls)
        assertFalse(next.isFinished)
        assertEquals(
            "成功批次要继续请求下一轮",
            listOf(StatefulAgentWorkflow.AgentSideEffect.CallLlm), effects
        )
    }

    /** 策略拒绝项没有执行结果，回填时优先用拒绝结果（真实原因不能被执行结果覆盖）。 */
    @Test
    fun toolBatchFinishedPrefersPolicyRejectionOverExecutionResult() {
        val allowed = ToolCall("call-1", "readFile", emptyMap())
        val denied = ToolCall("call-2", "grep", emptyMap())
        val rejection = StatefulAgentWorkflow.ToolBatchResult("call-2", "grep", "被项目安全规则拒绝", true)
        val state = StatefulAgentWorkflow.AgentSessionState(
            batchToolCalls = listOf(allowed, denied),
            rejectedToolResults = mapOf(denied.id to rejection)
        )

        val (next, _) = workflow.reduce(
            state,
            StatefulAgentWorkflow.AgentAction.ToolBatchFinished(listOf(
                StatefulAgentWorkflow.ToolBatchResult("call-1", "readFile", "ok", false),
                StatefulAgentWorkflow.ToolBatchResult("call-2", "grep", "本不该被执行", false)
            ))
        )

        val toolMessages = next.messages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(listOf("call-1", "call-2"), toolMessages.map { it.id })
        assertEquals("ok", toolMessages[0].result)
        assertEquals("被项目安全规则拒绝", toolMessages[1].result)
    }

    /** 单个工具报错不是本轮终止条件：结果照常回填、批次状态照常清空，循环继续问下一轮。 */
    @Test
    fun toolBatchFinishedWithErrorResultKeepsLoopRunning() {
        val call = ToolCall("call-1", "readFile", emptyMap())
        val state = StatefulAgentWorkflow.AgentSessionState(batchToolCalls = listOf(call))

        val (next, effects) = workflow.reduce(
            state,
            StatefulAgentWorkflow.AgentAction.ToolBatchFinished(listOf(
                StatefulAgentWorkflow.ToolBatchResult("call-1", "readFile", "工具执行失败", true)
            ))
        )

        assertFalse("工具报错不应直接结束本轮", next.isFinished)
        assertNull(next.error)
        assertNull(next.errorCode)
        assertTrue(next.batchToolCalls.isEmpty())
        val toolMessages = next.messages.filterIsInstance<AgentMessage.ToolResultMessage>()
        assertEquals(1, toolMessages.size)
        assertEquals("工具执行失败", toolMessages.single().result)
        assertEquals(
            listOf(StatefulAgentWorkflow.AgentSideEffect.CallLlm), effects
        )
    }

    // ───────────────────── Reducer：ToolBatchFinished 的循环判定 ─────────────────────

    /** 连续 30 次同参同结果是熔断级：本批结果先补完（不留孤儿 tool_call），再以错误结束本轮且不再请求下一轮。 */
    @Test
    fun toolBatchFinishedCircuitBreaksOnRepeatedIdenticalResults() {
        val lastCall = loopCall(30)
        val state = StatefulAgentWorkflow.AgentSessionState(
            messages = loopHistory(29) + loopAssistant(30),
            batchToolCalls = listOf(lastCall)
        )

        val (next, effects) = workflow.reduce(
            state,
            StatefulAgentWorkflow.AgentAction.ToolBatchFinished(listOf(
                StatefulAgentWorkflow.ToolBatchResult(lastCall.id, lastCall.name, "same", false)
            ))
        )

        assertTrue("熔断要终止本轮", next.isFinished)
        assertEquals(TOOL_LOOP_CIRCUIT_BREAK_CODE, next.errorCode)
        assertEquals(toolLoopCircuitBreakText("readFile", 30), next.error)
        assertTrue("熔断后不再请求下一轮", effects.isEmpty())
        assertTrue(next.batchToolCalls.isEmpty())
        assertEquals(
            "熔断也要把本批结果补齐，不能留下孤儿 tool_call",
            1, next.messages.count { it is AgentMessage.ToolResultMessage && it.id == lastCall.id }
        )
    }

    /** 刚跨过警告阈值的那一批注入一次循环提醒（带固定 id 前缀，便于判定窗口识别）。 */
    @Test
    fun toolBatchFinishedInjectsLoopWarningOnceWhenCrossingThreshold() {
        val lastCall = loopCall(10)
        val state = StatefulAgentWorkflow.AgentSessionState(
            messages = loopHistory(ToolLoopThresholds.WARNING - 1) + loopAssistant(10),
            batchToolCalls = listOf(lastCall)
        )

        val (next, effects) = workflow.reduce(
            state,
            StatefulAgentWorkflow.AgentAction.ToolBatchFinished(listOf(
                StatefulAgentWorkflow.ToolBatchResult(lastCall.id, lastCall.name, "same", false)
            ))
        )

        val nudges = next.messages.filterIsInstance<AgentMessage.UserMessage>()
            .filter { it.id.startsWith(TOOL_LOOP_NUDGE_ID) }
        assertEquals(1, nudges.size)
        assertEquals("$TOOL_LOOP_NUDGE_ID-${ToolLoopThresholds.WARNING}", nudges.single().id)
        assertEquals(toolLoopWarningText("readFile", ToolLoopThresholds.WARNING), nudges.single().content)
        assertFalse(next.isFinished)
        assertEquals(listOf(StatefulAgentWorkflow.AgentSideEffect.CallLlm), effects)
    }

    /** 已站在阈值之上的后续批次不再重复提醒，否则每批插一句会把上下文刷满。 */
    @Test
    fun toolBatchFinishedDoesNotRepeatLoopWarningAboveThreshold() {
        val history = loopHistory(ToolLoopThresholds.WARNING) + AgentMessage.UserMessage(
            id = "$TOOL_LOOP_NUDGE_ID-${ToolLoopThresholds.WARNING}",
            content = toolLoopWarningText("readFile", ToolLoopThresholds.WARNING)
        )
        val lastCall = loopCall(11)
        val state = StatefulAgentWorkflow.AgentSessionState(
            messages = history + loopAssistant(11),
            batchToolCalls = listOf(lastCall)
        )

        val (next, _) = workflow.reduce(
            state,
            StatefulAgentWorkflow.AgentAction.ToolBatchFinished(listOf(
                StatefulAgentWorkflow.ToolBatchResult(lastCall.id, lastCall.name, "same", false)
            ))
        )

        val nudges = next.messages.filterIsInstance<AgentMessage.UserMessage>()
            .filter { it.id.startsWith(TOOL_LOOP_NUDGE_ID) }
        assertEquals("只保留既有那条提醒", 1, nudges.size)
        assertEquals("本批只多了 assistant 与一条工具结果", history.size + 2, next.messages.size)
    }

    /** 循环判定用的同一调用：同工具、同参数、同结果。 */
    private fun loopCall(index: Int) =
        ToolCall("loop-$index", "readFile", mapOf("path" to JsonPrimitive("/a.txt")))

    /**
     * 本批对应的 assistant 消息：真实循环里 reduce(LlmResponse) 会先把它写进 messages，
     * 循环判定要靠它反查调用参数（没有这条，最后的工具结果会被当作找不到调用的孤儿而跳过）。
     */
    private fun loopAssistant(index: Int) = AgentMessage.AssistantMessage(
        id = "assistant-$index",
        content = "",
        toolCalls = listOf(loopCall(index))
    )

    /**
     * 构造「同一调用同参同结果连续重复 [repeats] 次」的历史。
     * 开头先放一条卡死提示，压掉 reducer 里与循环判定无关的卡死注入，让断言只盯循环分支。
     */
    private fun loopHistory(repeats: Int, result: String = "same"): List<AgentMessage> =
        listOf(AgentMessage.UserMessage(content = STUCK_NUDGE_TEXT)) + (1..repeats).flatMap { index ->
            val call = loopCall(index)
            listOf(
                AgentMessage.AssistantMessage(id = "assistant-$index", content = "", toolCalls = listOf(call)),
                AgentMessage.ToolResultMessage(id = call.id, toolName = call.name, result = result)
            )
        }

    /**
     * 跑一轮「LLM 返回一批工具调用 → 执行 → 再问一次 LLM」的真实循环。
     * 首轮响应带 [calls]，次轮收尾成普通文本；返回的工具完成事件与第二轮请求消息可供断言。
     */
    private suspend fun runToolBatchTurn(tool: AgentTool, calls: List<ToolCall>): ToolBatchTurn {
        val sentMessages = mutableListOf<List<AgentMessage>>()
        var round = 0
        prepare(flow {})
        // 策略放行 + 工具默认 AUTO_APPROVE：本批直接进入并行执行，不经过弹窗。
        coEvery { policyEngine.evaluate(any(), any(), any(), any(), any(), any()) } returns
            ToolPermissionPolicyEngine.EvalResult(ToolPermissionPolicyEngine.Verdict.ALLOW, emptyList())
        every { outputStore.process(any(), any(), any()) } answers { arg<ToolResult>(2) }
        every { provider.completeStream(any(), any(), any(), any()) } answers {
            sentMessages.add(arg<List<AgentMessage>>(1))
            val response = if (round++ == 0) AIResponse("", calls) else AIResponse("done")
            flow { emit(AIStreamChunk.Final(response)) }
        }
        val finished = mutableListOf<AgentEvent.ToolCallFinished>()
        workflow.executeEvents("request", context, listOf(tool)).collect { event ->
            when (event) {
                is AgentEvent.AssistantText -> event.persisted?.complete(Unit)
                is AgentEvent.ToolCallFinished -> {
                    finished.add(event)
                    event.persisted?.complete(Unit)
                }
                else -> Unit
            }
        }
        assertEquals("用例应恰好跑两轮 LLM 调用", 2, sentMessages.size)
        return ToolBatchTurn(finished, sentMessages[1])
    }

    private class ToolBatchTurn(
        val finished: List<AgentEvent.ToolCallFinished>,
        /** 第二轮请求发给模型的完整消息列表：用来核对 tool 结果条数与顺序。 */
        val secondRoundMessages: List<AgentMessage>
    )

    /**
     * 真实工具实现（不用 mock）：token 估算会读 name / description / toJsonSchema()，
     * mock 要逐项打桩，真实子类更接近生产路径且不会因为漏桩而抛 MockKException。
     */
    private class FakeEchoTool : AgentTool() {
        override val name = "echo"
        override val description = "测试用回声工具"
        override val parameters: Map<String, ToolParameter> = emptyMap()
        val executions = mutableListOf<Map<String, JsonElement>>()

        override suspend fun executeWithContext(
            args: Map<String, JsonElement>,
            context: AgentContext
        ): ToolResult {
            executions.add(args)
            return ToolResult.Success(JsonPrimitive("ok"))
        }
    }
}
