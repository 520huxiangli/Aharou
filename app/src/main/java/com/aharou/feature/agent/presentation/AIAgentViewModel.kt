package com.aharou.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import androidx.compose.runtime.mutableStateMapOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.MainActivity
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.core.util.toUserMessage
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.entity.AgentTaskStatus
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.domain.model.TodoItem
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.container.ContainerInitState
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.settings.data.repository.AgentSoundSettingsRepository
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import com.aharou.feature.settings.data.repository.ModelReasoningEffortRepository
import com.aharou.feature.settings.data.repository.StartupSessionMode
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.model.ChatSession
import com.aharou.feature.agent.domain.model.ReasoningEffort
import com.aharou.feature.agent.domain.inbox.InboxDirectory
import com.aharou.feature.agent.domain.inbox.SharedInboxDelivery
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationFormatter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.mention.FileMentionController
import com.aharou.feature.agent.domain.mention.FileMentionState
import com.aharou.feature.agent.domain.mention.fileMentionQuery
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.core.watch.FileChangeHub
import com.aharou.core.watch.asDirtySignal
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentResultInspector
import com.aharou.feature.agent.domain.schedule.ScheduledPendingRunner
import com.aharou.feature.agent.domain.schedule.ScheduledRunRequest
import com.aharou.feature.agent.domain.runner.AgentTurnCompletion
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.terminal.domain.TabFinishedEvent
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import com.aharou.feature.terminal.domain.TerminalSessionManager
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import com.aharou.feature.terminal.domain.renewWakeLockUntilIdle
import com.aharou.feature.terminal.domain.takeTailLines
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.isValidFileEntryName
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.ToolArgsProgress
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalChoice
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalManager
import com.aharou.feature.agent.domain.tool.mode.PlanApprovalRequest
import com.aharou.feature.agent.domain.tool.question.AskUserQuestionManager
import com.aharou.feature.agent.domain.tool.question.UserQuestionAnswer
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.MessageArchiveStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.aharou.feature.backup.domain.BackupManager
import com.aharou.feature.agent.domain.command.SlashCommand
import com.aharou.feature.agent.domain.command.SlashCommandContext
import com.aharou.feature.agent.domain.command.SlashCommandRegistry
import com.aharou.feature.agent.domain.command.SlashCommandRegistry.ResolvedCommand
import com.aharou.feature.agent.domain.skill.Skill
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.domain.sidequestion.SideQuestionRunner
import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import com.aharou.feature.agent.presentation.component.decodeAttachmentJson
import com.aharou.feature.agent.presentation.component.PastedText
import com.aharou.feature.agent.presentation.component.RewindOption
import com.aharou.feature.agent.presentation.component.SubAgentCardState
import com.aharou.feature.agent.presentation.component.SubAgentCardBridge
import com.aharou.feature.agent.presentation.component.SubAgentCardStatus
import com.aharou.feature.agent.presentation.component.isTurnHead
import com.aharou.feature.agent.presentation.turnKeyOf
import com.aharou.feature.agent.presentation.notification.AgentCompletedNotifier
import com.aharou.feature.agent.presentation.notification.AgentPermissionNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class AIAgentViewModel @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
    private val toolRegistry: ToolRegistry,
    private val agentMessageDao: AgentMessageDao,
    private val agentTaskDao: AgentTaskDao,
    private val chatSessionDao: ChatSessionDao,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val modelReasoningEffortRepository: ModelReasoningEffortRepository,
    private val toolPermissionManager: ToolPermissionManager,
    private val askUserQuestionManager: AskUserQuestionManager,
    private val containerEngine: LinuxContainerEngine,
    private val sessionUseCase: SessionUseCase,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val messageArchiveStore: MessageArchiveStore,
    private val planApprovalManager: PlanApprovalManager,
    private val terminalSessionManager: TerminalSessionManager,
    private val terminalSessionProvider: TerminalSessionProvider,
    private val slashCommandRegistry: SlashCommandRegistry,
    private val fileMentionController: FileMentionController,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val agentCompletedNotifier: AgentCompletedNotifier,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val subAgentRunLifecycle: com.aharou.feature.agent.domain.subagent.SubAgentRunLifecycle,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val sharedInboxDelivery: SharedInboxDelivery,
    private val inboxDirectory: InboxDirectory,
    private val agentPermissionNotifier: AgentPermissionNotifier,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentTurnRunner: AgentTurnRunner,
    private val ruleFileRepository: com.aharou.feature.agent.domain.rule.RuleFileRepository,
    private val sideQuestionRunner: SideQuestionRunner,
    private val turnEndBridge: AgentTurnEndBridge,
    private val sessionPanels: SessionPanelController,
    private val composer: ComposerStateController,
    private val sessionForker: SessionForker,
    private val sessionQueue: SessionQueueController,
    private val usageReport: UsageReportController,
    private val chatSearch: ChatSearchController,
    private val turnRuntime: TurnRuntimeStateController,
    private val runRegistry: SessionRunRegistry,
    private val stopController: SessionStopController,
    private val messageRouter: AgentMessageRouter,
    private val turnHandlers: TurnEventHandlers,
    private val scheduledTaskDao: ScheduledTaskDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val fileTreeRoots: ContainerTreeRootProvider,
    private val fileBrowse: FileBrowseController,
    /** Aharou：浏览器池 —— 聊天页“自动围观”面板与 browser 工具共用同一池。 */
    val browserTabPool: com.aharou.feature.browser.BrowserTabPool,
    /** Aharou：影子屏控制器 —— 聊天页小屏幕实时预览与 vscreen 工具共用。 */
    private val vdController: com.aharou.feature.agent.domain.vdisplay.VdController,
    private val workspaceRepository: com.aharou.feature.workspace.data.repository.WorkspaceRepository,
    /** 插件宿主 API 与聊天页之间的单消费者队列；本 VM 是唯一消费者。 */
    private val pluginChatRequestBus: com.aharou.feature.agent.domain.session.AgentChatRequestBus,
    /** 跨页面共享的轮次运行状态，供插件页面判断本轮是否还在跑。 */
    private val pluginRunStateStore: com.aharou.feature.agent.domain.session.AgentRunStateStore,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val suppressedSubAgentStops: MutableSet<String> get() = runRegistry.suppressedSubAgentStops

    /** 运行期状态共享在 [SessionRunRegistry]，这里只做别名，读写写法与原来一致。 */
    private val sessionJobs: MutableMap<String, Job> get() = runRegistry.jobs
    private val stoppingSessions: MutableMap<String, kotlinx.coroutines.CompletableDeferred<Unit>> get() = runRegistry.stopping

    /**
     * 「影子屏」取一帧。缩略图轮播用 [sampleSize]=3 就够（每 1.5s 一帧，避免整图进内存）；
     * 工具详情面板里的图要按屏宽铺开，用 2 才看得清。
     */
    suspend fun captureVdFrame(sampleSize: Int = 3): android.graphics.Bitmap? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val info = vdController.state.value ?: vdController.refresh()
                    ?: return@runCatching null
                val (_, file) = vdController.screenshot(info)
                val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sampleSize }
                android.graphics.BitmapFactory.decodeFile(file.absolutePath, opts)
            }.getOrNull()
        }

    /**
     * agent 执行期间持有的 CPU 唤醒锁：熄屏后系统会挂起进程，使流式响应中断、工具调用卡死。
     * 不计数（setReferenceCounted(false)），多会话共用一把锁，最后一个任务结束时统一释放。
     */
    private val wakeLock by lazy {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aharou:AgentWorkflow")
            .apply { setReferenceCounted(false) }
    }

    /** 设置里用户手动开启的常驻保活；agent 任务收尾时不能把它一并关掉。 */
    @Volatile
    private var userKeepaliveEnabled = false

    /** WakeLock 续期协程：单次 acquire 的超时覆盖不了超长任务，需在到期前重新续上。 */
    private var wakeLockRenewJob: Job? = null

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /**
     * 用户主动切换会话的信号（仅 [selectSession] 触发，冷启动/恢复时的 id 变化不发）。
     * 聊天面板据此清空待发附件，避免把上一个会话的附件带过去。
     */
    private val _sessionSwitchEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionSwitchEvents: SharedFlow<Unit> = _sessionSwitchEvents.asSharedFlow()

    val currentSessionTodoItems: StateFlow<List<TodoItem>> =
        sessionPanels.todoItems(_currentSessionId, viewModelScope)

    private val _agentStates: MutableStateFlow<Map<String, AgentUIState>> get() = runRegistry.agentStates
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

    /**
     * 还有 agent 协程在跑的会话。与 [agentStates] 不同：软打断会把会话状态立刻打回 Idle（界面立刻
     * 放开），但协程仍在后台跑完当前这一步，长按强打断的入口只能靠这个状态兜住，否则点过一次停止
     * 之后就再也强打断不了。
     */
    private val _activeAgentSessions: MutableStateFlow<Set<String>> get() = runRegistry.activeSessions
    val activeAgentSessions: StateFlow<Set<String>> = _activeAgentSessions.asStateFlow()

    /**
     * 各会话「最后一轮是否正常完成」：只有正常收尾（收到完成标记）才在轮末回复下挂「复制 / 更多」
     * 按钮；主动暂停 / 出错 / 未开始都不挂，避免按钮挂在半截输出上。
     */
    private val _completedSessions = MutableStateFlow<Set<String>>(emptySet())
    val completedSessions: StateFlow<Set<String>> = _completedSessions.asStateFlow()

    val agentState: StateFlow<AgentUIState> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(AgentUIState.Idle)
            else _agentStates.map { it[id] ?: AgentUIState.Idle }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentUIState.Idle)

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        _agentStates.value = _agentStates.value + (sessionId to state)
        syncRuntimeActive()
    }

    /** 同步「整轮任务在跑」到全局运行状态（悬浮窗按整轮门控，避免按工具开关导致闪烁）。 */
    private fun syncRuntimeActive() {
        val active = _agentStates.value.values.any {
            it is AgentUIState.Loading || it is AgentUIState.Streaming
        }
        com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus.setActive(active)
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30
    // 已真正加载过的分页上限（key = 会话 id）；写入发生在 Default 调度器（见 messagesState），故用并发映射。
    private val loadedMessageLimits = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 聊天记录搜索定位时预留的分页余量（条数上限与防抖在 [ChatSearchController]）。 */
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    /** 草稿 / 粘贴块 / 待发附件的状态与读写都在 [ComposerStateController]，这里只转发。 */
    val inputDraft: StateFlow<String> = composer.inputDraft(_currentSessionId, viewModelScope)

    /** 会话是否存在；插件发消息前用它拦掉已删会话。 */
    suspend fun sessionExists(sessionId: String): Boolean =
        sessionUseCase.getSessionById(sessionId) != null

    fun updateInputDraft(text: String) =
        composer.updateDraft(_currentSessionId.value, text, viewModelScope)

    fun clearInputDraft() = composer.clearDraft(_currentSessionId.value)

    // ── 输入框「@ 引用工作区文件」：薄转发，逻辑在 FileMentionController ──
    val fileMentionState: StateFlow<FileMentionState> = fileMentionController.state
    fun requestFileMention(text: String) {
        fileMentionController.onInputChanged(text, currentSessionWorkspace.value, viewModelScope)
    }
    fun dismissFileMention() = fileMentionController.dismiss()
    fun matchFileMentions(query: String): List<String> = fileMentionController.match(query)
    /** 选中候选：把 `@查询词` 整段替换成 `@相对路径 `（带尾空格，避免继续被当查询词过滤）。 */
    fun applyFileMention(relativePath: String) {
        val text = inputDraft.value
        val at = fileMentionQuery(text)?.let { text.lastIndexOf("@$it") } ?: -1
        dismissFileMention()
        updateInputDraft(if (at >= 0) text.substring(0, at) + "@$relativePath " else "$text@$relativePath ")
    }

    val pastedTexts: StateFlow<List<PastedText>> = composer.pastedTexts(_currentSessionId, viewModelScope)

    /** 待发送附件（含外部分享投递的）；「活得比面板长」与「切会话只留分享项」的取舍见 [ComposerStateController]。 */
    internal val pendingAttachments: StateFlow<List<PendingUploadAttachment>>
        get() = composer.pendingAttachments

    internal fun addPendingAttachments(items: List<PendingUploadAttachment>) =
        composer.addPendingAttachments(items)

    internal fun addSharedAttachments(items: List<PendingUploadAttachment>) =
        composer.addSharedAttachments(items)

    internal fun setPendingAttachments(items: List<PendingUploadAttachment>) =
        composer.setPendingAttachments(items)

    internal fun removePendingAttachmentAt(index: Int) = composer.removePendingAttachmentAt(index)

    internal fun attachWorkspaceEntryToInput(path: String) = composer.attachWorkspaceEntry(path, viewModelScope)

    internal fun attachEditorSelectionToInput(sourcePath: String, text: String) =
        composer.attachEditorSelection(text, viewModelScope)

    internal suspend fun readAttachmentText(containerPath: String): String? =
        composer.readAttachmentText(containerPath)

    /** 缓冲 [text]，返回顶替它的 `[Pasted#N]` 标记（实现见 [ComposerStateController]）。 */
    fun stashPastedText(text: String): String =
        composer.stashPastedText(text, _currentSessionId.value, viewModelScope) { _currentSessionId.value }

    fun removePastedText(id: Int) = composer.removePastedText(id, _currentSessionId.value)

    /** 发送时把标记还原成原文；[PastedText] 已被用户删掉的标记原样保留。 */
    fun expandPastes(text: String): String = composer.expandPastes(text, _currentSessionId.value)

    /**
     * 工具调用（分组头与单条工具卡片）的手动展开态：key = 分组 key（`toolgroup:<组内首条消息 id>`）
     * 或单条消息 id。
     *
     * 放在 ViewModel 而不是组合里：窄窗下打开设置 / 终端 / Git / 编辑器都是全屏路由，聊天页整棵
     * 组合被 dispose，`remember` 的 map 与按 message.id 的 remember 会一起丢——展开过的工具
     * 一离开视线（滚出屏幕被回收、切页返回）就缩回默认态。这里按 App 进程的内存保留，
     * 会话间互不影响（key 取消息 id，全局唯一），不落盘。
     */
    val toolExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次手动展开/收起（取值由调用方按当前可见态取反后传入）。 */
    fun setToolExpanded(key: String, expanded: Boolean) {
        toolExpansionOverrides[key] = expanded
    }

    /**
     * 整轮任务折叠（外层「执行中 / 已完成」头）的手动展开态：key = `turn:<轮首消息 id>`。
     *
     * 与 [toolExpansionOverrides] 同款：按 App 进程内存保留、不落盘。没记录时按「末轮运行中默认展开、
     * 其余默认收起」推默认值（见 ChatItemBuilders.buildChatItems 的 activeTurnKey）；一旦用户手动点过，
     * 就以其选择为准，任务完成自动收起也不会覆盖它。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次整轮折叠的手动展开/收起。 */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。状态本体在 [TurnRuntimeStateController]。
     */
    val reasoningExpansionOverrides get() = turnRuntime.reasoningExpansionOverrides

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /**
     * 子代理派发卡片的状态，key = 子会话 id（即 `agent_tasks` 主键，与 task 结果里的 id 一致）。
     *
     * 事实源是持久化的任务行：进程重启后内存里什么也不剩，卡片照样能从库里读到真实终态。
     * 事件总线只负责「比落库快一步」地把状态翻过去，详见 [noteSubAgentCardEvent]。
     */
    private val subAgentCardLock = Any()
    private val subAgentCardVersions = mutableMapOf<String, Long>()
    private val _subAgentCards = MutableStateFlow<Map<String, SubAgentCardState>>(emptyMap())
    internal val subAgentCards: StateFlow<Map<String, SubAgentCardState>> = _subAgentCards.asStateFlow()

    /** 卡片向宿主拿状态 / 触发读库 / 进子会话的窄接口，聊天页只把它下发到 CompositionLocal。 */
    internal val subAgentCardBridge = SubAgentCardBridge(
        cards = subAgentCards,
        refresh = ::refreshSubAgentCard,
        openSession = ::selectSession,
    )

    /** 按持久化任务行刷新一张卡片（卡片出现在屏上时、以及子代理结束时调）。 */
    fun refreshSubAgentCard(subSessionId: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { loadSubAgentCard(subSessionId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "刷新子代理卡片失败: $subSessionId", e)
            }
        }
    }

    private suspend fun loadSubAgentCard(subSessionId: String) {
        val revision = synchronized(subAgentCardLock) { subAgentCardVersions[subSessionId] ?: 0L }
        val task = agentTaskDao.getById(subSessionId) ?: return
        // 结论摘要与改动文件不另造一套：走与收尾通知、task(read) 同一个检查器。
        val inspection = runCatching {
            SubAgentResultInspector.inspect(subSessionId, messagePersistenceUseCase.getMessagesOnce(subSessionId))
        }.getOrNull()
        val persisted = SubAgentCardState(
            status = subAgentCardStatusOf(task.status),
            summary = inspection?.conclusion?.takeIf { it.isNotBlank() } ?: task.result,
            changedFiles = inspection?.changedFiles.orEmpty(),
        )
        if (agentTaskDao.getById(subSessionId)?.updatedAt != task.updatedAt) return
        synchronized(subAgentCardLock) {
        if ((subAgentCardVersions[subSessionId] ?: 0L) != revision) return
        _subAgentCards.update { cards ->
            val prev = cards[subSessionId]
            // 事件比落库快：终态事件已经先到、任务行还写着 RUNNING 时，别把卡片退回「运行中」。
            val status = when {
                subSessionId in subAgentEventBus.activeSubSessionIds.value -> SubAgentCardStatus.RUNNING
                prev != null && prev.status != SubAgentCardStatus.RUNNING && persisted.status == SubAgentCardStatus.RUNNING -> prev.status
                else -> persisted.status
            }
            cards + (subSessionId to persisted.copy(status = status))
        }
        }
    }

    /** 子代理事件即时翻卡片状态：落库慢一步，但「跑完了」要让卡片当场变。 */
    private fun noteSubAgentCardEvent(event: SubAgentEvent) {
        val status = when (event.type) {
            SubAgentEventType.SPAWNED, SubAgentEventType.RUN_STARTED -> SubAgentCardStatus.RUNNING
            SubAgentEventType.COMPLETED -> SubAgentCardStatus.COMPLETED
            SubAgentEventType.FAILED -> SubAgentCardStatus.FAILED
            SubAgentEventType.STOPPED, SubAgentEventType.DELETED -> SubAgentCardStatus.CANCELLED
            else -> return
        }
        synchronized(subAgentCardLock) {
        subAgentCardVersions[event.subSessionId] = (subAgentCardVersions[event.subSessionId] ?: 0L) + 1
        _subAgentCards.update { cards ->
            val prev = cards[event.subSessionId]
            cards + (event.subSessionId to (prev ?: SubAgentCardState()).copy(status = status))
        }
        }
        // 终态还要一次读库：结果摘要与改动文件只有子会话消息里有。
        if (status != SubAgentCardStatus.RUNNING) refreshSubAgentCard(event.subSessionId)
    }

    /** `agent_tasks.status` → 卡片状态；未知取值按仍在跑来兜（宁可多显示一次运行中，也别谎报已完成）。 */
    private fun subAgentCardStatusOf(raw: String): SubAgentCardStatus = when (raw) {
        AgentTaskStatus.COMPLETED.name -> SubAgentCardStatus.COMPLETED
        AgentTaskStatus.FAILED.name -> SubAgentCardStatus.FAILED
        AgentTaskStatus.CANCELLED.name -> SubAgentCardStatus.CANCELLED
        AgentTaskStatus.INTERRUPTED.name -> SubAgentCardStatus.INTERRUPTED
        else -> SubAgentCardStatus.RUNNING
    }

    /**
     * 流式思考（还没落库）的展开态 key。思考一落库就换成了新的消息 id，
     * 临时态由 [TurnRuntimeStateController.migrateStreamingReasoningExpansion] 迁过去。
     */
    fun streamingReasoningKey(sessionId: String?): String = turnRuntime.streamingReasoningKey(sessionId)

    /**
     * 每会话「消息 id → 轮 key」，与 [turnKeyOf] 同口径，供聊天页按轮折叠/列表锚点使用。
     *
     * 只由 [reconcileTurnKeys] 维护；会话切走就整张表丢掉（见 init），否则会话越切越多。
     */
    private val _turnMessageKeys = mutableStateMapOf<String, Map<String, String>>()

    /** 当前会话的「消息 id → 轮 key」（没有记录时为空表）。 */
    fun turnKeysOf(sessionId: String?): Map<String, String> = sessionId?.let { _turnMessageKeys[it] }.orEmpty()

    /**
     * 把「消息 id → 轮 key」与当前消息窗口对齐。
     *
     * 消息是按条数截断加载的，窗口边界常切在某一轮中间：那一截过程先挂在块尾生成的
     * `turn:head:<块尾 id>` 折叠头下。分页把轮首用户消息补进来后，同一批消息改归到一个正式轮里，
     * 若按轮首重新生成 `turn:<轮首 id>`，用户之前点开的整轮折叠（[turnExpansionOverrides]）与
     * LazyColumn 的 item key 一起失效——展开态缩回去、列表锚点跳一下。所以给每个会话留一份
     * 消息到轮 key 的映射：轮里已有旧 key 就沿用，没记录才用轮首 id 生成。
     */
    fun reconcileTurnKeys(sessionId: String, messages: List<AgentUIMessage>) {
        if (messages.isEmpty()) return
        val existing = _turnMessageKeys[sessionId].orEmpty()
        val next = LinkedHashMap<String, String>(messages.size)
        val firstTurn = messages.indexOfFirst { it.isTurnHead() }
        if (firstTurn != 0) {
            val end = if (firstTurn < 0) messages.lastIndex else firstTurn - 1
            val tail = messages[end]
            val key = existing[tail.id] ?: if (firstTurn < 0) {
                leadingHeadSessionKey(sessionId)
            } else {
                leadingHeadKey(tail)
            }
            for (i in 0..end) next[messages[i].id] = key
        }
        var i = if (firstTurn < 0) messages.size else firstTurn
        while (i < messages.size) {
            var j = i + 1
            while (j < messages.size && !messages[j].isTurnHead()) j++
            val key = existing[messages[i].id]
                ?: (i + 1 until j).firstNotNullOfOrNull { existing[messages[it].id] }
                ?: turnKeyOf(messages[i].id)
            for (k in i until j) next[messages[k].id] = key
            i = j
        }
        if (next != existing) _turnMessageKeys[sessionId] = next
    }

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val state = messagesState.value
        if (state.sessionId != sid || !state.loaded || !state.hasMore) return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        // 上次抬升的上限还没查完（loadedMessageLimits 未跟上）时直接返回，拦住快速滚动连发的重复全表查询。
        if (loadedMessageLimits[sid] != currentLimit) return
        // 按当前上限比例递增（封顶 90）：折叠后可见项很少时，定步长 30 要连续补载很多次。
        _messageLimit.value = _messageLimit.value +
            (sid to (currentLimit + (currentLimit / 2).coerceIn(30, 90)))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        fileMentionController.dismiss()  // 切工作区：@ 候选菜单先收起（清单按工作区惰性失效）
        // 切到新工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根）。
        fileBrowse.onWorkspaceChanged(path)
        // 搜索限定当前工作区，切区后旧结果与待定位消息无意义，一并清空。
        chatSearch.clear()
    }

    /**
     * 当前会话绑定的工作区路径；会话没绑定（老会话）或还没选会话时回退当前选中的工作区。
     *
     * 界面拿它当 projectRoot：文件落哪个工作区跟会话走，不跟界面上选中的工作区走。
     */
    val currentSessionWorkspace: StateFlow<String> = combine(
        _currentSessionId.flatMapLatest { id ->
            if (id == null) flowOf("")
            else chatSessionDao.getByIdFlow(id).map { it?.workspacePath.orEmpty() }
        },
        _currentWorkspace
    ) { sessionPath, selected ->
        sessionPath.ifBlank { selected }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val sessions: StateFlow<List<ChatSession>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyList())
            else chatSessionDao.getRootSessionsByWorkspace(path)
                .map { list -> list.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * 会话按工作区分组（文件夹 = 工作区 = 仓库）。侧边栏要看其他文件夹里有什么，
     * 而 [sessions] 只含当前工作区，所以这里单独走一次全量查询。
     */
    val sessionsByWorkspace: StateFlow<Map<String, List<ChatSession>>> = chatSessionDao
        .getAllRootSessions()
        .map { list -> list.map { it.toDomain() }.groupBy { it.workspacePath } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * 所有根会话的子代理，按父会话 id 分组，供侧边栏会话行就地展开。
     * 用全量查询而非逐行惰加载：同一张表一次读完，避开每展开一行开一个 Flow 的订阅风暴。
     */
    val subSessionsByParent: StateFlow<Map<String, List<ChatSession>>> = _currentWorkspace
        .flatMapLatest { path ->
            if (path.isBlank()) flowOf(emptyMap())
            else chatSessionDao.getAllSessionsByWorkspace(path).map { list ->
                list.filter { it.parentId != null }
                    .groupBy({ it.parentId!! }, { it.toDomain() })
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ── 聊天记录全局搜索（限当前工作区）：状态与查询在 [ChatSearchController] ──
    val chatSearchQuery: StateFlow<String> get() = chatSearch.query
    val chatSearchState: StateFlow<ChatSearchState> = chatSearch.state(_currentWorkspace, viewModelScope)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> get() = chatSearch.pendingScroll

    fun updateChatSearchQuery(query: String) = chatSearch.updateQuery(query)

    fun clearChatSearch() = chatSearch.clear()

    /**
     * 打开一条搜索命中：切到对应会话，把该会话分页上限抬到足够包含目标消息，并登记待定位消息。
     * 实际滚动由聊天页在消息就绪后完成。搜索词与结果保留，重开侧边栏仍停在结果列表。
     */
    fun openChatSearchHit(hit: ChatSearchHit) {
        selectSession(hit.sessionId)
        viewModelScope.launch {
            val timestamp = withContext(Dispatchers.IO) {
                agentMessageDao.getMessageById(hit.messageId)?.timestamp
            }
            if (timestamp != null) {
                val needed = withContext(Dispatchers.IO) {
                    agentMessageDao.countMessagesFromTimestamp(hit.sessionId, timestamp)
                } + messageLimitMargin
                val current = _messageLimit.value[hit.sessionId] ?: defaultLimit
                if (needed > current) {
                    _messageLimit.value = _messageLimit.value + (hit.sessionId to needed)
                }
            }
            chatSearch.markPendingScroll(hit.sessionId, hit.messageId)
        }
    }

    // ── 文件浏览（侧边栏「文件」Tab）：状态与操作整块在 [FileBrowseController] ──
    val expandedPaths: StateFlow<Set<String>> get() = fileBrowse.expandedPaths
    val expandingPath: StateFlow<String?> get() = fileBrowse.expandingPath
    val fileOpPaths: StateFlow<Set<String>> get() = fileBrowse.fileOpPaths
    val browseState: StateFlow<FileBrowseState> get() = fileBrowse.browseState
    val browseClipboard: StateFlow<BrowseClipboard?> get() = fileBrowse.browseClipboard
    val pasteConflict: StateFlow<Pair<String, String>?> get() = fileBrowse.pasteConflict

    fun refreshBrowse() = fileBrowse.refreshBrowse()

    fun toggleExpand(path: String) = fileBrowse.toggleExpand(path)

    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) =
        fileBrowse.createBrowseFile(parent, name, onResult)

    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) =
        fileBrowse.createBrowseFolder(parent, name, onResult)

    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        fileBrowse.renameBrowseEntry(path, newName, onResult)

    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        fileBrowse.deleteBrowseEntry(path, onResult)

    fun copyBrowseEntry(path: String, name: String) = fileBrowse.copyBrowseEntry(path, name)

    fun cutBrowseEntry(path: String, name: String) = fileBrowse.cutBrowseEntry(path, name)

    fun clearBrowseClipboard() = fileBrowse.clearBrowseClipboard()

    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) =
        fileBrowse.pasteBrowseEntry(targetDir, onResult)

    fun pasteBrowseEntryOverwrite() = fileBrowse.pasteBrowseEntryOverwrite()

    fun clearPasteConflict() = fileBrowse.clearPasteConflict()

    fun consumePendingScroll() = chatSearch.consumePendingScroll()


    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

    init {
        // 轮 key 表只服务当前会话：切走就丢，否则长会话切来切去会一直往上积（每条消息一条）。
        viewModelScope.launch {
            _currentSessionId.collect { id ->
                val stale = _turnMessageKeys.keys.filter { it != id }
                stale.forEach { _turnMessageKeys.remove(it) }
            }
        }
        // MCP 工具表跟着「当前会话所属工作区」走：跟着全局活动工作区时，在 Aharou 工作区开着的时候，
        // 小染工作区的会话就拿不到它自己的项目级 server（桥接 bot 典型）。
        viewModelScope.launch {
            currentSessionState.collect { session ->
                session?.workspacePath?.takeIf { it.isNotBlank() }?.let { mcpManager.setActiveWorkspace(it) }
            }
        }
    }

    /** 当前会话的思考强度（默认 MEDIUM）。 */
    val currentSessionReasoningEffort: StateFlow<ReasoningEffort> =
        currentSessionState.map { it?.reasoningEffort ?: ReasoningEffort.DEFAULT }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ReasoningEffort.DEFAULT)

    /** 当前会话绑定的 providerId/model（null 表示未绑定，回退全局 active provider）。 */
    val currentSessionProviderModel: StateFlow<Pair<String?, String?>> =
        currentSessionState.map { s -> (s?.providerId ?: "") to (s?.model ?: "") }
            .stateIn(viewModelScope, SharingStarted.Eagerly, Pair(null, null))

    /** 消息变体：每个变体组当前展示的版本在组内排序后的下标（见 [applyVariantSelection]）。 */
    private val _variantSelections = MutableStateFlow<Map<String, Int>>(emptyMap())

    /**
     * 当前会话的消息状态：会话切换时自动切换到对应历史，并携带所属会话 id 与 loaded 标志，
     * 使 UI 能区分「切换/冷启动加载中」与「空会话」——避免先闪 Welcome 或上一个会话的消息再突然刷新。
     * 过滤掉「纯工具调用」的空助手行（content 为空、仅用于回放配对，不应显示为气泡）。
     */
    val messagesState: StateFlow<ChatMessagesState> = combine(
        _currentSessionId,
        _messageLimit,
        _variantSelections
    ) { id, limitMap, selections -> Triple(id, limitMap[id] ?: defaultLimit, selections) }
        .flatMapLatest { (id, limit, selections) ->
            if (id == null) flowOf(ChatMessagesState(null, emptyList(), loaded = false))
            else messagePersistenceUseCase.messagesFlow(id, limit).map { list ->
                ChatMessagesState(
                    sessionId = id,
                    messages = applyVariantSelection(
                        messagePersistenceUseCase.restoreAll(list).filterNot {
                            it.role == MessageRole.ASSISTANT.name &&
                                !it.content.hasVisibleContent() &&
                                it.reasoning.isNullOrEmpty()
                        },
                        selections
                    ),
                    loaded = true,
                    hasMore = list.size >= limit,
                    isLoadingMore = false,
                    limit = limit
                )
            }.onEach { loadedMessageLimits[id] = limit }
        }
        // restoreAll 内部已逐条切 Dispatchers.IO，这里用 Default 承接附件 JSON 解析等 CPU 工作，避免嵌套线程切换。
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatMessagesState(null, emptyList(), loaded = false))

    /**
     * 变体选择：同一 [AgentMessageEntity.variantGroupId] 的多个版本只渲染选中的那一个，
     * 其余整组留在库里（供 ‹ n/N › 左右切换）不占时间线。
     *
     * 渲染位置取该组在时间线上的**最后一条**：重新生成后新版本追加在末尾，故选中任一版本都落在
     * 同一条用户消息之后——否则切回旧版本会把它渲染到用户消息前面。
     */
    private fun applyVariantSelection(
        entities: List<AgentMessageEntity>,
        selections: Map<String, Int>
    ): List<AgentUIMessage> {
        if (entities.none { it.variantGroupId != null }) return entities.map { it.toUIMessage() }
        val groupIndices = HashMap<String, List<Int>>()
        val groupLastPosition = HashMap<String, Int>()
        entities.forEachIndexed { index, entity ->
            val group = entity.variantGroupId ?: return@forEachIndexed
            groupLastPosition[group] = index
            val indices = groupIndices[group]
            groupIndices[group] = when {
                indices == null -> listOf(entity.variantIndex)
                entity.variantIndex in indices -> indices
                else -> (indices + entity.variantIndex).sorted()
            }
        }
        val result = ArrayList<AgentUIMessage>(entities.size)
        val emitted = HashSet<String>()
        entities.forEachIndexed { index, entity ->
            val group = entity.variantGroupId
            if (group == null) {
                result += entity.toUIMessage()
                return@forEachIndexed
            }
            if (group in emitted || groupLastPosition[group] != index) return@forEachIndexed
            emitted += group
            val indices = groupIndices[group].orEmpty().ifEmpty { listOf(0) }
            val position = (selections[group] ?: (indices.size - 1)).coerceIn(0, indices.size - 1)
            entities.asSequence()
                .filter { it.variantGroupId == group && it.variantIndex == indices[position] }
                .forEach { result += it.toUIMessage().copy(variantIndex = position, variantCount = indices.size) }
        }
        return result
    }

    val runningTool: StateFlow<List<RunningToolOutput>> =
        turnRuntime.runningTool(_currentSessionId, viewModelScope)

    private val preparingToolState = PreparingToolState(_currentSessionId, viewModelScope)

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）；工具真正开始执行后由 [runningTool] 接管。
     * 状态本身与进度放在 [PreparingToolState] 里，两者同生共死。
     */
    val preparingTool: StateFlow<String?> = preparingToolState.toolName

    /** 工具参数流式进度；null 时 UI 用 [preparingTool] 对应的通用场景文案。 */
    val preparingToolProgress: StateFlow<ToolArgsProgress?> = preparingToolState.argsProgress

    private fun setPreparingTool(sessionId: String, toolName: String?, argsProgress: ToolArgsProgress? = null) {
        preparingToolState.update(sessionId, toolName, argsProgress)
    }

    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = turnRuntime.contextUsages

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = turnRuntime.isCompacting(_currentSessionId, viewModelScope)

    private fun setCompacting(sessionId: String, compacting: Boolean) =
        turnRuntime.setCompacting(sessionId, compacting)

    val streamingText: StateFlow<String?> = turnRuntime.streamingText(_currentSessionId, viewModelScope)

    private fun setStreamingText(sessionId: String, text: String?) =
        turnRuntime.setStreamingText(sessionId, text)

    val reasoningTiming: StateFlow<Pair<Long, Long?>?> =
        turnRuntime.reasoningTiming(_currentSessionId, viewModelScope)

    val streamingReasoning: StateFlow<String?> =
        turnRuntime.streamingReasoning(_currentSessionId, viewModelScope)

    private fun setStreamingReasoning(sessionId: String, text: String?) =
        turnRuntime.setStreamingReasoning(sessionId, text)

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    val retryState: StateFlow<RetryState?> = turnRuntime.retryState(_currentSessionId, viewModelScope)

    private fun setRetryState(sessionId: String, state: RetryState?) =
        turnRuntime.setRetryState(sessionId, state)

    /** 按 sessionId 维护的供应商并发排队提示；取得名额、本轮结束或被打断时清除。 */
    val requestQueueState: StateFlow<RequestQueueState?> =
        sessionQueue.requestQueueState(_currentSessionId, viewModelScope)

    private fun setRequestQueueState(sessionId: String, state: RequestQueueState?) =
        sessionQueue.setRequestQueueState(sessionId, state)

    /** 按 sessionId 维护的多 Key 切换提示；重新出内容或本轮结束时置 null。 */
    val keySwitchState: StateFlow<KeySwitchState?> = turnRuntime.keySwitchState(_currentSessionId, viewModelScope)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) =
        turnRuntime.setKeySwitchState(sessionId, state)

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 一次回合的批量授权提案（一次回合的多个工具调用合并），弹窗优先渲染它。 */
    val pendingPermissionBatch = toolPermissionManager.pendingBatch

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        toolPermissionManager.pendingBatch,
        sessions,
        subSessionsByParent
    ) { req, batch, roots, subs ->
        val sid = (req?.sessionId ?: batch?.sessionId).orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions: MutableStateFlow<Set<String>> get() = runRegistry.runningCommandSessions
    val queuedRequests: StateFlow<List<QueuedRequest>> =
        sessionQueue.queuedRequests(_currentSessionId, viewModelScope)

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    /** 计划审批面板所属会话标题（多会话并行时供面板标注归属）。会话不存在时回退空串。 */
    val pendingPlanApprovalSessionTitle: StateFlow<String> = combine(
        planApprovalManager.pendingApproval,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId: MutableMap<String, String> get() = runRegistry.toolArgsByMsgId

    /** 是否有正在运行、可被打断的 agent 任务。 */
    val isRunning: Boolean get() {
        val sid = _currentSessionId.value ?: return false
        return sessionJobs[sid]?.isActive == true
    }

    fun hasRunningSessionsInCurrentWorkspace(): Boolean {
        if (sessions.value.any { sessionJobs[it.id]?.isActive == true }) return true
        // 子代理会话不在 sessions（只含根会话）里：漏掉它们会在父代理这一轮结束时
        // 提前释放唤醒锁与前台服务，仍在跑的子代理会被系统挂起。
        return subSessionsByParent.value.values.any { subs ->
            subs.any { sessionJobs[it.id]?.isActive == true }
        }
    }

    /** 任务开始：拿 CPU 唤醒锁并拉起前台保活通知，避免熄屏或切后台时进程被挂起、回收。 */
    private fun acquireKeepalive() {
        TerminalKeepaliveService.enablePersistent(context)
        runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
            .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
        if (wakeLockRenewJob?.isActive != true) wakeLockRenewJob = viewModelScope.launch {
            renewWakeLockUntilIdle(wakeLock, KEEPALIVE_TIMEOUT_MS, WAKE_LOCK_RENEW_MARGIN_MS, TAG) { hasRunningSessionsInCurrentWorkspace() }
        }
    }

    /** 任务收尾：释放唤醒锁；仅当既无后台终端任务、用户也没手动开保活时才停前台服务。 */
    private fun releaseKeepalive() {
        wakeLockRenewJob?.cancel().also { wakeLockRenewJob = null }
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"

        /** 插件会话队列的消费者标识；队列本身只允许一个消费者。 */
        const val PLUGIN_CHAT_CONSUMER_ID = "chat-view-model"

        /** 续跑时发给模型的引导语：任务中途被中断后接着进度做，避免重做已完成部分。 */
        const val RESUMED_TURN_PROMPT = "上一轮任务在中途被中断。请基于当前已有的对话与文件进度继续，" +
            "不要重做已经完成的部分，也不要重新读取或重写已经正确的文件。" +
            "如果上一条回答写到一半被打断，请从断点接着写完，不要重复已经输出的内容。"

        /** wakeLock 超时保险：构建、装依赖类工具动辄十几分钟，给足 60 分钟；任务正常结束会主动释放。 */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_MARGIN_MS = 10 * 60 * 1000L
        /** 从后台任务通知文本中提取 <title> 内容，供系统通知正文展示。 */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")

        /** 写文件会连珠触发多个 inotify 事件，合并后再重读目录。 */
        const val BROWSE_DEBOUNCE_MS = 300L
    }

    init {
        // 插件会话请求：插件只入队，真正开轮次仍由本 VM 执行（工具授权、运行状态、通知都在这），
        // 否则插件能绕过这些边界另起一套发送。
        pluginChatRequestBus.attach(PLUGIN_CHAT_CONSUMER_ID)
        viewModelScope.launch {
            while (true) {
                val request = pluginChatRequestBus.receive()
                runCatching { handlePluginChatRequest(request) }
                    .onFailure { FileLogger.w(TAG, "处理插件会话请求失败", it) }
            }
        }
        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 附件按会话隔离：用户主动切换会话时清空。原本挂在聊天面板的 collect 里，
        // 面板被销毁时清不掉，且分享投递与面板组合时序耦合，这里提到 VM 统一处理。
        viewModelScope.launch {
            _sessionSwitchEvents.collect {
                // 分享投递进来的附件要留住：冷启动时「会话就绪」紧接着分享投递，一律清空会让
                // 用户刚点进来的文件凭空消失（退出重进才看到）。
                val (cleared, keptShare) = composer.retainSharedAttachments()
                if (cleared > 0) {
                    FileLogger.i("ChatAttachDbg", "切会话清附件：清 $cleared 项，保留分享 $keptShare 项")
                }
            }
        }

        // 会话就绪：接管「无会话期间」打的草稿；浏览器池的会话作用域也跟着走，
        // aharou://workspace/... 的解析与下载落盘都按池的 sessionId（见 BrowserUseManager）。
        viewModelScope.launch {
            _currentSessionId.filterNotNull().collect { sid ->
                composer.adoptPendingDraft(sid)
                withContext(Dispatchers.IO) { browserTabPool.setSession(sid) }
            }
        }

        // 错误横幅持久化：会话末条消息带着错误文本时恢复 Error 状态，重开 App / 切会话后
        // 横幅与一键重试仍在；末条不再是错误行（已重试或继续对话）时清除该会话的错误态。
        viewModelScope.launch {
            _currentSessionId.filterNotNull().flatMapLatest { id ->
                agentMessageDao.getMessagesBySessionPaged(id, 1)
            }.collect { list ->
                val sessionId = _currentSessionId.value ?: return@collect
                if (sessionJobs[sessionId]?.isCompleted == false || sessionId in stoppingSessions) return@collect
                val state = _agentStates.value[sessionId]
                if (state is AgentUIState.Loading || state is AgentUIState.Streaming) return@collect
                val error = list.lastOrNull()?.error
                if (error.isNullOrBlank()) {
                    if (state is AgentUIState.Error) setAgentState(sessionId, AgentUIState.Idle)
                } else {
                    setAgentState(sessionId, AgentUIState.Error(error))
                }
            }
        }

        // 启动与工作区切换时重新扫描技能（项目级技能随工作区变化），刷新 `/` 命令菜单。
        viewModelScope.launch {
            _currentWorkspace.collect { slashCommandRegistry.refresh() }
        }

        viewModelScope.launch {
            _currentWorkspace.collectLatest { path ->
                if (path.isBlank()) return@collectLatest
                // 当前会话本就挂在这个工作区上（由 selectSession 驱动切过来的），不必重挑，
                // 否则会把用户刚点开的会话又换掉。只在会话与工作区对不上时才按工作区选会话。
                val existing = _currentSessionId.value
                    ?.let { sessionUseCase.getSessionById(it) }
                    ?.workspacePath
                if (existing == path) return@collectLatest
                val recent = sessionUseCase.getMostRecentSessionOfWorkspace(path)
                // 从别的工作区切过来时直接进该文件夹的最近会话：用户点的是文件夹，想看的就是上次
                // 在那儿聊的东西。startupSessionMode 只管冷启动（_currentSessionId 为 null），否则
                // 默认的「新开会话」设置会让每次切文件夹都开一个新会话。
                val switchingWorkspace = _currentSessionId.value != null
                val targetId = when {
                    recent == null -> createAndUpsertSession(path)
                    switchingWorkspace -> recent.id
                    generalSettingsRepository.startupSessionMode() == StartupSessionMode.RECENT_SESSION -> recent.id
                    sessionUseCase.isSessionEmpty(recent.id) -> recent.id
                    else -> createAndUpsertSession(path)
                }
                _currentSessionId.value = targetId

                // 异步回收多余的空会话（保留当前 targetId），在后台执行，不卡主线程与首帧渲染。
                if (recent != null) {
                    launch(Dispatchers.IO) {
                        sessionUseCase.recycleEmptySessions(path, keepId = targetId)
                    }
                }
            }
        }

        // 进入/切换工作区就把信箱未读投到该工作区的「默认会话」（最近更新的那个），
        // 不必用户先在这个工作区发一句话、由那一轮去捞。没有会话就不投，不凭空造空会话。
        // 来信不必等切工作区：写入工具与目录监听发出信号后，直接投到目标工作区的默认会话。
        sharedInboxDelivery.bind { path -> deliverInboxToDefaultSession(path) }
        viewModelScope.launch {
            _currentWorkspace.collect { path ->
                if (path.isNotBlank()) withContext(Dispatchers.IO) { deliverInboxToDefaultSession(path) }
            }
        }

        // 工作区列表或当前工作区变化时刷新共享区通讯录（幂等：内容没变不写盘）。
        viewModelScope.launch {
            combine(workspaceRepository.workspaces, _currentWorkspace) { list, _ -> list }
                .collect { list -> withContext(Dispatchers.IO) { inboxDirectory.refresh(list) } }
        }

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionProvider.tabFinishedEvents.collect { handleBackgroundCommandFinished(it) }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOPPED：task(action="stop") 请求停止子代理，取消对应会话的 AI 任务；
        // - COMPLETED/FAILED：由子会话工作流结束时发出（见 handleSubAgentFinished），注入父会话通知；
        // - DELETED：task(action="del") 已删库，只清运行时状态，不做任何停止收尾。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                if (!subAgentEventBus.isCurrentEvent(event)) return@collect
                // 卡片状态先按事件即时翻过去（落库慢一步），终态再补一次读库拿摘要与改动文件。
                noteSubAgentCardEvent(event)
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.RUN_STARTED -> Unit
                    SubAgentEventType.STOPPED -> {
                        stopAgentSession(event.subSessionId)
                        if (event.runId?.let { suppressedSubAgentStops.remove(it) } != true) viewModelScope.launch {
                            val sub = sessionUseCase.getSessionById(event.subSessionId) ?: return@launch
                            messageRouter.notifyParentSubAgentFinished(event.parentSessionId, sub.id, sub.title,
                                NotificationOutcome.STOPPED, event.detail.takeIf { it.isNotBlank() }, deliver = deliverToSession)
                        }
                    }
                    SubAgentEventType.DELETED -> clearDeletedSessionState(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        if (event.runId?.let { suppressedSubAgentStops.remove(it) } != true) enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }

        // 定时任务：Worker 认领后交给前台服务后台跑，这里只负责捡「服务没起来」时落库的活。
        viewModelScope.launch {
            ScheduledPendingRunner(scheduledTaskDao) { request -> runScheduledTask(request) }.drain()
        }
        viewModelScope.launch { awaitingPermissionSessionIds.collect { agentPermissionNotifier.sync(it) } }
        viewModelScope.launch {
            combine(askUserQuestionManager.pendingQuestion, planApprovalManager.pendingApproval) { q, p -> q to p }.collect { (question, plan) -> agentPermissionNotifier.syncPrompts(question, plan) }
        }
        viewModelScope.launch { agentPermissionNotifier.replies.collect { r -> enqueueAgentRequest(request = r.text, projectRoot = workspaceOf(r.sessionId), targetSessionId = r.sessionId) } }
    }

    /**
     * 跑一轮定时任务：目标会话存在就在它里面跑；没有目标会话就按任务记的工作区新建一个。
     * 标题用任务名，并跳过首条消息的标题推导，免得被指令内容覆盖。
     */
    private suspend fun runScheduledTask(request: ScheduledRunRequest) {
        com.aharou.feature.agent.presentation.runScheduledTask(request, sessionUseCase, scheduledTaskDao) { sessionId, root, completion ->
            executeAgentRequestStream(request = request.prompt, projectRoot = root, targetSessionId = sessionId,
                isAutoTrigger = true, skipTitleUpdate = true, skipCommandDispatch = true, completion = completion)
        }
    }

    /**
     * 子代理已创建（task 工具已创建子会话）：在子会话上启动 AI 工作流。
     * 消息由 executeAgentRequestStream 统一落库（与用户手动发消息一致），
     * 标题保留 task 传入的 description（skipTitleUpdate）。
     */
    private suspend fun spawnSubAgentWorkflow(event: SubAgentEvent) {
        val parentSession = sessionUseCase.getSessionById(event.parentSessionId)
        if (parentSession == null) {
            FileLogger.w(TAG, "子代理父会话不存在: ${event.parentSessionId}")
            // SPAWNED 已在总线里占了名额，早退不归还就永远占着（task 之后一直报「已达上限」）。
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            subAgentEventBus.release(event.subSessionId)
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）。这里不能 release：
        // 活跃名额属于正在跑的那一轮，释放会让它在完成后少计一个，并发上限就被绕过了。
        if (sessionJobs[event.subSessionId]?.isActive == true) return
        if (subAgentEventBus.currentRun(event.subSessionId)?.runId != event.runId) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true, expectedSubAgentRunId = event.runId
        )
    }

    /**
     * 子代理完成/失败通知：父会话忙碌时先入队（由本轮内下一批工具结果搭车送达，见
     * [com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow]），空闲时立即注入一条系统通知消息触发新一轮。
     * 父代理据此得知子代理结束，可 task(action="read") 取回结果。与 terminal 后台通知同机制。
     */
    private fun enqueueSubAgentNotification(event: SubAgentEvent) {
        viewModelScope.launch {
            val title = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
            messageRouter.notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED || event.accepted == false) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() },
                deliver = deliverToSession
            )
        }
    }
    /** 代理间消息的组装在 [AgentMessageRouter]；入队与起轮仍走 [deliverSystemEvent]。 */
    private val deliverToSession: (String, PendingNotification) -> Unit = { sid, item -> deliverSystemEvent(sid, item) }

    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) =
        messageRouter.deliverMessageToSubAgent(event.parentSessionId, event.subSessionId, event.detail, deliverToSession)

    private suspend fun deliverMessageToParent(event: SubAgentEvent) =
        messageRouter.deliverMessageToParent(event.parentSessionId, event.subSessionId, event.detail, event.fromSibling, deliverToSession)

    private suspend fun deliverInboxToDefaultSession(workspacePath: String) =
        messageRouter.deliverInboxToDefaultSession(workspacePath, deliverToSession)

    private suspend fun deliverInboxMessages(sessionId: String, projectRoot: String) =
        messageRouter.deliverInboxMessages(sessionId, projectRoot, deliverToSession)

    /**
     * 后台命令（notify=true）结束后的回调。
     *
     * - 会话忙碌：入队 [agentNotificationCenter]，由本轮内下一批工具结果搭车送达（AI 当轮即可感知，
     *   省掉「等本轮结束再起一轮」的 LLM 往返）；整轮再没有工具调用时由 [flushPendingNotifications] 兜底。
     * - 会话空闲：立即以一条系统通知消息触发 Agent 新一轮。
     *
     * 兜底路径用 user 消息而非 assistant(tool_call) + tool_result 消息对：后者会与原 terminal 工具调用的
     * tool 结果在落库顺序上错位（后台回调异步触发，可能抢先于原 terminal 结果落库），导致 messages
     * 违反 OpenAI「assistant(tool_calls) → tool 结果紧跟」的配对约束，上游返回 400。user 消息无需与
     * 任何 tool_call 配对，天然不破坏顺序。通知文本带围栏说明，防止 AI 误判为用户的新指令或批准；
     * AI 据此用 terminal(read) 取回完整输出。
     *
     * 不自行 persist 通知、用 isAutoTrigger=false 走 enqueueAgentRequest 正常流程：由
     * executeAgentRequestStream 统一 persist 这条 user 消息，workflow 的 InitRequest 追加的同一条
     * UserMessage 即是它，避免重复落库或出现空占位消息。
     */
    private fun handleBackgroundCommandFinished(event: TabFinishedEvent) {
        val sessionId = event.sourceSessionId ?: return
        val jobActive = sessionJobs[sessionId]?.isActive == true
        val currentSid = _currentSessionId.value
        FileLogger.d(TAG, "handleBgFinished: eventSid=$sessionId currentSid=$currentSid jobActive=$jobActive state=${_agentStates.value[sessionId]}")
        val item = event.toPendingNotification()
        deliverSystemEvent(sessionId, item)
    }

    /**
     * 会话绑定的工作区路径；会话不存在或没绑定时回退全局当前工作区。
     *
     * 让「文件落哪个工作区」跟会话走，而不是跟界面上选中的工作区走——否则切工作区后
     * 老会话的工具调用会写到新工作区里去。
     */
    private suspend fun workspaceOf(sessionId: String): String =
        sessionUseCase.getSessionById(sessionId)?.workspacePath?.takeIf { it.isNotBlank() }
            ?: _currentWorkspace.value

    /**
     * 统一投递一条系统事件给指定会话：后台任务完成、子代理结束、代理间消息、用户插话共用此入口。
     *
     * 一律先入 [agentNotificationCenter]，由 [flushPendingNotifications] 作唯一消费点取出起一轮。
     * 不按空闲/忙碌分支：两条路径并存时会同时看到同一批通知、靠忙碌判断互相让路，既可能漏送也可能
     * 重送；单一消费点还能保证 ack 与真实送达一一对应。主线程调用时 flush 当场消费（viewModelScope 用
     * Main.immediate，内联执行、不多一次调度），通知仍在同一轮事件循环内送达。
     */
    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        agentNotificationCenter.enqueue(sessionId, item)
        // 先切回主线程再 flush：共享区信箱那条投递跑在 IO 上，若在后台线程直接 peek，两次相邻投递会各自
        // peek 到同一批通知（前者还没 ack）而重复送达；从主线程进 flush 才能把「拦门→peek→ack」串成一段。
        viewModelScope.launch { flushPendingNotifications(sessionId) }
    }

    private fun TabFinishedEvent.toPendingNotification() = PendingNotification(
        kind = AgentNotificationKind.BACKGROUND_TASK,
        sourceId = tabId,
        title = title,
        outcome = if (exitCode == 0) NotificationOutcome.COMPLETED else NotificationOutcome.FAILED,
        command = command,
        exitCode = exitCode,
        tailOutput = tailOutput
    )

    /**
     * 本轮结束后的兜底送达：把仍留在队列里的通知合并成一条消息发送。
     * 已被工具结果搭车送达的通知此时已 ack 移除，取到空则什么都不做。
     */
    private fun flushPendingNotifications(sessionId: String) {
        // 会话正忙或正在停止时起不了新一轮，这时消费队列等于把通知吞掉；留给下一次收尾。
        if (sessionId in stoppingSessions || sessionJobs[sessionId]?.isCompleted == false) return
        val items = agentNotificationCenter.peek(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            // 先入队再 ack：中途被取消（点停止）就不 ack，通知留在队列里等下次收尾再送，
            // 而不是「已从队列取走、但那一轮并没真的跑起来」把消息丢掉。
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = workspaceOf(sessionId),
                targetSessionId = sessionId
            )
            withContext(NonCancellable) {
                agentNotificationCenter.ack(sessionId, items.map { it.seq })
            }
        }
    }

    fun enqueueAgentRequest(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        isAutoTrigger: Boolean = false,
        targetSessionId: String? = null,
        persistUserMessage: Boolean = true
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        // 软打断的会话不算忙：软打断=「停下听我说」，这时发的话要直接进会话，不该排队
        val isCurrentRunning = sid != null && sid !in stoppingSessions && (sessionJobs[sid]?.isCompleted == false || sid in _runningCommandSessions.value)
        if (isCurrentRunning) {
            val req = QueuedRequest(
                id = UUID.randomUUID().toString(),
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage
            )
            sessionQueue.enqueue(sid, req)
        } else {
            executeAgentRequestStream(
                request = request,
                modelRequest = modelRequest,
                currentFile = currentFile,
                selectedCode = selectedCode,
                projectRoot = projectRoot,
                inputImages = inputImages,
                inputAttachments = inputAttachments,
                targetSessionId = sid,
                isAutoTrigger = isAutoTrigger,
                persistUserMessage = persistUserMessage
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        _currentSessionId.value?.let { sessionQueue.remove(it, id) }
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        _currentSessionId.value?.let { sessionQueue.move(it, fromIndex, toIndex) }
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        _currentSessionId.value?.let { sessionQueue.update(it, id, newText) }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * [deliverSystemEvent]——忙碌时搭车注入本批工具结果，AI 当前轮即可感知；空闲时作为
     * 新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val req = sessionQueue.takeOut(sid, id) ?: return
        deliverSystemEvent(
            sid,
            PendingNotification(
                kind = AgentNotificationKind.USER_MESSAGE,
                sourceId = sid,
                title = "",
                outcome = NotificationOutcome.COMPLETED,
                message = req.request
            )
        )
    }

    private fun processNextInQueue(sessionId: String) {
        // 已有活跃 job（可能是本次收尾前由通知合并/flush 等入口启动的）时不消费，
        // 避免队列被多个收尾入口重复消费、同一会话并发跑两个 job。
        if (sessionId in stoppingSessions || sessionJobs[sessionId]?.isCompleted == false || sessionId in _runningCommandSessions.value) return
        val next = sessionQueue.peekFirst(sessionId) ?: return
        sessionQueue.dropFirst(sessionId)
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger,
            persistUserMessage = next.persistUserMessage
        )
    }

    /**
     * 执行斜杠命令：按类型派发——内置命令先把命令文本落库再走本地动作；
     * 技能则触发 agent 回合，用户消息由本轮 runner 统一落库（只落 `/技能名 参数`，正文仅喂模型）。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                when (resolved) {
                    is ResolvedCommand.Action -> {
                        messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                        sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                        resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    }
                    // 技能：用户消息由 runSkill 触发的本轮 runner 落库，此处不再预先落库，避免出现两条。
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
    }

    /** 当前会话或指定会话的输入框草稿；插件写入草稿时读它拼追加。 */
    fun inputDraftOf(sessionId: String?): String =
        composer.draftOf(sessionId ?: _currentSessionId.value)

    override fun onCleared() {
        pluginChatRequestBus.detach(PLUGIN_CHAT_CONSUMER_ID)
        super.onCleared()
    }

    fun executeAgentRequestStream(
        request: String,
        modelRequest: String = request,
        currentFile: String? = null,
        selectedCode: String? = null,
        projectRoot: String = "",
        inputImages: List<AgentImage> = emptyList(),
        inputAttachments: List<AgentAttachment> = emptyList(),
        targetSessionId: String? = null,
        isAutoTrigger: Boolean = false,
        /** 子代理等场景：已预设会话标题，跳过首条消息的标题推导/生成，保留预设标题。 */
        skipTitleUpdate: Boolean = false,
        /** 技能等场景：request 以 `/` 开头但已由调用方处理，跳过斜杠命令再派发，避免重入死循环。 */
        skipCommandDispatch: Boolean = false,
        /** /init 等场景：调用方已先落过命令文本，这轮不再落用户消息，避免落出两条。 */
        persistUserMessage: Boolean = true,
        completion: AgentTurnCompletion? = null,
        expectedSubAgentRunId: String? = null
    ): Job = viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
        val sessionId = targetSessionId ?: ensureSession()
        if (sessionId.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过请求")
            return@launch
        }
        val currentJob = requireNotNull(coroutineContext[Job])
        try {
            while (true) {
                stoppingSessions[sessionId]?.await()
                val previousJob = sessionJobs[sessionId]
                if (previousJob == null || previousJob === currentJob || previousJob.isCompleted) break
                previousJob.join()
            }
            if (!skipCommandDispatch && request.startsWith("/")) {
                slashCommandRegistry.resolve(request)?.let { command ->
                    runResolvedCommand(command, request, sessionId)
                    return@launch
                }
            }
            sessionJobs[sessionId] = currentJob
            _activeAgentSessions.value = _activeAgentSessions.value + sessionId
            pluginRunStateStore.started(sessionId)
            currentJob.invokeOnCompletion {
                viewModelScope.launch {
                    if (sessionJobs[sessionId] !== currentJob) return@launch
                    sessionJobs.remove(sessionId)
                    _activeAgentSessions.value = _activeAgentSessions.value - sessionId
                    pluginRunStateStore.finished(sessionId, "completed", null)
                    if (sessionId !in stoppingSessions) {
                        flushPendingNotifications(sessionId)
                        processNextInQueue(sessionId)
                    }
                    if (sessionJobs.values.none { !it.isCompleted }) releaseKeepalive()
                }
            }
            FileLogger.d(TAG, "stream start: sid=$sessionId prevState=${_agentStates.value[sessionId]} isAutoTrigger=$isAutoTrigger")
            setAgentState(sessionId, AgentUIState.Streaming)
            acquireKeepalive()

            // 会话就绪、历史组装、工具装配、落库全在 runner 里；这里只管界面状态。
            // 重逻辑分支（工具卡片、失败落库、完成通知与记忆沉淀）在 [TurnEventHandlers]，
            // 目的是把这个超大协程状态机切小（见记忆 agent_loop_jit_abort_split）。
            val turnScope = TurnEventHandlers.TurnScope(
                sessionId = sessionId,
                currentJob = currentJob,
                modelRequest = modelRequest,
                projectRoot = projectRoot
            ) { next -> enqueueAgentRequest(next, projectRoot = projectRoot, isAutoTrigger = true, targetSessionId = sessionId) }
            // projectRoot 空时回落会话自己的工作区：否则中途切区会让本轮后续工具漂到新目录。
            val turnProjectRoot = projectRoot.ifBlank { sessionUseCase.getSessionById(sessionId)?.workspacePath.orEmpty() }
            deliverInboxMessages(sessionId, turnProjectRoot)
            agentTurnRunner.run(
                AgentTurnRequest(
                    sessionId = sessionId,
                    text = request,
                    modelRequest = modelRequest,
                    currentFile = currentFile,
                    selectedCode = selectedCode,
                    projectRoot = turnProjectRoot,
                    inputImages = inputImages,
                    inputAttachments = inputAttachments,
                    isAutoTrigger = isAutoTrigger,
                    skipTitleUpdate = skipTitleUpdate,
                    persistUserMessage = persistUserMessage,
                    expectedSubAgentRunId = expectedSubAgentRunId
                )
            ).collect { event ->
                completion?.observe(event)
                if (sessionJobs[sessionId] !== currentJob) return@collect
                when (event) {
                    is AgentEvent.RequestQueued -> setRequestQueueState(sessionId, RequestQueueState(event.requestsAhead))
                    AgentEvent.RequestStarted -> setRequestQueueState(sessionId, null)
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) {
                            turnHandlers.finishReasoning(turnScope)
                        }
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (turnScope.reasoningStart == null) {
                            turnScope.reasoningStart = System.currentTimeMillis()
                            turnScope.reasoningEnd = null
                        }
                        turnRuntime.setReasoningTiming(sessionId, turnScope.reasoningStart!! to turnScope.reasoningEnd)
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        turnHandlers.finishReasoning(turnScope)
                        // 工具名先于参数到达：让 UI 把「正在思考」换成具体场景（「正在编辑文件」）。
                        // 参数流完、工具真正开始执行后由 ToolCallStarted 清掉，改由工具行表达。
                        // event.progress 为 null 时与加进度之前完全一致。
                        setPreparingTool(sessionId, event.toolName, event.progress)
                    }
                    is AgentEvent.Retrying -> {
                        turnScope.reasoningStart = null
                        turnScope.reasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // 重试会从头重新流式输出：清掉已展示的正文/思维链气泡，
                        // 否则重连后思维链重新生成而旧正文残留（workflow 已同步清空累积器）。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        turnScope.reasoningStart = null
                        setKeySwitchState(sessionId, KeySwitchState(event.newIndex, event.total))
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                    }
                    is AgentEvent.CompactionStarted -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setCompacting(sessionId, true)
                    }
                    AgentEvent.CompactionFinished -> {
                        setCompacting(sessionId, false)
                        turnRuntime.afterCompaction(sessionId)
                    }
                    is AgentEvent.ContextUsage -> turnRuntime.reportContextUsage(sessionId, event)
                    // 压缩失败与采样循环中止都落库为无配对的 TOOL 消息（界面各渲染成卡片/提示行），落库在 runner 里做
                    is AgentEvent.CompactionFailed -> { turnRuntime.markCompactionFailed(sessionId); setCompacting(sessionId, false) }
                    is AgentEvent.SamplingLoopDetected -> Unit
                    is AgentEvent.AssistantText -> {
                        val reasoningDuration = turnScope.reasoningStart?.let { System.currentTimeMillis() - it }
                        turnScope.reasoningStart = null

                        // 流式收尾：落库在 runner 里做，但它先把事件转给我们，
                        // 所以这里能先清空流式状态，避免「落库消息与流式气泡同屏并存」的时差。
                        // 落库消息换了 id：先把思考卡的手动展开态迁到 event.messageId，再清流式态。
                        turnRuntime.migrateStreamingReasoningExpansion(sessionId, event.messageId)
                        setStreamingReasoning(sessionId, null)
                        setStreamingText(sessionId, null)
                        if (reasoningDuration != null && reasoningDuration > 0 && event.messageId.isNotBlank()) {
                            reasoningDurations[event.messageId] = reasoningDuration
                        }
                        if (event.toolCalls.isEmpty()) {
                            setPreparingTool(sessionId, null)
                        }

                        if (event.inputTokens > 0 || event.outputTokens > 0) {
                            _llmCallEvents.tryEmit(LlmCallEvent(sessionId, event.inputTokens, event.outputTokens, event.cachedInputTokens))
                        }
                    }
                    is AgentEvent.ToolCallStarted ->
                        turnHandlers.handleToolCallStarted(turnScope, event) { setPreparingTool(sessionId, null) }
                    is AgentEvent.ToolCallProgress -> turnHandlers.handleToolCallProgress(turnScope, event)
                    is AgentEvent.ToolCallFinished -> turnHandlers.handleToolCallFinished(turnScope, event)
                    is AgentEvent.Failed -> turnHandlers.handleFailed(turnScope, event)
                    AgentEvent.Completed -> turnHandlers.handleCompleted(turnScope, viewModelScope)
                    is AgentEvent.ModeChanged -> {
                        // 模式切换事件：PlanApprovalManager 已在 workflow 层面挂起等待用户批准
                        // 这里只更新 streamingText 显示
                    }
                }
            }

            if (sessionJobs[sessionId] !== currentJob) return@launch
            sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
            if (sessionJobs[sessionId] !== currentJob) return@launch
            val finishedState = _agentStates.value[sessionId]
            if (!turnScope.failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
                setAgentState(sessionId, AgentUIState.Result(WorkflowStatus.SUCCESS))
                _completedSessions.value = _completedSessions.value + sessionId
            }
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)

        } catch (e: CancellationException) {
            val cancelledState = _agentStates.value[sessionId]
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream cancelled: sid=$sessionId isOwnJob=$isOwnJob state=$cancelledState")
            if (isOwnJob &&
                (cancelledState is AgentUIState.Loading || cancelledState is AgentUIState.Streaming)
            ) {
                setAgentState(sessionId, AgentUIState.Idle)
                _completedSessions.value = _completedSessions.value - sessionId
            }
            throw e
        } catch (e: Exception) {
             completion?.fail(e)
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             if (sessionJobs[sessionId] === currentJob) {
                 val failureMessage = e.toUserMessage(context)
                 setAgentState(sessionId, AgentUIState.Error(failureMessage))
                 turnHandlers.persistFailure(sessionId, failureMessage)
                 _completedSessions.value = _completedSessions.value - sessionId
             }
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                turnRuntime.clearRunningTools(sessionId)
                setStreamingText(sessionId, null)
                setStreamingReasoning(sessionId, null)
                setPreparingTool(sessionId, null)
                setCompacting(sessionId, false)
                setRetryState(sessionId, null)
                setKeySwitchState(sessionId, null)
                setRequestQueueState(sessionId, null)
                val currentState = _agentStates.value[sessionId]
                if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                    setAgentState(sessionId, AgentUIState.Idle)
                }
            }
        }
    }.also { completion?.bind(it) }

    fun resolveToolPermission(id: String, choice: PermissionChoice) = toolPermissionManager.resolve(id, choice)

    /** 批量授权面板回传用户选择（全部同判）。 */
    fun resolveToolPermissionBatch(batchId: String, choice: PermissionChoice) =
        toolPermissionManager.resolveBatch(batchId, choice)

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) = askUserQuestionManager.resolve(id, answer)

    /**
     * 停止所有正在运行的 AI 会话并关闭终端标签（切换/重置容器前调用）。
     *
     * 容器 rootfs 会被删掉，会话与终端必须全停；切工作区不走这里——那个只换目录，什么都不用停。
     */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止所有正在运行的 AI 会话（仅由容器切换/重置流程调用）。见 [SessionStopController.stopAll]。 */
    fun stopAllAgents() {
        stopController.stopAll(viewModelScope) { releaseKeepalive() }
    }

    /** 软打断当前会话正在运行的 agent：不取消协程，只投一条 USER_INTERRUPT，见 [stopAgentSession]。 */
    fun stopAgent() {
        val sessionId = _currentSessionId.value ?: return
        stopAgentSession(sessionId)
    }

    /** 长按停止键：强制打断当前会话正在运行的 agent，见 [forceStopAgentSession]。 */
    fun forceStopAgent() {
        val sessionId = _currentSessionId.value ?: return
        forceStopAgentSession(sessionId)
    }

    /**
     * 停止指定会话的 AI 任务（子代理停止 / 用户手动停止共用）。
     *
     * 走「软打断」：只投递一条 USER_INTERRUPT，agent 在当前这步做完后就不再继续，**不取消 job**——
     * 「任务放着继续做，你先停下来听我说」。实现在 [SessionStopController.stopSession]。
     */
    fun stopAgentSession(sessionId: String) {
        stopController.stopSession(sessionId, viewModelScope) { sid ->
            flushPendingNotifications(sid)
            processNextInQueue(sid)
        }
    }

    /**
     * 强制打断指定会话（用户长按停止键）：取消协程，容器内进程与网络请求一并断开。
     * 允许叠加在软打断之上；协程已不在也会补一次收尾。实现在 [SessionStopController.forceStopSession]。
     */
    fun forceStopAgentSession(sessionId: String) {
        stopController.forceStopSession(sessionId, viewModelScope) { sid ->
            flushPendingNotifications(sid)
            processNextInQueue(sid)
        }
    }

    /** 会话被删除时清掉与它绑定的内存态：污点标记、会话级授权。 */
    private fun clearSessionRuntimeState(sessionId: String) = stopController.clearSessionRuntimeState(sessionId)

    /**
     * 会话已被删除（task del 删库 / 界面上删会话）时清掉它的全部运行时状态。
     *
     * 不能走 [stopAgentSession]：那是软打断，会把 USER_INTERRUPT 投进一个即将消失的会话，
     * 收尾时 job 的 finally 又对着已删会话补送通知、再起一轮（幽灵会话，白烧一轮 LLM）。
     */
    private fun clearDeletedSessionState(sessionId: String) {
        clearSessionRuntimeState(sessionId)
        val deletedRun = subAgentEventBus.currentRun(sessionId)
        if (deletedRun != null) viewModelScope.launch {
            subAgentRunLifecycle.stopRun(sessionId, deletedRun.parentSessionId, deleted = true,
                broadcast = false, expectedRun = deletedRun)
        }
        // 先清队列再 cancel：cancel 可能在调用栈内同步跑 finally → flushPendingNotifications，
        // 队列已空才会直接返回，不为已删会话再起一轮。
        agentNotificationCenter.clear(sessionId)
        sessionQueue.clear(sessionId)
        sessionJobs[sessionId]?.cancel()
        sessionJobs.remove(sessionId)
        _activeAgentSessions.value = _activeAgentSessions.value - sessionId
        _agentStates.value = _agentStates.value - sessionId
        turnRuntime.setStreamingText(sessionId, null)
        turnRuntime.setStreamingReasoning(sessionId, null)
        turnRuntime.clearRunningTools(sessionId)
        _turnMessageKeys.remove(sessionId)
        turnRuntime.setRetryState(sessionId, null)
        composer.dropDraft(sessionId)
    }

    // region 会话管理

    /** 新建会话；当前会话为空则复用，否则切到工作区里已存在的空会话，都没有才新建。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val reusableId = _currentSessionId.value?.takeIf { sessionUseCase.isSessionEmpty(it) }
            ?: sessionUseCase.getEmptySessionOfWorkspace(_currentWorkspace.value)?.id
        if (reusableId != null) {
            setAgentState(reusableId, AgentUIState.Idle)
            _currentSessionId.value = reusableId
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server（让 manageMcp 新增的配置真正生效）；不阻塞会话创建，避免卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        _currentSessionId.value = createAndUpsertSession(_currentWorkspace.value)
    }

    fun setCurrentSessionId(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
    }

    fun setSessionMode(mode: AgentMode) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateMode(sid, mode.name)
            // 工作期间切换：把新模式作为系统事件投递给运行中的 agent，本轮即时生效（见 StatefulAgentWorkflow）。
            // 空闲时无需投递——下一轮开轮会按新模式注入一次提醒。
            if (sessionJobs[sid]?.isActive == true) {
                agentNotificationCenter.enqueue(
                    sid,
                    PendingNotification(
                        kind = AgentNotificationKind.MODE_CHANGE,
                        sourceId = "user",
                        title = mode.name,
                        outcome = NotificationOutcome.COMPLETED,
                        newMode = mode
                    )
                )
            }
        }
    }

    fun setSessionFastMode(enabled: Boolean) { _currentSessionId.value?.let { sid -> viewModelScope.launch { sessionUseCase.updateFastMode(sid, enabled) } } }

    fun setSessionReasoningEffort(effort: ReasoningEffort) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateReasoningEffort(sid, effort.name)
            // 同步记忆到模型级默认档位，供后续新建会话沿用
            val s = sessionUseCase.getSessionById(sid)?.toDomain()
            val pid = s?.providerId
            val model = s?.model
            if (!pid.isNullOrBlank() && !model.isNullOrBlank()) {
                modelReasoningEffortRepository.set(pid, model, effort.name)
            }
        }
    }

    fun setSessionProviderModel(providerId: String, model: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            sessionUseCase.updateProviderModel(sid, providerId, model)
            turnRuntime.clearContextUsage(sid)
            // 空会话中的选择视为「新会话默认模型」，供下次新建会话沿用
            if (sessionUseCase.isSessionEmpty(sid)) {
                defaultModelSettingsRepository.setDefaultModel(providerId, model)
            }
        }
    }

    /** 暴露给 UI：输入框 `/` 菜单展示的命令列表（内置命令 + 已启用技能）。 */
    val slashCommands: StateFlow<List<SlashCommand>> get() = slashCommandRegistry.commands

    /** 重新扫描技能并刷新命令菜单；进入 `/` 菜单时调用，反映技能的增删改。 */
    fun refreshSlashCommands() {
        viewModelScope.launch { slashCommandRegistry.refresh() }
    }

    /** /init —— 触发 agent 回合，分析代码库并生成/改进项目规则文件。 */
    override fun initProject(prompt: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            executeAgentRequestStream(
                request = prompt,
                projectRoot = workspaceOf(sid),
                targetSessionId = sid,
                isAutoTrigger = true,
                persistUserMessage = false
            ).join()
            // init 用写文件工具落的盘，不经过 RuleFileRepository，这里补一次整理。
            workspaceOf(sid).takeIf { it.isNotBlank() }?.let { ruleFileRepository.scheduleCurate(java.io.File(it)) }
        }
    }

    /** 技能触发 —— 技能正文仅作为模型输入，聊天里只留 `/技能名 参数`。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            executeAgentRequestStream(
                request = "/${skill.name} ${args}".trim(),
                modelRequest = SlashCommandRegistry.buildSkillPrompt(skill, args),
                projectRoot = workspaceOf(sid),
                targetSessionId = sid,
                isAutoTrigger = true,
                skipCommandDispatch = true
            )
        }
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val table = usageReport.renderMarkdown()
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table, isCompacted = true)
        }
    }

    /** /compress —— 手动触发当前会话的上下文压缩。 */
    override fun compactCurrentSession() {
        val sid = _currentSessionId.value ?: return
        if (isRunning) return
        sessionJobs[sid]?.let { if (it.isActive) return }
        val job = viewModelScope.launch {
            setCompacting(sid, true)
            var failed = false
            try {
                val changed = agentWorkflow.compactSession(sid) { event ->
                    when (event) {
                        is AgentEvent.CompactionStarted -> setCompacting(sid, true)
                        AgentEvent.CompactionFinished -> setCompacting(sid, false)
                        is AgentEvent.CompactionFailed -> {
                            failed = true
                            setCompacting(sid, false)
                            // 与自动压缩一致：落库无配对的 TOOL 消息渲染失败卡片，buildHistory 回放丢弃。
                            messagePersistenceUseCase.persist(
                                sessionId = sid,
                                role = MessageRole.TOOL,
                                content = event.reason,
                                toolName = COMPACTION_FAILURE_TOOL_NAME,
                                isError = true
                            )
                        }
                        else -> {}
                    }
                }
                // 压缩成功时 marker 分隔线 + 摘要卡片已提供反馈，不再落库重复的提示气泡；
                // 仅「无需压缩」（head 为空/无可压缩内容）时给出一条提示。
                if (!failed && !changed) {
                    messagePersistenceUseCase.persist(
                        sessionId = sid,
                        role = MessageRole.ASSISTANT,
                        content = context.getString(R.string.agent_context_no_compaction)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                FileLogger.e(TAG, "手动压缩失败: session=$sid", e)
                messagePersistenceUseCase.persist(
                    sessionId = sid,
                    role = MessageRole.ASSISTANT,
                    content = context.getString(R.string.agent_compaction_failed, e.message)
                )
            } finally {
                setCompacting(sid, false)
                // 压缩是异步流程，结束后接续队列中排队的下一条
                processNextInQueue(sid)
            }
        }
        sessionJobs[sid] = job
    }

    // 按请求 id 解析，而非隐式取队头：多会话并行时队头可能已换成别的会话的请求，
    // 界面上残留（退场动画）的那张卡片点下去就会批错对象。陈旧 id 解析不到即静默忽略。
    /** 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。 */
    fun approvePlanAndBuild(id: String) {
        planApprovalManager.resolve(id, PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。 */
    fun refinePlan(id: String) {
        planApprovalManager.resolve(id, PlanApprovalChoice.REFINE)
    }

    fun selectSession(id: String) {
        if (_currentSessionId.value == id) return
        _currentSessionId.value = id
        _sessionSwitchEvents.tryEmit(Unit)
        // 工作区跟着会话走：与「切工作区挑对应会话」（见 _currentWorkspace 的订阅）互为一对，
        // 两个方向都把会话与它绑定的仓库保持在一起。
        viewModelScope.launch {
            val path = sessionUseCase.getSessionById(id)?.workspacePath
            if (!path.isNullOrBlank() && path != workspaceRepository.currentPath()) {
                workspaceRepository.selectWorkspaceByPath(path)
            }
        }
    }

    fun deleteSessions(ids: Set<String>) = viewModelScope.launch {
        if (ids.isEmpty()) return@launch
        val allDeletedIds = mutableSetOf<String>()
        ids.forEach { id ->
            val deletedSession = sessionUseCase.getSessionById(id)
            if (deletedSession?.parentId != null) {
                val run = subAgentEventBus.currentRun(id)
                subAgentRunLifecycle.stopRun(id, deletedSession.parentId, deleted = true, broadcast = false, expectedRun = run)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            clearDeletedSessionState(sid)
        }

        if (_currentSessionId.value in allDeletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        // 删掉运行中的子代理时先取它的父会话与标题（删库后就取不到了），稍后告知父代理。
        // 删的是父会话时无需通知（父会话本身也没了），但级联删掉的子会话仍要交回并发槽位。
        val deletedSession = sessionUseCase.getSessionById(id)
        val deletedRun = subAgentEventBus.currentRun(id)
        val stoppedSubAgent = if (deletedSession?.parentId != null &&
            subAgentRunLifecycle.stopRun(id, deletedSession.parentId, deleted = true, broadcast = false, expectedRun = deletedRun)
        ) deletedSession else null

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            clearDeletedSessionState(sid)
        }

        if (_currentSessionId.value in deletedIds) {
            val ws = _currentWorkspace.value
            if (ws.isBlank()) {
                _currentSessionId.value = null
            } else {
                val remaining = sessionUseCase.getFirstSessionOfWorkspace(ws)
                if (remaining != null) {
                    _currentSessionId.value = remaining.id
                } else {
                    _currentSessionId.value = createAndUpsertSession(ws)
                }
            }
        }

        stoppedSubAgent?.let { sub ->
            messageRouter.notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。",
                deliver = deliverToSession
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    // 回退时因「被检查点之外的操作改过」而跳过的文件；非空时界面提示
    private val _rewindConflicts = MutableStateFlow<List<String>>(emptyList())
    val rewindConflicts: StateFlow<List<String>> = _rewindConflicts.asStateFlow()

    /** 打开回退菜单时算出的「将还原的文件数」上限，供确认提示展示。 */
    private val _rewindPreviewFiles = MutableStateFlow(0)
    val rewindPreviewFiles: StateFlow<Int> = _rewindPreviewFiles.asStateFlow()

    /** 回退时间线：每次恢复/撤销留下的现场，供用户挑一个节点撤销（新→旧）。 */
    private val _rewindUndoRecords = MutableStateFlow<List<CheckpointManager.UndoRecordInfo>>(emptyList())
    val rewindUndoRecords: StateFlow<List<CheckpointManager.UndoRecordInfo>> = _rewindUndoRecords.asStateFlow()

    fun dismissRewindConflicts() {
        _rewindConflicts.value = emptyList()
    }

    /** 撤销最近一次代码恢复（把当时的现场写回去）。 */
    fun undoLastRewind() = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        checkpointManager.undoLastRestore(sessionId)
    }

    fun openRewindMenu(messageId: String) {
        _targetRewindMessageId.value = messageId
        // 确认框要把「会动到几个文件」摆出来，故开菜单时先只读地数一遍（不动文件）。
        _rewindPreviewFiles.value = 0
        viewModelScope.launch {
            val sessionId = _currentSessionId.value ?: return@launch
            val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
            _rewindPreviewFiles.value = checkpoint
                ?.let { checkpointManager.countFilesToRestore(sessionId, it.id) }
                ?: 0
            _rewindUndoRecords.value = checkpointManager.listUndoRecords(sessionId)
        }
    }

    /** 撤销到指定的备份时间节点（[recordId] 为空即最新一份）。 */
    fun undoRewind(recordId: String?) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        checkpointManager.undoRestoreTo(sessionId, recordId)
        _rewindUndoRecords.value = checkpointManager.listUndoRecords(sessionId)
    }

    fun dismissRewindMenu() { _targetRewindMessageId.value = null }

    suspend fun previewRewindDiff(messageId: String): CheckpointManager.RewindDiff? =
        _currentSessionId.value?.let { sid -> checkpointDao.getCheckpointBySessionAndMessage(sid, messageId)?.let { runCatching { checkpointManager.previewRewindDiff(sid, it.id) }.getOrNull() } }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队
        sessionQueue.clear(sessionId)
        agentNotificationCenter.clear(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)
        _activeAgentSessions.value = _activeAgentSessions.value - sessionId

        // 2. 重置会话运行、流式与检查点状态
        setAgentState(sessionId, AgentUIState.Idle)
        turnRuntime.clearRunningTools(sessionId)
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        // 压缩标记/摘要行不承载可回退的内容：退到它们会撤销压缩并把保留的 tail 删空。
        val targetMsgEntity = messagePersistenceUseCase.restore(agentMessageDao.getMessageById(messageId)?.takeUnless { it.isCompactionMarker || it.isContextSummary } ?: return@launch)
        val attachments = targetMsgEntity.toUIMessage().attachments

        var conflicts: List<String> = emptyList()
        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    conflicts = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id).conflicts
                }
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.rewindConversation(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CODE -> {
                if (checkpoint != null) {
                    conflicts = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id).conflicts
                }
            }
            RewindOption.UNDO_LAST_RESTORE -> {
                checkpointManager.undoLastRestore(sessionId)
            }
        }
        // 回退可能删掉变体组里除一条以外的所有版本，只剩一条时必须恢复它的上下文参与权。
        agentMessageDao.repairSingletonVariantGroups(sessionId)
        _rewindConflicts.value = conflicts
    }

    /**
     * 错误横幅的一键重试：有进度（非空回答或工具结果）就接着续跑，删掉重跑会让模型把已完成的部分当新任务重做；之前完全没产出时才删除这一轮重跑。拿不到用户消息时也按续跑处理，且先收起横幅再取消旧任务。
     */
    fun retryLastFailedRequest() = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        val userMessage = lastUserInputMessage(sessionId)
        setAgentState(sessionId, AgentUIState.Idle)
        cancelRunningTurn(sessionId)
        FileLogger.i(TAG, "重试：sid=$sessionId hasUserMessage=${userMessage != null}")
        if (userMessage == null || turnHasProgressAfter(sessionId, userMessage.timestamp)) {
            executeAgentRequestStream(
                request = RESUMED_TURN_PROMPT,
                projectRoot = workspaceOf(sessionId),
                targetSessionId = sessionId,
                // 续跑轮不落用户消息、不建检查点、不改标题：引导语只是给模型的，不该变成聊天记录里的气泡。
                isAutoTrigger = true,
                persistUserMessage = false,
                skipTitleUpdate = true
            )
            return@launch
        }
        messagePersistenceUseCase.deleteMessagesFromTimestamp(sessionId, userMessage.timestamp)
        agentMessageDao.repairSingletonVariantGroups(sessionId)
        executeAgentRequestStream(
            request = userMessage.content,
            projectRoot = workspaceOf(sessionId),
            inputAttachments = decodeAttachmentJson(userMessage.attachmentsJson),
            targetSessionId = sessionId
        )
    }

    private suspend fun turnHasProgressAfter(sessionId: String, timestamp: Long): Boolean =
        agentMessageDao.getMessagesBySessionOnce(sessionId).any {
            it.timestamp > timestamp && (it.role == MessageRole.TOOL.name ||
                (it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()))
        }

    /**
     * 「重新生成」：旧回答连同本轮工具过程标成变体组的一版（退出上下文回放），重跑后新产出是下一版。
     */
    fun regenerateMessage(messageId: String) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        val target = agentMessageDao.getMessageById(messageId) ?: return@launch
        if (target.role != MessageRole.ASSISTANT.name) return@launch
        val all = agentMessageDao.getMessagesBySessionOnce(sessionId)
        val userMessage = all.lastOrNull {
            it.role == MessageRole.USER.name && !it.isCompactionMarker && !it.isContextSummary &&
                it.timestamp < target.timestamp
        } ?: return@launch
        // 只对最后一轮生效：重跑中间轮次会把它挪到会话末尾，时序就乱了。
        val hasLaterTurn = all.any {
            it.timestamp > userMessage.timestamp && it.role == MessageRole.USER.name &&
                !it.isCompactionMarker && !it.isContextSummary
        }
        if (hasLaterTurn) return@launch

        cancelRunningTurn(sessionId)

        val groupId = target.variantGroupId ?: target.id
        val turnIds = all.filter { it.timestamp > userMessage.timestamp }.map { it.id }
        val existingIndices = all.filter { it.variantGroupId == groupId }
            .map { it.variantIndex }.distinct().sorted()
        val nextIndex = (existingIndices.lastOrNull() ?: -1) + 1
        if (turnIds.isNotEmpty()) {
            if (existingIndices.isEmpty()) {
                // 首次重新生成：本轮产出整体成为版本 0。
                agentMessageDao.assignVariant(turnIds, groupId, 0, excluded = true)
            } else {
                agentMessageDao.updateContextExcluded(turnIds, excluded = true)
            }
        }
        // 只删用户消息行：本轮旧回答要留在库里供切换，不能按时间戳批量删。
        agentMessageDao.deleteMessageById(userMessage.id)
        runCatching { messageArchiveStore.deleteMessage(sessionId, userMessage.id) }
        messagePersistenceUseCase.invalidateHistory(sessionId)
        setAgentState(sessionId, AgentUIState.Idle)

        val startedAt = System.currentTimeMillis()
        executeAgentRequestStream(
            request = userMessage.content,
            projectRoot = workspaceOf(sessionId),
            inputAttachments = decodeAttachmentJson(userMessage.attachmentsJson),
            targetSessionId = sessionId
        ).join()

        // 本轮新产出的消息（用户消息之后新落库的）归入同一变体组的下一版。
        val newIds = agentMessageDao.getMessagesBySessionOnce(sessionId).filter {
            it.timestamp >= startedAt && it.variantGroupId == null && it.role != MessageRole.USER.name
        }.map { it.id }
        if (newIds.isNotEmpty()) {
            agentMessageDao.assignVariant(newIds, groupId, nextIndex, excluded = false)
        } else {
            // 新一轮没产出任何消息（如立刻报错）：组里只剩旧版本，得把它的上下文参与权还回去。
            agentMessageDao.repairSingletonVariantGroups(sessionId)
        }
        _variantSelections.value = _variantSelections.value + (groupId to nextIndex)
    }

    fun selectMessageVariant(groupId: String, position: Int) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value ?: return@launch
            val members = agentMessageDao.getMessagesBySessionOnce(sessionId)
                .filter { it.variantGroupId == groupId }
            val indices = members.map { it.variantIndex }.distinct().sorted()
            if (indices.isEmpty()) return@launch
            val clamped = position.coerceIn(0, indices.size - 1)
            val selected = indices[clamped]
            val selectedIds = members.filter { it.variantIndex == selected }.map { it.id }
            val otherIds = members.filter { it.variantIndex != selected }.map { it.id }
            if (otherIds.isNotEmpty()) agentMessageDao.updateContextExcluded(otherIds, excluded = true)
            if (selectedIds.isNotEmpty()) agentMessageDao.updateContextExcluded(selectedIds, excluded = false)
            messagePersistenceUseCase.invalidateHistory(sessionId)
            _variantSelections.value = _variantSelections.value + (groupId to clamped)
        }
    }

    fun deleteMessageVariant(groupId: String, position: Int) {
        viewModelScope.launch {
            val sessionId = _currentSessionId.value ?: return@launch
            val members = agentMessageDao.getMessagesBySessionOnce(sessionId)
                .filter { it.variantGroupId == groupId }
            val indices = members.map { it.variantIndex }.distinct().sorted()
            if (indices.size <= 1) return@launch
            val removed = indices[position.coerceIn(0, indices.size - 1)]
            val removedMessages = members.filter { it.variantIndex == removed }
            removedMessages.forEach {
                agentMessageDao.deleteMessageById(it.id)
                runCatching { messageArchiveStore.deleteMessage(sessionId, it.id) }
            }
            val remaining = indices.filter { it != removed }
            val next = (position - 1).coerceIn(0, remaining.size - 1)
            val selected = remaining[next]
            val selectedIds = members.filter { it.variantIndex == selected }.map { it.id }
            if (selectedIds.isNotEmpty()) agentMessageDao.updateContextExcluded(selectedIds, excluded = false)
            messagePersistenceUseCase.invalidateHistory(sessionId)
            _variantSelections.value = _variantSelections.value + (groupId to next)
        }
    }

    /** 会话里最后一条真正的用户输入（排除压缩锚点/摘要与后台通知）。 */
    private suspend fun lastUserInputMessage(sessionId: String): AgentMessageEntity? =
        agentMessageDao.getMessagesBySessionOnce(sessionId).lastOrNull {
            it.role == MessageRole.USER.name && !it.isCompactionMarker && !it.isContextSummary &&
                !it.content.startsWith(BACKGROUND_NOTIFICATION_PREFIX)
        }

    override fun askSideQuestion(question: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch { if (question.isNotBlank()) sideQuestionRunner.ask(sid, question) }
    }

    /** 中断当前轮并清掉运行态（重试 / 重新生成前调用），与回退流程同款收尾。 */
    private suspend fun cancelRunningTurn(sessionId: String) {
        sessionQueue.clear(sessionId)
        agentNotificationCenter.clear(sessionId)
        sessionJobs[sessionId]?.takeIf { it.isActive }?.cancelAndJoin()
        sessionJobs.remove(sessionId)
        _activeAgentSessions.value = _activeAgentSessions.value - sessionId
        turnRuntime.clearRunningTools(sessionId)
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        setKeySwitchState(sessionId, null)
    }
    /** 重命名会话标题。仅更新 title，不改 updatedAt，列表顺序保持不变。 */
    fun renameSession(id: String, newTitle: String) = viewModelScope.launch {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return@launch
        sessionUseCase.updateTitle(id, trimmed)
    }

    /** 置顶/取消置顶会话。置顶后排在列表最前（置顶分组），不改 updatedAt。 */
    fun togglePinSession(id: String) = viewModelScope.launch {
        val pinned = sessions.value.find { it.id == id }?.isPinned ?: return@launch
        sessionUseCase.updatePinned(id, !pinned)
    }

    /** 导出单个会话为无密码备份格式（tar.gz），流式写入 [output]（调用方打开，本方法负责关闭）。成功回调 true，失败回调 false。 */
    fun exportSession(sessionId: String, output: OutputStream, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        try {
            backupManager.exportSession(sessionId, output)
            onResult(true)
        } catch (e: Exception) {
            FileLogger.e("AIAgentViewModel", "exportSession failed", e)
            onResult(false)
        } finally {
            runCatching { output.close() }
        }
    }

    private suspend fun ensureSession(): String {
        _currentSessionId.value?.let { return it }
        val ws = _currentWorkspace.value
        if (ws.isBlank()) return ""
        val id = agentTurnRunner.ensureSession(ws)
        _currentSessionId.value = id
        return id
    }

    /** 新建会话并落库，返回 id。 */
    private suspend fun createAndUpsertSession(workspacePath: String): String {
        val s = createSession(workspacePath)
        sessionUseCase.upsertSession(s)
        return s.id
    }

    /**
     * 创建新会话并按「新会话默认模型」绑定 provider/model；未设置默认时回退全局 active provider。
     * 所有新建会话的入口（冷启动、新建、删除兜底、ensureSession）都走这里。
     */
    private suspend fun createSession(workspacePath: String): ChatSessionEntity =
        agentTurnRunner.createSessionEntity(workspacePath)

    // endregion

    fun updateMessageContent(messageId: String, newContent: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.updateContent(messageId, newContent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "更新消息失败", e)
        }
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        try {
            messagePersistenceUseCase.deleteMessage(messageId)
        } catch (e: Exception) {
            FileLogger.e(TAG, "删除消息失败", e)
        }
    }

    /**
     * 「从此处新建分支」：以消息 [messageId]（含）之前的历史另起一个会话，并自动切过去。
     *
     * 历史截齐与复制都在 [SessionForker]，这里只负责取当前会话与切过去。
     */
    fun forkSessionFromMessage(messageId: String) = viewModelScope.launch {
        try {
            val sourceId = _currentSessionId.value ?: return@launch
            val forkId = sessionForker.fork(sourceId, messageId, _currentWorkspace.value) ?: return@launch
            selectSession(forkId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "分叉会话失败", e)
        }
    }
}
