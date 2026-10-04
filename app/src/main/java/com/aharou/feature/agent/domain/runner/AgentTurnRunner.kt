package com.aharou.feature.agent.domain.runner

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.COMPACTION_FAILURE_TOOL_NAME
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.hasVisibleContent
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/** [AgentTurnRunner.run] 的入参。 */
data class AgentTurnRequest(
    /** 落到哪个会话。界面传「当前会话」，语音通话传工作区里最近聊过的会话。 */
    val sessionId: String,
    /** 落库的用户消息文本。 */
    val text: String,
    /** 真正喂给模型的文本；默认与 [text] 相同。 */
    val modelRequest: String = text,
    val currentFile: String? = null,
    val selectedCode: String? = null,
    val projectRoot: String = "",
    val inputImages: List<AgentImage> = emptyList(),
    val inputAttachments: List<AgentAttachment> = emptyList(),
    /** 非用户手打的请求（斜杠命令、后台通知合并等）：不落用户消息、不动标题。 */
    val isAutoTrigger: Boolean = false,
    /** 子代理等场景已预设标题，跳过首条消息的标题推导与生成。 */
    val skipTitleUpdate: Boolean = false,
)

/**
 * 一轮 agent 请求的编排：准备数据 → 跑 workflow → 把结果落库。
 *
 * 从 [com.aharou.feature.agent.presentation.AIAgentViewModel.executeAgentRequestStream] 抽出来，
 * 让聊天界面与语音通话（前台服务里跑，拿不到界面作用域的 ViewModel）共用同一套历史组装与落库
 * 语义，免得两边各写一份而逐渐分叉。
 *
 * 这里只碰数据——会话、消息、工具、事件；界面状态一概不碰，调用方拿到 [AgentEvent] 流
 * 自己决定怎么展示。
 *
 * 事件在落库**之前**转发给调用方：界面收到事件后会先清掉流式气泡，再轮到落库消息进列表，
 * 否则两者会短暂同屏并存。也正因如此，调用方在 collect 里不要做耗时操作，否则会拖住落库。
 */
@Singleton
class AgentTurnRunner @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val chatSessionDao: ChatSessionDao,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val checkpointManager: CheckpointManager,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val subAgentWriteLease: SubAgentWriteLease,
    private val workspaceRepository: WorkspaceRepository,
    @param:ApplicationContext private val context: Context,
) {

    /** 标题生成这种「跑完再补」的活儿不该拖住本轮，丢到进程级 scope 里。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前工作区路径；工作区还没就绪时为空串。 */
    fun currentWorkspacePath(): String = workspaceRepository.current.value?.path.orEmpty()

    /** 取该工作区最近聊过的会话，没有就建一个。不碰调用方的「当前会话」缓存。 */
    suspend fun ensureSession(workspacePath: String): String {
        sessionUseCase.getMostRecentSessionOfWorkspace(workspacePath)?.let { return it.id }
        val entity = createSessionEntity(workspacePath)
        sessionUseCase.upsertSession(entity)
        return entity.id
    }

    /**
     * 新建会话实体并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    suspend fun createSessionEntity(workspacePath: String): ChatSessionEntity {
        val providerId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
        val model = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
        val effort = if (providerId != null && model != null) {
            modelReasoningEffortRepository.get(providerId, model) ?: ReasoningEffort.DEFAULT.name
        } else {
            ReasoningEffort.DEFAULT.name
        }
        return sessionUseCase.newSessionEntity(
            workspacePath = workspacePath,
            providerId = providerId,
            model = model,
            reasoningEffort = effort,
        )
    }

    /**
     * 跑一轮。冷流，collect 期间完成落库；调用方只消费事件用于展示。
     */
    fun run(turn: AgentTurnRequest): Flow<AgentEvent> = flow {
        val sessionId = turn.sessionId

        // 兼容历史会话：尚未绑定 provider/model 的会话，发消息时固化下来，
        // 免得之后默认模型变动把已有会话带偏。
        val currentSession = sessionUseCase.getSessionById(sessionId)
        if (currentSession != null &&
            (currentSession.providerId.isNullOrBlank() || currentSession.model.isNullOrBlank())
        ) {
            val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId().takeIf { it.isNotBlank() }
            val defaultModel = defaultModelSettingsRepository.getDefaultModel().takeIf { it.isNotBlank() }
            if (defaultProviderId != null && defaultModel != null) {
                sessionUseCase.updateProviderModel(sessionId, defaultProviderId, defaultModel)
            }
        }

        // 必须在插入本次用户消息之前取历史：workflow 会自己把 userRequest 加进去，避免重复。
        val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
        val isFirst = history.isEmpty()

        val userMsgId = UUID.randomUUID().toString()
        if (!turn.isAutoTrigger) {
            messagePersistenceUseCase.persist(
                sessionId,
                MessageRole.USER,
                turn.text,
                id = userMsgId,
                attachments = turn.inputAttachments,
            )
            checkpointManager.createCheckpoint(sessionId, userMsgId, turn.text)
            if (isFirst && !turn.skipTitleUpdate) {
                sessionUseCase.updateTitle(sessionId, sessionUseCase.deriveTitle(turn.text))
                // 后台异步用 LLM 生成更贴切的标题替换临时标题；失败或取不到时保留临时标题
                scope.launch {
                    agentWorkflow.generateTitle(sessionId, turn.text)?.let { sessionUseCase.updateTitle(sessionId, it) }
                }
            }
        } else {
            messagePersistenceUseCase.persist(
                sessionId,
                MessageRole.USER,
                turn.modelRequest,
                id = userMsgId,
                attachments = turn.inputAttachments,
            )
        }
        messagePersistenceUseCase.invalidateHistory(sessionId)
        sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())

        val sessionEntity = sessionUseCase.getSessionById(sessionId)
        val sessionDomain = sessionEntity?.toDomain()
        val mode = sessionDomain?.mode ?: AgentMode.BUILD
        // 子会话的 subagentType 存的是自定义 agent 名；能查到定义时提示词与工具集都按它组装。
        val agentDefinition = sessionEntity?.takeIf { it.parentId != null }
            ?.subagentType
            ?.let { agentDefinitionRepository.findIncludingDisabled(it) }
        val isSub = sessionEntity?.parentId != null

        val agentContext = AgentContext(
            currentFile = turn.currentFile,
            selectedCode = turn.selectedCode,
            projectRoot = turn.projectRoot,
            language = turn.currentFile?.let { detectLanguage(it) },
            history = history,
            inputImages = turn.inputImages,
            sessionId = sessionId,
            inputMessageId = userMsgId,
            lastInputTokens = sessionEntity?.lastInputTokens ?: 0,
            mode = mode,
            modeBeforePlan = sessionDomain?.modeBeforePlan,
            reasoningEffort = sessionDomain?.reasoningEffort?.apiValue,
            agentDefinition = agentDefinition,
            writePaths = subAgentWriteLease.pathsFor(sessionId),
        )

        val allTools = toolRegistry.getAvailableTools()
        val tools = when {
            agentDefinition != null -> {
                val allowed = agentDefinition.filterToolNames(allTools.map { it.name }).toSet()
                allTools.filter { it.name in allowed }
            }
            isSub -> allTools.filterNot { it.name == AgentDefinition.NESTED_TOOL }
            else -> allTools.filterNot { it.name == AgentDefinition.PARENT_MESSAGE_TOOL }
        }

        // 工具参数预览：结束时只有部分事件带 argsPreview，缺失时回退到开始时记下的那份
        val toolArgsByMsgId = mutableMapOf<String, String>()

        agentWorkflow.executeEvents(
            userRequest = turn.modelRequest,
            context = agentContext,
            tools = tools,
        ).collect { event ->
            // 先转发、后落库：调用方先清流式气泡，落库消息才不会与它同屏并存。
            emit(event)
            when (event) {
                is AgentEvent.CompactionFailed -> {
                    // 落库为无配对的 TOOL 消息：界面渲染失败卡片，buildHistory 回放时自动丢弃
                    messagePersistenceUseCase.persist(
                        sessionId,
                        MessageRole.TOOL,
                        event.reason,
                        toolName = COMPACTION_FAILURE_TOOL_NAME,
                        isError = true,
                    )
                }

                is AgentEvent.AssistantText -> {
                    val normalized = if (event.content.hasVisibleContent()) event.content else ""
                    val reasoning = event.reasoning.takeIf { it.hasVisibleContent() }
                    messagePersistenceUseCase.persist(
                        sessionId,
                        MessageRole.ASSISTANT,
                        normalized,
                        id = event.messageId.ifBlank { UUID.randomUUID().toString() },
                        toolCalls = event.toolCalls,
                        reasoning = reasoning,
                        signature = event.signature.ifEmpty { null },
                        thinkingBlocksJson = event.thinkingBlocksJson.ifEmpty { null },
                        attachments = event.attachments,
                        inputTokens = event.inputTokens,
                        outputTokens = event.outputTokens,
                        cachedInputTokens = event.cachedInputTokens,
                    )
                    if (event.inputTokens > 0 || event.outputTokens > 0) {
                        // 同步写库：工具循环下一轮 CallLlm 前会重读 lastInputTokens 判断压缩，
                        // 异步写库可能读到压缩前的旧大值导致重复触发压缩。
                        runCatching {
                            chatSessionDao.addTokenUsage(sessionId, event.inputTokens, event.outputTokens)
                            if (event.inputTokens > 0) {
                                chatSessionDao.updateLastInputTokens(sessionId, event.inputTokens)
                            }
                        }
                    }
                    event.persisted?.complete(Unit)
                }

                is AgentEvent.ToolCallStarted -> {
                    val msgId = "tool_${event.id}"
                    toolArgsByMsgId[msgId] = event.argsPreview
                    messagePersistenceUseCase.persist(
                        sessionId,
                        MessageRole.TOOL,
                        "${SessionUseCase.PENDING_TOOL_MARKER} " +
                            context.getString(R.string.agent_tool_executing, event.toolName),
                        id = msgId,
                        toolCallId = event.id,
                        toolName = event.toolName,
                        toolArgs = event.argsPreview,
                        isError = false,
                    )
                }

                is AgentEvent.ToolCallFinished -> {
                    val msgId = "tool_${event.id}"
                    messagePersistenceUseCase.persist(
                        sessionId,
                        MessageRole.TOOL,
                        event.result,
                        id = msgId,
                        toolCallId = event.id,
                        toolName = event.toolName,
                        toolArgs = event.argsPreview ?: toolArgsByMsgId[msgId],
                        isError = event.isError,
                        attachments = event.attachments,
                    )
                    toolArgsByMsgId.remove(msgId)
                    event.persisted?.complete(Unit)
                }

                is AgentEvent.Failed -> if (isSub) {
                    sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                        subAgentEventBus.emit(
                            SubAgentEvent(
                                subSessionId = sessionId,
                                parentSessionId = parentId,
                                type = SubAgentEventType.FAILED,
                                detail = event.error,
                            )
                        )
                    }
                }

                AgentEvent.Completed -> if (isSub) {
                    sessionUseCase.getSessionById(sessionId)?.parentId?.let { parentId ->
                        subAgentEventBus.emit(
                            SubAgentEvent(
                                subSessionId = sessionId,
                                parentSessionId = parentId,
                                type = SubAgentEventType.COMPLETED,
                            )
                        )
                    }
                }

                else -> Unit
            }
        }

        sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
    }

    private fun detectLanguage(filePath: String): String =
        when (filePath.substringAfterLast(".").lowercase()) {
            "kt", "kotlin" -> "kotlin"
            "java" -> "java"
            "dart" -> "dart"
            "py" -> "python"
            "js" -> "javascript"
            "ts" -> "typescript"
            "tsx" -> "typescript"
            "jsx" -> "javascript"
            "go" -> "go"
            "rs" -> "rust"
            else -> "text"
        }
}
