package com.aharou.feature.agent.domain.workflow

import android.os.SystemClock
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.memory.MemoryCurator
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.notification.AgentEventInjector
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.ocr.TesseractOcrEngine
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.ToolPermissionPolicyEngine
import com.aharou.feature.agent.domain.prompt.SystemPromptProvider
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.PendingPermissionBatch
import com.aharou.feature.agent.domain.tool.StreamingAgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.ToolOutputStore
import com.aharou.feature.agent.domain.tool.ToolStreamEvent
import com.aharou.feature.agent.domain.tool.foldStoredToolResults
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.agent.domain.tool.toTransportString
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.CompactionModelSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ProviderKeyRotator
import com.aharou.feature.settings.data.repository.TitleModelSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.fixedTemperature
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.agent.domain.provider.AllKeysFailedException
import com.aharou.feature.agent.domain.provider.enrichWithHttpErrorBody
import com.aharou.feature.agent.domain.provider.KeySwitchOutcome
import com.aharou.feature.agent.domain.provider.isKeySwitchFailure
import com.aharou.feature.agent.domain.provider.OpenAIAdapter
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.aharou.feature.agent.domain.provider.StreamApiException
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.UUID
import javax.inject.Inject

/**
 * 阶段三重构 (完全版)：基于不可变状态 (Immutable State) 与 MVI 架构的 Agent 工作流引擎。
 * 通过定义明确的 AgentSessionState, AgentAction 与 AgentSideEffect，
 * 采用 Reducer 来进行状态扭转，将纯函数的业务逻辑与带有副作用的外部环境操作完全解耦。
 */
class StatefulAgentWorkflow @Inject constructor(
    private val toolRegistry: ToolRegistry,
    private val aiProviderRepository: AIProviderRepository,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi,
    private val promptProvider: SystemPromptProvider,
    private val permissionManager: ToolPermissionManager,
    private val policyEngine: ToolPermissionPolicyEngine,
    private val contextCompactor: ContextCompactor,
    private val planApprovalManager: PlanApprovalManager,
    private val toolOutputStore: ToolOutputStore,
    private val modelMetadataService: ModelMetadataService,
    private val compactionModelSettingsRepository: CompactionModelSettingsRepository,
    private val titleModelSettingsRepository: TitleModelSettingsRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val keyRotator: ProviderKeyRotator,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val eventInjector: AgentEventInjector,
    private val memoryCurator: MemoryCurator,
    private val fileAccess: FileAccessProvider,
    private val ocrEngine: TesseractOcrEngine
) : AgentWorkflow {

    internal companion object {
        const val TAG = "StatefulAgentWorkflow"
        const val LIVE_TAIL_CHARS = 20_000

        private fun livePreview(text: StringBuilder): String {
            var start = (text.length - LIVE_TAIL_CHARS).coerceAtLeast(0)
            if (start > 0 && text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) start++
            return text.substring(start)
        }
        internal fun AgentEvent.AssistantText.hasSnapshotData(): Boolean =
            content.isNotEmpty() || reasoning.isNotBlank() || toolCalls.isNotEmpty() || signature.isNotBlank() ||
                thinkingBlocksJson.isNotBlank() || attachments.isNotEmpty() || inputTokens > 0 || outputTokens > 0 || cachedInputTokens > 0
        internal fun AgentEvent.AssistantText.persistenceConfirmed(): Boolean =
            persisted?.let { it.isCompleted && !it.isCancelled } == true
        const val PERSIST_WAIT_MS = 15_000L
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_REJECTED_CODE = "USER_REJECTED"

        /** 模型调用了不在本次运行允许清单里的工具：不执行，直接回错误结果。 */
        const val TOOL_NOT_ALLOWED_CODE = "TOOL_NOT_ALLOWED"
        const val TITLE_GENERATOR_FILE = "agent/title-generator.md"
        const val TITLE_MAX_CHARS = 50
        const val COMMIT_GENERATOR_FILE = "agent/commit-generator.md"
        /** 模式提醒提示词：复用 prompts 目录文件（用户可自定义覆盖），切换时随消息注入而非进 system。 */
        const val MODE_REMINDER_PLAN_FILE = "agent/plan-mode.md"
        const val MODE_REMINDER_AUTO_FILE = "agent/auto-mode.md"
        val LEADING_COMMENT = Regex("(?s)^\\s*<!--.*?-->\\s*")
        /** 模型直出图片落盘目录（与 GenerateImageTool 保持一致）。 */
        const val GENERATED_IMAGE_DIR = "~/.aharou/generated-images"
        const val MAX_GENERATED_IMAGE_BYTES = 20L * 1024 * 1024

        /**
         * 单次任务里工具调用的总次数上限（跨轮累加）。模型可能陷入「调工具→再调工具」的循环，
         * 到量后回一条提示并结束本轮，不再只能靠用户手动停止。
         */
        const val MAX_TOTAL_TOOL_CALLS = 200
    }

    /**
     * 每会话「上次已注入模式提醒的模式」。模式提醒只在变化时注入，避免每轮把同一段文本
     * 重复拼到最新用户消息上——那样下一轮重建历史时该位置（Anthropic 缓存断点）内容不一致，
     * 会白白丢掉一段前缀缓存。进程内缓存，重启后首轮再注入一次（无害）。
     */
    private val lastInjectedMode = java.util.concurrent.ConcurrentHashMap<String, AgentMode>()

    /** 不可变状态树 */
    data class AgentSessionState(
        val messages: List<AgentMessage> = emptyList(),
        val iterations: Int = 0,
        val isFinished: Boolean = false,
        val error: String? = null,
        /** 错误类型码（如服务端 stop_reason），供 UI 换成本地化文案；null 表示直接展示 [error]。 */
        val errorCode: String? = null,
        /** 本批模型返回的 toolCalls（原始顺序，用于最后按序组装 tool 响应） */
        val batchToolCalls: List<ToolCall> = emptyList(),
        /** 待请求权限的 toolCall（逐个弹窗收集） */
        val pendingPermissionCalls: List<ToolCall> = emptyList(),
        /** 已批准、待并行执行的 toolCall */
        val approvedToolCalls: List<ToolCall> = emptyList(),
        /** 被策略/系统拒绝（非用户拒绝）的 tool 结果，key = toolCall.id */
        val rejectedToolResults: Map<String, ToolBatchResult> = emptyMap(),
        /** 累计进入执行阶段的工具调用次数（跨轮累加），用于总次数熔断。 */
        val totalToolCalls: Int = 0,
        /** 已达上限并回传过一次提示：再触发时直接结束，不再给模型收尾回合。 */
        val toolLimitNotified: Boolean = false
    )

    /** 改变状态的动作 (Action) */
    sealed interface AgentAction {
        data class InitRequest(val initialMessages: List<AgentMessage>) : AgentAction
        data class LlmResponse(val response: AIResponse, val messageId: String) : AgentAction
        data class LlmError(val error: String, val reasonCode: String? = null) : AgentAction
        /**
         * 整批权限评估完成：[approved] 为可执行集（策略放行 + 用户放行），[rejectedResults] 为策略拒绝项；
         * [userRejected]=true 表示用户拒绝了本批（含「全部拒绝」），整批取消。
         */
        data class PermissionBatchEvaluated(
            val approved: List<ToolCall>,
            val rejectedResults: Map<String, ToolBatchResult>,
            val userRejected: Boolean = false
        ) : AgentAction
        data class ToolBatchFinished(val results: List<ToolBatchResult>) : AgentAction
        data class ToolBatchRejected(val results: List<ToolBatchResult>, val finish: Boolean) : AgentAction
    }

    private data class ToolRunResult(
        val raw: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 批量工具执行结果：携带 toolCall 元信息，供最后按原始顺序组装 ToolResultMessage。 */
    data class ToolBatchResult(
        val id: String,
        val toolName: String,
        val result: String,
        val isError: Boolean,
        /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
        val attachments: List<AgentAttachment> = emptyList(),
        val images: List<AgentImage> = emptyList()
    )

    /** 需要在外部环境中执行的副作用 (SideEffect) */
    sealed interface AgentSideEffect {
        object CallLlm : AgentSideEffect
        data class PersistUser(val message: AgentMessage.UserMessage) : AgentSideEffect
        /** 一次回合的全部 tool_call 合并成「一个提案批量审批」。 */
        data class RequestPermissionBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        /** 批量并行执行已批准的工具；传入空列表表示本批无工具可执行，直接进入收尾。 */
        data class ExecuteToolBatch(val toolCalls: List<ToolCall>) : AgentSideEffect
        data class RejectToolBatch(
            val toolCalls: List<ToolCall>, val message: String, val code: String, val finish: Boolean,
            val previousResults: Map<String, ToolBatchResult> = emptyMap()
        ) : AgentSideEffect
    }

    private suspend fun getEffectiveProvider(sessionId: String?): AIProvider {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    /**
     * 解析当前生效的 provider 配置：优先用 session 绑定的 providerId/model，回退全局 active provider。
     * session 绑定的 provider 不存在或已禁用时回退全局，保证老会话与异常数据不中断。
     */
    private suspend fun resolveProviderConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    // 绑定的模型可能已被移出该 provider 的模型列表，此时绑定失效、继续往下回退默认模型
                    if (boundModel.isNullOrBlank()) return config
                    if (boundModel in config.models) return config.copy(selectedModel = boundModel)
                }
            }
        }
        // 回退：新会话默认模型（主页空会话中选择后记忆）；未设置则返回 null，由调用方报错引导。
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    override suspend fun compactSession(sessionId: String, onEvent: suspend (AgentEvent) -> Unit): Boolean {
        val config = resolveProviderConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (!config.hasUsableApiKey) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        val provider = createStandaloneProvider(config, sessionId)
        val history = messagePersistenceUseCase.buildHistory(sessionId, "__manual_compress__")
        if (history.size <= 2) return false
        val session = sessionUseCase.getSessionById(sessionId)
        val domain = session?.toDomain()
        val definition = session?.takeIf { it.parentId != null }?.subagentType
            ?.let { promptProvider.findAgentDefinitionIncludingDisabled(it) }
        val context = AgentContext(null, null, session?.workspacePath.orEmpty(), null,
            history = history, sessionId = sessionId, mode = domain?.mode ?: AgentMode.BUILD,
            modeBeforePlan = domain?.modeBeforePlan, reasoningEffort = domain?.reasoningEffort?.apiValue,
            lastInputTokens = session?.lastInputTokens ?: 0, agentDefinition = definition)
        val allTools = toolRegistry.getAvailableTools()
        val tools = when {
            definition != null -> definition.filterToolNames(allTools.map { it.name }).toSet()
                .let { allowed -> allTools.filter { it.name in allowed } }
            session?.parentId != null -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
            else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
        }
        val compactionProvider = resolveCompactionFallbackProvider(sessionId) ?: provider
        val result = contextCompactor.compactIfNeeded(history, compactionProvider, sessionId, force = true,
            windowProvider = provider, systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(context) }, tools = tools, onEvent = onEvent)
        return result.compacted
    }

    /**
     * 根据 [config] 创建一个全新的、独立的 [AIProvider] 实例。
     * 用于上下文压缩等独立请求场景，完全不占用或修改主对话所用的 Provider 单例。
     * 同时把提供商级 LLM 缓存开关（Anthropic 断点 / OpenAI cache key）应用到实例。
     */
    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        // 多 Key 模式下由轮换器决定本次用哪个 Key（会话内粘住，Key 不可用时切下一个并重发）。
        val activeKeys = config.effectiveApiKeys
        provider.apiKey = keyRotator.activeKey(config, sessionId)
            ?: throw IllegalStateException("「${config.name}」的 Key 均在冷却中，请稍后重试")
        // 只有真正的多 Key 才需要自动切换：单 Key 冷却后没有可切换目标，只会把用户锁死一段时间。
        val keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? =
            if (activeKeys.size > 1) {
                { error, failedKey, triedKeys ->
                    if (!error.isKeySwitchFailure(config.effectiveKeySwitchStatusCodes)) {
                        null
                    } else {
                        val switched = keyRotator.reportFailure(config.id, sessionId, failedKey, triedKeys)
                        if (switched == null) {
                            val e = error.enrichWithHttpErrorBody()  // 原始异常此处确定被丢弃，读走 errorBody 无副作用；enrich 后 message 才带上游响应体 detail
                            throw AllKeysFailedException(
                                "「${config.name}」的 ${activeKeys.size} 个 Key 均失败：${e.message ?: e.javaClass.simpleName}", e)
                        }
                        KeySwitchOutcome(switched.newKey, switched.newIndex, switched.total)
                    }
                }
            } else {
                null
            }
        provider.keySwitcher = keySwitcher
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        // 模型元数据的输出上限（models.dev limit.output）：不传时 Anthropic 会把输出卡在 adapter 兜底值上。
        provider.maxOutputTokens = metadata.outputTokens
        // 元数据说不接受自定义温度就不发该字段（kimi-k3、gpt-5 系带了直接 400）；允许的只发官方固定值。
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    override fun executeEvents(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>
    ): Flow<AgentEvent> = channelFlow {
        runAgentLoop(userRequest, context, tools) { send(it) }
    }

    /**
     * Agent 主循环：初始化运行期上下文，然后反复「取动作 → Reducer → 执行副作用」，
     * 直到流程结束或动作队列排空。
     *
     * 之所以自成一函数：原先整段（含每次 LLM 调用、每批工具执行）都写在 executeEvents 的
     * channelFlow 闭包里，编译出的协程状态机 invokeSuspend 有十万量级指令；ART 对这么大的
     * 方法会放弃 JIT、只能解释执行，在「流式中点停止」的取消展开路径上直接 native abort。
     * 拆开后主循环只保留调度骨架，重活各自落在独立函数里，每个状态机都小到能正常跑。
     */
    private suspend fun runAgentLoop(
        userRequest: String,
        context: AgentContext,
        tools: List<AgentTool>,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val telemetry = AgentTurnTelemetry.begin(context.sessionId, context.mode.name, tools.size, context.history.size)
        var currentContext = context
        var state = AgentSessionState()
        var currentTools = tools
        // 执行期只认「本次运行允许的工具集」：toolRegistry 是全量的，回落过去等于把所有黑白名单
        // （子代理禁 task、messageParent 方向限制、定义里的 allowedTools/disallowedTools）全部作废——
        // 模型只要发出一个不在清单里的调用就能绕过（2026-10-05 实测：子代理真的派出了子代理）。
        val toolsByName = tools.associateBy { it.name }
        val actionQueue = ArrayDeque<AgentAction>()
        // 模式提醒仅在模式变化时随最新用户消息注入一次（不进 system，避免切换时 system 前缀变化打断缓存）。
        val modeReminder = takeModeReminderIfChanged(currentContext.sessionId, currentContext.mode)
        actionQueue.addLast(
            AgentAction.InitRequest(
                currentContext.history + AgentMessage.UserMessage(
                    id = currentContext.inputMessageId,
                    content = if (modeReminder == null) userRequest else "$userRequest\n\n$modeReminder",
                    images = currentContext.inputImages
                )
            )
        )

        // 提示词构建会读取技能/子代理/记忆等文件（远程经 SFTP），须离开收集本工作流的线程（主线程）。
        val promptStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "prompt.start", details = "operation=$promptStartedNs")
        val systemPrompt = withContext(Dispatchers.IO) { promptProvider.build(currentContext) }
        FileLogger.memoryCheckpoint(TAG, "prompt.ready",
            elapsedMs = (System.nanoTime() - promptStartedNs) / 1_000_000,
            details = "operation=$promptStartedNs promptChars=${systemPrompt.length}")
        val aiProvider = getEffectiveProvider(currentContext.sessionId)
        val metadata = modelMetadataService.resolve(aiProvider.providerId, when (aiProvider) {
            is AnthropicAdapter -> ProviderType.ANTHROPIC
            is GeminiAdapter -> ProviderType.GEMINI
            else -> ProviderType.OPENAI
        }, aiProvider.model)
        val inputBudget = ModelContextPolicy.effectiveInputBudget(metadata)
        if (aiProvider is AnthropicAdapter) {
            aiProvider.maxOutputTokens = ModelContextPolicy.outputReserveTokens(metadata)
        }
        val historyStartedNs = System.nanoTime()
        FileLogger.memoryCheckpoint(TAG, "history.start", details = "operation=$historyStartedNs")
        val lastAssistantIndex = currentContext.history.indexOfLast { it is AgentMessage.AssistantMessage && it.inputTokens > 0 }
        // 预算基线与两个「本轮只试一次」的开关都只在 LLM 调用分支用到，收成一个可变对象，
        // 让该分支抽成独立函数后不必在主循环里再留 4 个跨挂起点局部变量。
        val llmBudget = LlmCallBudgetState(
            baselineEstimate = withContext(Dispatchers.Default) { ContextTokenEstimator.estimate(systemPrompt,
                currentContext.history.take(lastAssistantIndex.coerceAtLeast(0)), currentTools) },
            baselineUsage = if (currentContext.lastInputTokens > 0 && lastAssistantIndex >= 0) {
                (currentContext.history[lastAssistantIndex] as AgentMessage.AssistantMessage).inputTokens
            } else 0
        )
        telemetry.historyReady(systemPrompt.length, currentTools.size, currentContext.history.size)
        FileLogger.memoryCheckpoint(TAG, "history.ready",
            elapsedMs = (System.nanoTime() - historyStartedNs) / 1_000_000,
            details = "operation=$historyStartedNs messages=${currentContext.history.size} tools=${currentTools.size}")

        while (!state.isFinished && actionQueue.isNotEmpty()) {
            // 用户按了停止：不再往下走。正在跑的命令/请求留在后台跑完（见 stopAgentSession 的软打断）。
            if (consumeUserInterrupt(currentContext.sessionId)) {
                FileLogger.i(TAG, "收到用户打断，本轮到此为止")
                actionQueue.clear()
                break
            }
            val action = actionQueue.removeFirst()
            val (newState, effects) = reduce(state, action)
            state = newState

            for (effect in effects) {
                when (effect) {
                    is AgentSideEffect.PersistUser -> {
                        currentContext.sessionId?.let { sessionId ->
                            messagePersistenceUseCase.persist(sessionId, com.aharou.feature.agent.presentation.MessageRole.USER,
                                effect.message.content, id = effect.message.id)
                            messagePersistenceUseCase.invalidateHistory(sessionId)
                        }
                    }
                    is AgentSideEffect.CallLlm -> {
                        state = executeLlmEffect(
                            initialState = state,
                            currentContext = currentContext,
                            currentTools = currentTools,
                            systemPrompt = systemPrompt,
                            aiProvider = aiProvider,
                            inputBudget = inputBudget,
                            budget = llmBudget,
                            actionQueue = actionQueue,
                            emit = emit,
                            telemetry = telemetry
                        )
                    }
                    is AgentSideEffect.RequestPermissionBatch -> {
                        handlePermissionBatchEffect(
                            toolCalls = effect.toolCalls,
                            currentContext = currentContext,
                            toolsByName = toolsByName,
                            actionQueue = actionQueue,
                            emit = emit
                        )
                    }
                    is AgentSideEffect.RejectToolBatch -> {
                        val results = effect.toolCalls.map { call ->
                            effect.previousResults[call.id]?.let { return@map it }
                            val result = toolError(call, effect.message, effect.code)
                            emit(AgentEvent.ToolCallFinished(call.id, call.name, result, true))
                            ToolBatchResult(call.id, call.name, result, true)
                        }
                        actionQueue.addLast(AgentAction.ToolBatchRejected(results, effect.finish))
                    }
                    is AgentSideEffect.ExecuteToolBatch -> {
                        currentContext = executeToolBatchEffect(
                            toolCalls = effect.toolCalls,
                            initialContext = currentContext,
                            toolsByName = toolsByName,
                            actionQueue = actionQueue,
                            emit = emit,
                            telemetry = telemetry
                        )
                    }
                }
            }
        }
        
        state.error?.let {
            telemetry.fail(it, state.errorCode, state.iterations)
            emit(AgentEvent.Failed(it, state.errorCode))
        }
        if (state.error == null) telemetry.end(state.iterations, state.totalToolCalls)
        emit(AgentEvent.Completed)
    }

    /**
     * 同一轮任务内跨多次 LLM 调用保持的预算基线与恢复开关（只在 LLM 调用分支使用）。
     * [compactionAttemptFailed] 与 [overflowRecoveryAttempted] 都是「本轮只试一次」的标记。
     */
    private class LlmCallBudgetState(
        var baselineEstimate: Int,
        var baselineUsage: Int,
        var compactionAttemptFailed: Boolean = false,
        var overflowRecoveryAttempted: Boolean = false
    )

    /**
     * 单次 LLM 调用的完整副作用：压缩与预算检查 → 流式收流与节流推送 → 组装回复入队，
     * 以及取消展开时的收尾落库。抽成独立函数的目的是控制协程状态机体积（见 [runAgentLoop] 注释）。
     *
     * @return 可能被压缩改写过的新会话状态。
     */
    /**
     * 会话是否收到了「用户打断」：peek 到就打勾并 ack 掉（一次性消费），
     * 供主循环与流式收集两处判断「用户按了停止」。
     */
    private fun consumeUserInterrupt(sessionId: String?): Boolean {
        if (sessionId == null) return false
        val hits = agentNotificationCenter.peek(sessionId)
            .filter { it.kind == AgentNotificationKind.USER_INTERRUPT }
        if (hits.isEmpty()) return false
        agentNotificationCenter.ack(sessionId, hits.map { it.seq })
        return true
    }

    private suspend fun executeLlmEffect(
        initialState: AgentSessionState,
        currentContext: AgentContext,
        currentTools: List<AgentTool>,
        systemPrompt: String,
        aiProvider: AIProvider,
        inputBudget: Int,
        budget: LlmCallBudgetState,
        actionQueue: ArrayDeque<AgentAction>,
        emit: suspend (AgentEvent) -> Unit,
        telemetry: AgentTurnTelemetry
    ): AgentSessionState {
        var state = initialState
        val providerInUse = aiProvider
        // 压缩轮：若配置了压缩专用模型，使用独立压缩模型压缩
        val compactionProvider = resolveCompactionFallbackProvider(currentContext.sessionId) ?: providerInUse
        var compactedMessages = state.messages
        if (!budget.compactionAttemptFailed) {
            telemetry.compactionStart()
            val estimate = withContext(Dispatchers.Default) { ContextTokenEstimator.estimate(systemPrompt, state.messages, currentTools) }
            val predictedInput = ContextTokenEstimator.calibrated(estimate, budget.baselineEstimate, budget.baselineUsage)
            val compaction = contextCompactor.compactIfNeeded(state.messages, compactionProvider, currentContext.sessionId,
                windowProvider = aiProvider, systemPrompt = systemPrompt, tools = currentTools,
                currentInputTokens = ContextTokenEstimator.forCompactionTrigger(estimate, budget.baselineEstimate, budget.baselineUsage)) { event ->
                if (event is AgentEvent.CompactionFailed) budget.compactionAttemptFailed = true
                emit(event)
            }
            compactedMessages = compaction.messages
            telemetry.compactionEnd(compaction.compacted, compactedMessages.size)
            if (compaction.compacted) {
                state = state.copy(messages = compaction.messages)
                budget.baselineUsage = 0
                budget.baselineEstimate = 0
            }
        }
        val requestEstimate = withContext(Dispatchers.Default) { ContextTokenEstimator.estimate(systemPrompt, compactedMessages, currentTools) }
        val predictedInput = ContextTokenEstimator.calibrated(requestEstimate, budget.baselineEstimate, budget.baselineUsage)
        emit(AgentEvent.ContextUsage(predictedInput, inputBudget, true))
        if (predictedInput >= inputBudget) {
            actionQueue.addLast(AgentAction.LlmError("", "input_budget_exceeded"))
            return state
        }

        val acc = StringBuilder()
        val reasoningAcc = StringBuilder()
        var finalResponse: AIResponse? = null
        val responseMessageId = UUID.randomUUID().toString()
        var assistantSnapshot: AgentEvent.AssistantText? = null
        var snapshotSent = false
        var persistedImages = emptyList<AgentImage>()
        suspend fun captureSnapshot(): AgentEvent.AssistantText {
            assistantSnapshot?.let { return it }
            val response = finalResponse ?: AIResponse(acc.toString(), reasoning = reasoningAcc.toString())
            val (images, attachments) = if (response.images.isNotEmpty()) persistModelImages(response.images)
                else emptyList<AgentImage>() to emptyList<AgentAttachment>()
            persistedImages = images
            return AgentEvent.AssistantText(response.content, response.toolCalls,
                response.reasoning.orEmpty().ifEmpty { reasoningAcc.toString() }, response.signature.orEmpty(), response.inputTokens,
                response.outputTokens, response.cachedInputTokens, response.thinkingBlocksJson.orEmpty(),
                attachments, responseMessageId, kotlinx.coroutines.CompletableDeferred()).also { assistantSnapshot = it }
        }
        suspend fun publishSnapshot() {
            val snapshot = captureSnapshot()
            if (!snapshotSent) { emit(snapshot); snapshotSent = true }
            withTimeoutOrNull(PERSIST_WAIT_MS) { snapshot.persisted?.await() }
        }

        // 调用统计埋点：记录请求发出/首字/结束时刻与 usage，失败与取消同样留痕。
        val callStartElapsed = SystemClock.elapsedRealtime()
        val callStartWall = System.currentTimeMillis()
        val callKind = "chat"
        var ttfbElapsed: Long? = null
        var callError: String? = null
        var callCompleted = false

        // 流式 delta 节流：上游每个 chunk 都携带完整累积文本，逐条发事件会让
        // ViewModel 端每秒重建几十次状态、UI 端反复重启打字机协程。按时间窗口合并：
        // 窗口内只保留最新累积文本，到窗口边界才发送，把下游事件频率压到 ~16/s。
        // 打字机渲染本身有 100ms 节流，少发中间态无感知；collect 结束补发最后 pending。
        val DELTA_THROTTLE_MS = 60L
        var lastTextDeltaSentAt = 0L
        var lastReasoningDeltaSentAt = 0L
        var pendingTextDelta = false
        var pendingReasoningDelta = false
        var retryAttempts = 0
        suspend fun flushPendingTextDelta() {
            if (!pendingTextDelta) return
            pendingTextDelta = false
            emit(AgentEvent.AssistantDelta(livePreview(acc)))
        }
        suspend fun flushPendingReasoningDelta() {
            if (!pendingReasoningDelta) return
            pendingReasoningDelta = false
            emit(AgentEvent.ReasoningDelta(livePreview(reasoningAcc)))
        }
        // 遥测的调用序号在 try 内产生，但 catch 分支也要用它标记这一次流失败，故声明在 try 之外。
        var callId = -1

        try {
            // 发送前按实际模型的视觉能力处理图片（同 execute 路径）。
            val supportsVision = activeModelSupportsVision(currentContext.sessionId)
            // 早轮次里已落盘的工具结果换成引用，长会话不必每轮重发全文。
            val messagesToSend = foldStoredToolResults(
                sanitizeImagesForModel(compactedMessages, supportsVision)
            )
            // 采样循环检测：命中后 takeWhile 会取消上游流，模型不再继续把重复内容刷下去。
            var samplingLoopCut = false
            // 用户打断：与采样检测共用同一个 takeWhile，命中即掉断上游流，不再继续吐字。
            var userInterruptCut = false
            // 延迟加载的工具（MCP）只有被 tool_search 展开过才进 tools 数组：
            // 每轮重新过滤，命中之后下一轮就能直接调用。
            val promptTools = currentTools.filter {
                !it.deferredLoading || toolRegistry.isActivated(it.name)
            }
            callId = telemetry.llmRequest(providerInUse.providerId, providerInUse.model, messagesToSend.size, promptTools.size, predictedInput)
            providerInUse.completeStream(systemPrompt, messagesToSend, promptTools, currentContext.reasoningEffort)
                .takeWhile { !samplingLoopCut && !userInterruptCut }
                .collect { chunk ->
                    if (!userInterruptCut && consumeUserInterrupt(currentContext.sessionId)) {
                        userInterruptCut = true
                    }
                when (chunk) {
                    is AIStreamChunk.TextDelta -> {
                        if (ttfbElapsed == null) { ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed; telemetry.llmFirstByte(callId) }
                        acc.append(chunk.text)
                        val loopStart = samplingLoopStart(acc)
                        if (loopStart >= 0) {
                            samplingLoopCut = true
                            cutSamplingLoop(acc, loopStart, inReasoning = false) { emit(it) }
                        }
                        pendingTextDelta = true
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastTextDeltaSentAt >= DELTA_THROTTLE_MS) {
                            flushPendingTextDelta()
                            lastTextDeltaSentAt = now
                        }
                    }
                    is AIStreamChunk.ReasoningDelta -> {
                        // 思考内容也算首字（推理模型先吐思考再吐正文）
                        if (ttfbElapsed == null) { ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed; telemetry.llmFirstByte(callId) }
                        reasoningAcc.append(chunk.text)
                        val loopStart = samplingLoopStart(reasoningAcc)
                        if (loopStart >= 0) {
                            samplingLoopCut = true
                            cutSamplingLoop(reasoningAcc, loopStart, inReasoning = true) { emit(it) }
                        }
                        pendingReasoningDelta = true
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastReasoningDeltaSentAt >= DELTA_THROTTLE_MS) {
                            flushPendingReasoningDelta()
                            lastReasoningDeltaSentAt = now
                        }
                    }
                    is AIStreamChunk.Retrying -> {
                        retryAttempts = maxOf(retryAttempts, chunk.attempt)
                        acc.setLength(0)
                        reasoningAcc.setLength(0)
                        pendingTextDelta = false
                        pendingReasoningDelta = false
                        lastTextDeltaSentAt = 0L
                        lastReasoningDeltaSentAt = 0L
                        emit(AgentEvent.Retrying(chunk.attempt, chunk.maxRetries, chunk.error))
                    }
                    is AIStreamChunk.KeySwitched -> {
                        acc.setLength(0)
                        reasoningAcc.setLength(0)
                        pendingTextDelta = false
                        pendingReasoningDelta = false
                        lastTextDeltaSentAt = 0L
                        lastReasoningDeltaSentAt = 0L
                        emit(AgentEvent.KeySwitched(chunk.newIndex, chunk.total))
                    }
                    is AIStreamChunk.Final -> {
                        // 纯工具调用轮没有文本/思考增量，Final 是首个内容事件，兜底记为 TTFB
                        if (ttfbElapsed == null) { ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed; telemetry.llmFirstByte(callId) }
                        finalResponse = chunk.response
                    }
                    is AIStreamChunk.ToolCallDeclared -> {
                        // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                        // 免得长参数流式期间一直停在「正在思考」。
                        if (ttfbElapsed == null) { ttfbElapsed = SystemClock.elapsedRealtime() - callStartElapsed; telemetry.llmFirstByte(callId) }
                        emit(AgentEvent.ToolCallPreparing(chunk.name))
                    }
                }
            }
            // 节流窗口内可能还压着最新累积文本：补发，保证 UI 尾巴拿到完整文本再交接落库。
            flushPendingTextDelta()
            flushPendingReasoningDelta()
            val aiResponse = finalResponse ?: AIResponse(content = acc.toString())
            val snapshot = captureSnapshot()
            telemetry.llmStreamEnd(callId, acc.length, reasoningAcc.length, snapshot.toolCalls.size, aiResponse.stopReason, aiResponse.inputTokens, aiResponse.outputTokens, retryAttempts)
            callCompleted = true
            if (aiResponse.stopReason == "model_context_window_exceeded" && !budget.overflowRecoveryAttempted) {
                budget.overflowRecoveryAttempted = true
                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { emit(it) }
                if (recovery.compacted) {
                    state = state.copy(messages = recovery.messages)
                    budget.baselineUsage = 0
                    budget.baselineEstimate = 0
                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                    return state
                }
            }
            budget.baselineEstimate = requestEstimate
            budget.baselineUsage = aiResponse.inputTokens
            emit(AgentEvent.ContextUsage(
                if (budget.baselineUsage > 0) budget.baselineUsage else requestEstimate, inputBudget, budget.baselineUsage <= 0
            ))
            // 将本轮 reasoning 附加到 AIResponse，以便 reduce 时存入 AssistantMessage 并在下一轮回传
            val responseWithReasoning = aiResponse.copy(reasoning = snapshot.reasoning)

            if (snapshot.hasSnapshotData()) publishSnapshot()
            actionQueue.addLast(
                AgentAction.LlmResponse(
                    if (persistedImages.isNotEmpty()) responseWithReasoning.copy(images = persistedImages)
                    else responseWithReasoning,
                    messageId = responseMessageId
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (finalResponse == null && !budget.overflowRecoveryAttempted && acc.isEmpty() && reasoningAcc.isEmpty() && isContextOverflow(e)) {
                budget.overflowRecoveryAttempted = true
                val recovery = contextCompactor.compactIfNeeded(state.messages, compactionProvider,
                    currentContext.sessionId, force = true, windowProvider = aiProvider,
                    systemPrompt = systemPrompt, tools = currentTools, currentInputTokens = predictedInput) { emit(it) }
                if (recovery.compacted) {
                    state = state.copy(messages = recovery.messages)
                    budget.baselineUsage = 0
                    budget.baselineEstimate = 0
                    actionQueue.addLast(AgentAction.InitRequest(recovery.messages))
                    callError = e.message
                    return state
                }
            }
            callError = e.message ?: e.javaClass.simpleName
            telemetry.llmStreamFailed(callId, callError ?: "unknown", acc.length)
            if (finalResponse != null || acc.isNotEmpty() || reasoningAcc.isNotEmpty()) {
                if (captureSnapshot().hasSnapshotData()) publishSnapshot()
            }
            // 多 Key 的自动切换与重发已在 adapter 内完成（见 AIProvider.keySwitcher）；
            // 走到这里说明不是 Key 问题、或候选 Key 已全部失败，直接上报原始错误。
            val errorText = "LLM 调用失败: ${e.message}"
            actionQueue.addLast(AgentAction.LlmError(errorText))
        } finally {
            // 取消展开期间绝不允许异常逃出 finally：待传播的取消异常一旦再叠上抛出的异常
            //（落库失败、日志格式化、OOM 等），ART 会直接 AssertNoPendingException 终止进程。
            try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.IO) {
                    currentContext.sessionId?.let { sid ->
                        if (finalResponse != null || acc.isNotEmpty() || reasoningAcc.isNotEmpty()) runCatching {
                            val snapshot = captureSnapshot()
                            if (snapshot.hasSnapshotData() && !snapshot.persistenceConfirmed()) {
                                messagePersistenceUseCase.persist(sid, com.aharou.feature.agent.presentation.MessageRole.ASSISTANT,
                                    snapshot.content, id = responseMessageId, reasoning = snapshot.reasoning, toolCalls = snapshot.toolCalls,
                                    signature = snapshot.signature, thinkingBlocksJson = snapshot.thinkingBlocksJson,
                                    attachments = snapshot.attachments, inputTokens = snapshot.inputTokens,
                                    outputTokens = snapshot.outputTokens, cachedInputTokens = snapshot.cachedInputTokens)
                                snapshot.persisted?.complete(Unit)
                            }
                        }.onFailure { runCatching { FileLogger.w(TAG, "保存回复快照失败: " + it.javaClass.simpleName) } }
                    }
                    // 调用记录同样放在 NonCancellable 里：否则取消时这一句会立刻再抛一个取消异常。
                    val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
                    val usage = finalResponse
                    runCatching {
                        llmCallRecordDao.insert(
                            LlmCallRecordEntity(
                                sessionId = currentContext.sessionId,
                                providerId = providerInUse.providerId.ifBlank { null },
                                model = providerInUse.model,
                                reasoningEffort = currentContext.reasoningEffort,
                                kind = callKind,
                                inputTokens = usage?.inputTokens ?: 0,
                                outputTokens = usage?.outputTokens ?: 0,
                                cachedInputTokens = usage?.cachedInputTokens ?: 0,
                                cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                                ttfbMillis = ttfbElapsed?.toInt(),
                                durationMillis = durationMillis,
                                status = when {
                                    callCompleted -> "success"
                                    callError != null -> "error"
                                    else -> "cancelled"
                                },
                                errorMessage = callError,
                                stopReason = usage?.stopReason,
                                retryCount = retryAttempts,
                                createdAt = callStartWall
                            )
                        )
                    }.onFailure { runCatching { FileLogger.w(TAG, "写入调用记录失败: " + it.javaClass.simpleName) } }
                }
            } catch (t: Throwable) {
                runCatching { FileLogger.w(TAG, "收尾落库异常: " + t.javaClass.simpleName) }
            }
        }
        return state
    }

    /**
     * 一次回合的多个工具调用合并为「一个提案批量审批」：先逐条纯评估（策略引擎 + planMode 特例），
     * 放行的直接进批准集、策略拒绝的记为拒绝结果、需询问的合并成一个批次一次性挂起等用户决定；
     * 用户拒绝（含「全部拒绝」）则整批取消。结果作为 [AgentAction.PermissionBatchEvaluated] 回灌主循环。
     */
    private suspend fun handlePermissionBatchEffect(
        toolCalls: List<ToolCall>,
        currentContext: AgentContext,
        toolsByName: Map<String, AgentTool>,
        actionQueue: ArrayDeque<AgentAction>,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val gate = PermissionGate(policyEngine, permissionManager, fileAccess)
        val approved = mutableListOf<ToolCall>()
        val rejectedResults = mutableMapOf<String, ToolBatchResult>()
        val asks = mutableListOf<Pair<ToolCall, PermissionVerdict.Ask>>()
        val argsPreviews = toolCalls.associate { it.id to JsonObject(it.arguments).toString().take(500) }

        toolCalls.forEach { toolCall ->
            val argsPreview = argsPreviews.getValue(toolCall.id)
            val tool = toolsByName[toolCall.name]
            val verdict = if (tool == null) {
                // 不在允许清单里的调用：不问权限、也不执行，直接回一条错误结果。
                PermissionVerdict.Deny("工具「${toolCall.name}」在当前会话不可用（不在允许的工具清单内）。", TOOL_NOT_ALLOWED_CODE)
            } else {
                gate.evaluate(tool, toolCall.id, toolCall.arguments, argsPreview, currentContext.mode,
                    currentContext.sessionId, currentContext.projectRoot, currentContext)
            }
            when (verdict) {
                PermissionVerdict.Allow -> approved.add(toolCall)
                is PermissionVerdict.Deny -> {
                    val result = toolError(toolCall, verdict.reason, verdict.code)
                    emit(AgentEvent.ToolCallFinished(toolCall.id, toolCall.name, result, true, argsPreview))
                    rejectedResults[toolCall.id] = ToolBatchResult(toolCall.id, toolCall.name, result, true)
                }
                is PermissionVerdict.Ask -> {
                    asks.add(toolCall to verdict)
                    approved.add(toolCall)
                }
            }
        }

        // 需询问的项一次性挂起一个批次，等用户对整批做决定。
        val userRejected = if (asks.isNotEmpty()) {
            val batch = PendingPermissionBatch(
                id = UUID.randomUUID().toString(),
                sessionId = currentContext.sessionId.orEmpty(),
                items = asks.map { it.second.request }
            )
            val decisions = gate.awaitBatch(batch, asks.map { it.second }, currentContext.projectRoot)
            asks.any { decisions[it.first.id] == PermissionChoice.REJECT }
        } else false

        if (userRejected) {
            // 用户拒绝本批：整批取消，已先行被策略拒绝的项其真实原因随 rejectedResults 保留。
            actionQueue.addLast(AgentAction.PermissionBatchEvaluated(emptyList(), rejectedResults, userRejected = true))
            return
        }
        approved.forEach { emit(AgentEvent.ToolCallStarted(it.id, it.name, argsPreviews.getValue(it.id))) }
        actionQueue.addLast(AgentAction.PermissionBatchEvaluated(approved, rejectedResults))
    }

    /**
     * 并行执行一批已批准的工具，再串行收口 mode 切换、通知注入与完成事件。
     * 抽成独立函数的目的是控制协程状态机体积（见 [runAgentLoop] 注释）。
     *
     * @return 可能被模式切换等改写的新上下文。
     */
    private suspend fun executeToolBatchEffect(
        toolCalls: List<ToolCall>,
        initialContext: AgentContext,
        toolsByName: Map<String, AgentTool>,
        actionQueue: ArrayDeque<AgentAction>,
        emit: suspend (AgentEvent) -> Unit,
        telemetry: AgentTurnTelemetry
    ): AgentContext {
        val batchId = telemetry.toolBatchStart(toolCalls)
        var currentContext = initialContext
        // 并行执行本批已批准的工具。先统一记录 checkpoint（editFile/writeFile 修改前快照），
        // 再并行执行；mode 切换检查在结果收集后于主协程串行处理（planApproval 单例）。
        toolCalls.forEach { toolCall ->
            if (toolCall.name == "editFile" || toolCall.name == "writeFile") {
                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                    currentContext.sessionId?.let { sid ->
                        checkpointManager.beforeFileModified(sid, path)
                    }
                }
            }
        }

        // 批内去重：模型偶尔会在同一次响应里给出完全相同的调用（同名同参），
        // 重复执行就是真跑两遍（写文件、执行命令都一样）。重复项不执行，只补一条说明，
        // 仍按原顺序返回，保持 tool 消息条数与 assistant(toolCalls) 对齐（否则 API 报 400）
        val seenSignatures = HashSet<String>()
        val duplicateSignatures = HashSet<String>()
        toolCalls.forEach { toolCall ->
            if (!seenSignatures.add(toolCallSignature(toolCall))) {
                duplicateSignatures.add(toolCallSignature(toolCall))
            }
        }

        val runResults = if (toolCalls.isEmpty()) {
            emptyList()
        } else {
            coroutineScope {
                toolCalls.map { toolCall ->
                    val isDuplicate = toolCallSignature(toolCall) in duplicateSignatures
                    async {
                        if (isDuplicate) {
                            ToolRunResult(
                                toolError(toolCall, "与本次响应中前面同名的调用参数完全相同，已跳过重复执行。", "DUPLICATE_TOOL_CALL"),
                                true
                            )
                        } else {
                            val tool = toolsByName[toolCall.name]
                            when {
                                tool == null -> ToolRunResult(
                                    toolError(toolCall, "工具「${toolCall.name}」在当前会话不可用（不在允许的工具清单内）。", TOOL_NOT_ALLOWED_CODE),
                                    true
                                )
                                tool is StreamingAgentTool -> runToolStream(tool, toolCall, currentContext) { emit(it) }
                                else -> runToolSync(tool, toolCall, currentContext)
                            }
                        }
                    }
                }.awaitAll()
            }
        }

        // 串行处理 mode 切换并组装批量结果。
        val batchResults = mutableListOf<ToolBatchResult>()
        toolCalls.forEachIndexed { index, toolCall ->
            val runResult = runResults.getOrNull(index)
                ?: ToolRunResult(toolError(toolCall, "工具未执行", "TOOL_NOT_EXECUTED"), true)
            var rawResult = runResult.raw
            var isError = runResult.isError
            // 写入成功后记录内容摘要，供回退时判断文件是否被检查点之外的操作改过
            if (!isError && (toolCall.name == "editFile" || toolCall.name == "writeFile")) {
                (toolCall.arguments["path"] as? JsonPrimitive)?.contentOrNull?.let { path ->
                    currentContext.sessionId?.let { sid -> checkpointManager.afterFileModified(sid, path) }
                }
            }
            val (newCtx, updated) = checkAndUpdateMode(toolCall, isError, currentContext)
            if (updated) {
                val reason = (toolCall.arguments["reason"] as? JsonPrimitive)?.content?.trim()
                    ?: toolCall.arguments["reason"]?.toString()?.replace("\"", "")?.trim()
                    ?: ""
                emit(AgentEvent.ModeChanged(newCtx.mode, reason))

                // 退出 PLAN 时挂起 workflow，等待用户在计划审查面板批准后才继续
                if (currentContext.mode == AgentMode.PLAN && newCtx.mode != AgentMode.PLAN) {
                    val choice = planApprovalManager.awaitApproval(reason, currentContext.sessionId)
                    if (choice == PlanApprovalChoice.APPROVE) {
                        currentContext = newCtx
                        // system 与 mode 已解耦（SystemPromptProvider 不再注入模式提示词），
                        // 切换不重建 systemPrompt，避免 system 前缀变化打断缓存；模式状态通过工具结果与下轮消息提醒告知。
                        rawResult += buildModeSwitchNotice(newCtx.mode)
                    } else {
                        // 用户选择继续反馈，回滚到 PLAN 模式，修正工具结果让 AI 知道切换被取消并等待用户反馈
                        currentContext = currentContext.copy(mode = AgentMode.PLAN)
                        rawResult = toolError(toolCall, "用户希望补充说明或调整方案，当前保持在 PLAN 模式。请等待用户输入具体的补充或修改意见，不要自行臆测修改，待用户明确反馈后再继续。", "MODE_SWITCH_REJECTED")
                        isError = true
                    }
                } else {
                    currentContext = newCtx
                    rawResult += buildModeSwitchNotice(newCtx.mode)
                }
            }
            batchResults.add(ToolBatchResult(toolCall.id, toolCall.name, rawResult, isError, runResult.attachments, runResult.images))
        }

        // 本轮内到达的后台任务/子代理完成通知：搭在本批最后一条工具结果上立即送达，
        // AI 当轮即可感知，不必等本轮结束再起新一轮。ack 放到完成事件发出（结果已落库）之后：
        // 中途被取消时通知仍留在队列里，由后续批次或本轮结束的兜底路径送达。
        val notifySessionId = currentContext.sessionId
        val notifications = if (notifySessionId != null && batchResults.isNotEmpty()) {
            // 用户打断是给循环看的开关，不是要给模型读的提示，别当通知注入进去。
            agentNotificationCenter.peek(notifySessionId)
                .filter { it.kind != AgentNotificationKind.USER_INTERRUPT }
        } else {
            emptyList()
        }
        if (notifications.isNotEmpty()) {
            val modeChange = notifications.lastOrNull { it.kind == AgentNotificationKind.MODE_CHANGE }
            val newMode = modeChange?.newMode
            if (newMode != null) {
                // 用户在工作期间切换模式：本轮后续批次的权限判定立即改用新模式。
                // 与 SessionUseCase.updateMode 保持同一套语义：进入 PLAN 记下进入前的模式，其余情况清空。
                val prevMode = currentContext.mode
                currentContext = if (newMode == AgentMode.PLAN) {
                    currentContext.copy(
                        mode = AgentMode.PLAN,
                        modeBeforePlan = if (prevMode != AgentMode.PLAN) prevMode else currentContext.modeBeforePlan
                    )
                } else {
                    currentContext.copy(mode = newMode, modeBeforePlan = null)
                }
            }
            val last = batchResults.last()
            var injected = eventInjector.inject(last.result, notifications)
            if (newMode != null) {
                // 模式约束提示随工具结果落库，留在历史里供后续轮沿用。
                injected += buildExternalModeSwitchNotice(newMode)
            }
            batchResults[batchResults.lastIndex] = last.copy(result = injected)
        }

        // 逐个推送完成事件（保持与 batchToolCalls 一致顺序），并进入收尾。
        batchResults.forEach { br ->
            val persisted = kotlinx.coroutines.CompletableDeferred<Unit>()
            emit(AgentEvent.ToolCallFinished(br.id, br.toolName, br.result, br.isError,
                attachments = br.attachments, persisted = persisted))
            withTimeoutOrNull(PERSIST_WAIT_MS) { persisted.await() }
        }
        if (notifySessionId != null && notifications.isNotEmpty()) {
            agentNotificationCenter.ack(notifySessionId, notifications.map { it.seq })
        }
        telemetry.toolBatchEnd(batchId, batchResults.size, batchResults.count { it.isError })
        actionQueue.addLast(AgentAction.ToolBatchFinished(batchResults))
        return currentContext
    }

    private fun isContextOverflow(error: Throwable): Boolean {
        if (error is StreamApiException && error.code == "context_window_exceeded") return true
        val text = error.message.orEmpty().lowercase()
        return listOf("context_length_exceeded", "context_window_exceeded", "maximum context length",
            "prompt is too long", "input token limit").any { it in text }
    }

    /** 工具调用的去重签名：同名且参数完全相同才算重复。 */
    private fun toolCallSignature(toolCall: ToolCall): String =
        toolCall.name + "\u0000" + JsonObject(toolCall.arguments).toString()

    private fun toolError(call: ToolCall, message: String, code: String): String = try {
        toolOutputStore.process(call.name, call.id, ToolResult.Error(message, code)).toTransportString()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.Error("Tool output processing failed", "TOOL_OUTPUT_PROCESSING_FAILED").toTransportString()
    }

    private suspend fun runToolSync(tool: AgentTool?, toolCall: ToolCall, context: AgentContext): ToolRunResult {
        val name = toolCall.name
        if (tool == null) {
            return ToolRunResult(toolError(toolCall, "工具 $name 不存在", "TOOL_NOT_FOUND"), true)
        }
        return try {
            withContext(Dispatchers.IO) {
                val result = tool.executeWithContext(toolCall.arguments, context)
                val attachments = if (name == "sendFile" || name == "generateImage") extractAttachments(result) else emptyList()
                val images = if (result is ToolResult.Success) result.images else emptyList()
                val transportResult = if (attachments.isNotEmpty()) stripAttachments(result) else result
                val processed = toolOutputStore.process(name, toolCall.id, transportResult)
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, attachments, images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(toolError(toolCall, "工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED"), true)
        }
    }

    private suspend fun activeModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveProviderConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /**
     * 发送前按模型视觉能力处理消息中的图片：
     * - 支持 vision：原样返回。
     * - 不支持：剥离所有图片（仅影响本次发送，不动持久化数据），历史/输入中的图片不会原样发给
     *   非多模态模型导致请求失败；切回多模态模型后图片上下文仍可正常使用。
     */
    /**
     * 发送前按模型能力处理图片：能看图就原样发；不能看时改用本地 OCR 把图转成文字塞回去。
     *
     * 这里之前是直接把 images 清空 —— 用户消息还剩一句「图片已省略」，工具结果那条连提示都没有，
     * 而影子屏截图正是走工具结果，模型连「有图」都不知道。现改为 OCR 转文字，
     * 不支持视觉的模型也能读到屏幕上的内容。
     */
    private suspend fun sanitizeImagesForModel(
        messages: List<AgentMessage>,
        supportsVision: Boolean
    ): List<AgentMessage> {
        if (supportsVision) return messages
        val ocrEnabled = generalSettingsRepository.ocrForTextOnlyModels()
        return messages.map { msg ->
            when (msg) {
                is AgentMessage.UserMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(
                        images = emptyList(),
                        content = appendOcrText(
                            msg.content,
                            if (ocrEnabled) describeImages(msg.images) else "",
                            "图片已省略：当前模型不支持图片输入"
                        )
                    )
                is AgentMessage.ToolResultMessage ->
                    if (msg.images.isEmpty()) msg
                    else msg.copy(
                        images = emptyList(),
                        modelResult = appendOcrText(
                            msg.modelResult ?: msg.result,
                            if (ocrEnabled) describeImages(msg.images) else "",
                            null
                        )
                    )
                is AgentMessage.AssistantMessage -> msg
            }
        }
    }

    /** 逐张本地 OCR 并拼成带序号的文字；一张都没认出来时返回空串。 */
    private suspend fun describeImages(images: List<AgentImage>): String =
        images.mapIndexedNotNull { index, image ->
            val text = ocrEngine.recognize(image) ?: return@mapIndexedNotNull null
            val label = if (images.size == 1) "图片文字" else "图片 ${index + 1} 文字"
            "[$label]\n$text"
        }.joinToString("\n\n")

    /** 把 OCR 文字接在原内容后面；没认出来时用 [emptyHint] 兑底，没有就保持原样。 */
    private fun appendOcrText(original: String, ocrText: String, emptyHint: String?): String = when {
        ocrText.isNotBlank() -> if (original.isBlank()) ocrText else "$original\n\n$ocrText"
        emptyHint != null -> original.ifBlank { "（$emptyHint）" }
        else -> original
    }

    /**
     * 压缩轮专用 provider 解析。若用户配置了压缩专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveCompactionFallbackProvider(sessionId: String? = null): AIProvider? {
        val providerId = compactionModelSettingsRepository.getCompactionProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = compactionModelSettingsRepository.getCompactionModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 标题生成专用 provider 解析。若用户配置了标题总结专用模型且 provider 存在、已启用、有 apiKey，
     * 则返回全新的独立 AIProvider 实例；否则返回 null（沿用当前聊天模型）。
     */
    private suspend fun resolveTitleFallbackProvider(sessionId: String?): AIProvider? {
        val providerId = titleModelSettingsRepository.getTitleProviderId().trim()
        if (providerId.isEmpty()) return null
        val model = titleModelSettingsRepository.getTitleModel().trim()
        if (model.isEmpty()) return null
        val config = aiProviderRepository.getProviderById(providerId) ?: return null
        if (!config.isEnabled || !config.hasUsableApiKey) return null
        return createStandaloneProvider(config.copy(selectedModel = model), sessionId)
    }

    /**
     * 为新建会话生成标题：默认跟随当前聊天模型，配置了标题总结专用模型则用之。
     * 提示词来自 [SystemPromptProvider] 的 `agent/title-generator.md`。
     * 生成失败或取不到标题时返回 null（调用方保留临时标题）。
     */
    override suspend fun generateTitle(sessionId: String, request: String): String? = runCatching {
        val provider = resolveTitleFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
        val prompt = promptProvider.resolvePrompt(TITLE_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        val response = try {
            val resp = provider.complete(
                systemPrompt = prompt,
                messages = listOf(AgentMessage.UserMessage(content = request)),
                tools = emptyList()
            )
            usage = resp
            callCompleted = true
            resp
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "title",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
        response.content.trim().take(TITLE_MAX_CHARS).ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成会话标题失败", e)
    }.getOrNull()

    /**
     * 通用一次性调用：系统提示词 + 单条用户文本 ⇒ 模型回复文本。
     * 供记忆蒸馏等后台任务用：不走会话历史与工具循环，也不改任何会话状态。
     */
    override suspend fun completeOnce(
        systemPrompt: String,
        userText: String,
        sessionId: String?
    ): String? = runCatching {
        val provider = getEffectiveProvider(sessionId)
        provider.complete(
            systemPrompt = systemPrompt,
            messages = listOf(AgentMessage.UserMessage(content = userText)),
            tools = emptyList()
        ).content.trim().ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "一次性调用失败", e)
    }.getOrNull()

    override suspend fun generateCommitMessage(diff: String): String? = runCatching {
        if (diff.isBlank()) return@runCatching null
        val provider = getEffectiveProvider(sessionId = null)
        val prompt = promptProvider.resolvePrompt(COMMIT_GENERATOR_FILE)
            .replace(LEADING_COMMENT, "")
        val truncatedDiff = diff.take(12000)
        val resp = provider.complete(
            systemPrompt = prompt,
            messages = listOf(AgentMessage.UserMessage(content = "git diff:\n```diff\n$truncatedDiff\n```")),
            tools = emptyList()
        )
        val line = resp.content.lines().firstOrNull { it.isNotBlank() }?.trim()
            ?.removeSurrounding("`")
            ?.removePrefix("\"")
            ?.removeSuffix("\"")
            ?.trim()
        line?.take(100)?.ifBlank { null }
    }.onFailure { e ->
        FileLogger.w(TAG, "生成提交信息失败", e)
    }.getOrNull()

    /**
     * 会话轮次结束后的记忆兑底：优先用压缩专用模型（轻量、便宜）抽记忆，未配置则回退当前聊天模型。
     * 全静默，任何失败都不影响调用方。
     */
    override suspend fun curateMemory(sessionId: String, projectRoot: String?, transcript: String): Int {
        if (transcript.isBlank()) return 0
        return try {
            val provider = resolveCompactionFallbackProvider(sessionId) ?: getEffectiveProvider(sessionId)
            val saved = memoryCurator.curate(provider, sessionId, projectRoot, transcript)
            if (saved > 0) promptProvider.invalidateMemoryCache(sessionId, projectRoot)
            saved
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FileLogger.w(TAG, "记忆兑现失败: ${e.message}", e)
            0
        }
    }

    private suspend fun runToolStream(
        tool: StreamingAgentTool, 
        toolCall: ToolCall,
        context: AgentContext,
        onEvent: suspend (AgentEvent) -> Unit
    ): ToolRunResult {
        return try {
            withContext(Dispatchers.IO) {
                val live = StringBuilder()
                var lastEmitMs = 0L
                var finalResult: ToolResult? = null
                tool.executeStream(toolCall.arguments, context).collect { ev ->
                    when (ev) {
                        is ToolStreamEvent.Progress -> {
                            live.append(ev.chunk).append('\n')
                            if (live.length > LIVE_TAIL_CHARS) {
                                live.delete(0, live.length - LIVE_TAIL_CHARS)
                            }
                            val now = System.currentTimeMillis()
                            if (now - lastEmitMs >= PROGRESS_INTERVAL_MS) {
                                lastEmitMs = now
                                onEvent(AgentEvent.ToolCallProgress(toolCall.id, toolCall.name, live.toString()))
                            }
                        }
                        is ToolStreamEvent.Completed -> finalResult = ev.result
                    }
                }
                val result = finalResult ?: ToolResult.Error("流式工具未返回结果", "MISSING_STREAM_RESULT")
                val processed = toolOutputStore.process(toolCall.name, toolCall.id, result)
                val images = if (result is ToolResult.Success) result.images else emptyList()
                ToolRunResult(processed.toTransportString(), processed is ToolResult.Error, images = images)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolRunResult(toolError(toolCall, "工具执行失败: ${e.message}", "TOOL_EXECUTION_FAILED"), true)
        }
    }

    /**
     * 把模型直出的图片（base64）落盘到 `~/.aharou/generated-images/`，返回带容器路径的 images
     * 与一一对应的 UI 附件（附件只带路径不含 base64，落库不撑爆数据库行）。
     */
    private suspend fun persistModelImages(images: List<AgentImage>): Pair<List<AgentImage>, List<AgentAttachment>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val persisted = mutableListOf<AgentImage>()
            val attachments = mutableListOf<AgentAttachment>()
            images.forEach { image ->
                runCatching {
                    val estimatedBytes = image.base64Data.length.toLong() * 3 / 4
                    if (estimatedBytes > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
                    if (bytes.isEmpty() || bytes.size > MAX_GENERATED_IMAGE_BYTES) return@runCatching
                    val unique = "${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
                    val targetPath = "$GENERATED_IMAGE_DIR/gen_$unique.${extForMime(image.mimeType)}"
                    fileAccess.writeBytes(targetPath, bytes, overwrite = false)
                    val displayPath = fileAccess.toDisplayPath(targetPath)
                    val localFile = fileAccess.copyToLocal(targetPath)
                    persisted.add(image.copy(path = displayPath))
                    attachments.add(
                        AgentAttachment(
                            fileName = targetPath.substringAfterLast('/'),
                            containerPath = displayPath,
                            localPath = localFile.absolutePath,
                            mimeType = image.mimeType,
                            sizeBytes = bytes.size.toLong(),
                            isImage = true
                        )
                    )
                }
            }
            persisted to attachments
        }

    private fun extForMime(mime: String): String = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        else -> "png"
    }

    private fun checkAndUpdateMode(toolCall: ToolCall, isError: Boolean, currentContext: AgentContext): Pair<AgentContext, Boolean> {
        if (toolCall.name == "planMode" && !isError) {
            val action = (toolCall.arguments["action"] as? JsonPrimitive)?.content?.trim()?.lowercase()
                ?: toolCall.arguments["action"]?.toString()?.replace("\"", "")?.trim()?.lowercase()
            when (action) {
                // 进入 PLAN 时记住当前模式；退出时恢复到它（AUTO→PLAN 的规划往返结束后回到 AUTO 而非 BUILD）。
                "enter" -> if (currentContext.mode != AgentMode.PLAN) {
                    return currentContext.copy(mode = AgentMode.PLAN, modeBeforePlan = currentContext.mode) to true
                }
                "exit" -> if (currentContext.mode == AgentMode.PLAN) {
                    return currentContext.copy(mode = currentContext.modeBeforePlan ?: AgentMode.BUILD) to true
                }
            }
        }
        return currentContext to false
    }

    /**
     * 当前模式提醒：随最新用户消息注入（借鉴 opencode SessionReminders 的思路）。
     * 模式提示词不进 system——一旦切换就要重建 system、打断前缀缓存；
     * 改为消息级提醒：每次用户请求拼在最新用户消息末尾，位置在消息流尾部，前缀保持稳定。
     */
    private fun buildModeReminder(mode: AgentMode): String? = when (mode) {
        AgentMode.PLAN -> promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.AUTO -> promptProvider.resolvePrompt(MODE_REMINDER_AUTO_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
            .let { "【模式提醒】$it" }
        AgentMode.BUILD -> null
    }

    /**
     * 把待送通知注入工具结果：优先作为 transport JSON 顶层的 `notifications` 字段，结果仍是合法 JSON，
     * UI 的 formatToolResult（只读 data/message）与各类结构化解析不受影响。
     * raw 已被模式切换提示等纯文本追加过、不再是合法 JSON 时，退化为文本追加。
     */
    /**
     * 取本次应注入的模式提醒：仅当模式与上次注入时不同（含首次）才返回文本并记录，否则为 null。
     */
    private fun takeModeReminderIfChanged(sessionId: String?, mode: AgentMode): String? {
        if (sessionId == null) return buildModeReminder(mode)
        if (lastInjectedMode[sessionId] == mode) return null
        lastInjectedMode[sessionId] = mode
        return buildModeReminder(mode)
    }

    /** 工具调用成功后拼进 planMode 工具结果的模式状态通知（当轮即可见，无需等下一条用户消息）。 */
    private fun buildModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】计划已获用户批准，你已切换到 BUILD（构建）模式，可以开始执行计划。"
        AgentMode.AUTO -> "\n\n【模式切换】计划已获用户批准，你已恢复到 AUTO（自动）模式，可以开始执行计划（工具调用将自动放行）。"
    }

    /** 用户在外部（界面）手动切换模式时拼进工具结果的提示；与 AI 自切的 [buildModeSwitchNotice] 区分，避免误称「计划已获批准」。 */
    private fun buildExternalModeSwitchNotice(mode: AgentMode): String = when (mode) {
        AgentMode.PLAN -> "\n\n" + promptProvider.resolvePrompt(MODE_REMINDER_PLAN_FILE)
            .replace(LEADING_COMMENT, "")
            .trim()
        AgentMode.BUILD -> "\n\n【模式切换】用户已将模式切换为 BUILD（构建）模式，可以正常执行写操作。"
        AgentMode.AUTO -> "\n\n【模式切换】用户已将模式切换为 AUTO（自动）模式。"
    }

    /**
     * 从 sendFile 工具结果的 `files` 数组提取文件卡片元数据（含宿主本地路径，供 UI 打开文件用）。
     * 任一文件缺关键字段则整体返回空（与 sendFile 的原子语义一致）。
     */
    private fun extractAttachments(result: ToolResult): List<AgentAttachment> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyList()
        val files = data["files"] as? JsonArray ?: return emptyList()
        val attachments = files.mapNotNull { elem ->
            val obj = elem as? JsonObject ?: return@mapNotNull null
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val localPath = obj["local_path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: path.substringAfterLast('/')
            val mimeType = obj["mime_type"]?.jsonPrimitive?.contentOrNull ?: "application/octet-stream"
            AgentAttachment(
                fileName = name,
                containerPath = path,
                localPath = localPath,
                mimeType = mimeType,
                sizeBytes = obj["size_bytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                isImage = obj["is_image"]?.jsonPrimitive?.booleanOrNull ?: mimeType.startsWith("image/")
            )
        }
        return if (attachments.size == files.size) attachments else emptyList()
    }

    /** 从回传给模型的 sendFile 结果中剥离宿主本地路径（模型只应看到容器路径）。 */
    private fun stripAttachments(result: ToolResult): ToolResult {
        val success = result as? ToolResult.Success ?: return result
        val data = success.data as? JsonObject ?: return result
        val strippedFiles = (data["files"] as? JsonArray)?.map { elem ->
            val obj = elem as? JsonObject ?: return@map elem
            JsonObject(obj.toMutableMap().apply { remove("local_path") })
        } ?: return result
        val strippedData = data.toMutableMap().apply {
            this["files"] = JsonArray(strippedFiles)
            this["files_attached"] = JsonPrimitive(true)
        }
        return ToolResult.Success(JsonObject(strippedData))
    }
}
