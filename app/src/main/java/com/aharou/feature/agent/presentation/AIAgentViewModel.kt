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
import com.aharou.core.util.GitIgnoreMatcher
import com.aharou.core.util.toUserMessage
import com.aharou.core.util.formatCostUsd
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.domain.checkpoint.CheckpointManager
import com.aharou.feature.agent.data.local.dao.CheckpointDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.dao.TodoItemDao
import com.aharou.feature.agent.domain.model.TodoItem
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.container.ContainerInitState
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.service.ModelCostCalculator
import com.aharou.feature.settings.domain.repository.AIProviderRepository
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
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationFormatter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.core.watch.FileChangeHub
import com.aharou.core.watch.asDirtySignal
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.schedule.ScheduledRunBus
import com.aharou.feature.agent.domain.schedule.ScheduledRunRequest
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.terminal.domain.TabFinishedEvent
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import com.aharou.feature.terminal.domain.TerminalSessionManager
import com.aharou.feature.terminal.domain.takeTailLines
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileEntry
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import com.aharou.feature.workspace.domain.isValidFileEntryName
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.tool.PendingToolPermission
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
import com.aharou.feature.agent.presentation.component.PendingUploadAttachment
import com.aharou.feature.agent.presentation.component.PastedText
import com.aharou.feature.agent.presentation.component.RewindOption
import com.aharou.feature.agent.presentation.component.expandPastePlaceholders
import com.aharou.feature.agent.presentation.component.formatTokenCount
import com.aharou.feature.agent.presentation.component.shouldPasteAsFile
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Calendar
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
    private val chatSessionDao: ChatSessionDao,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val modelCostCalculator: ModelCostCalculator,
    private val aiProviderRepository: AIProviderRepository,
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
    private val slashCommandRegistry: SlashCommandRegistry,
    private val checkpointManager: CheckpointManager,
    private val checkpointDao: CheckpointDao,
    private val backupManager: BackupManager,
    private val mcpManager: McpManager,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val keepaliveSettings: KeepaliveSettingsRepository,
    private val subAgentEventBus: SubAgentEventBus,
    private val scheduledRunBus: ScheduledRunBus,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val agentTurnRunner: AgentTurnRunner,
    private val todoItemDao: TodoItemDao,
    val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    /** Aharou：浏览器池 —— 聊天页“自动围观”面板与 browser 工具共用同一池。 */
    val browserTabPool: com.aharou.feature.browser.BrowserTabPool,
    /** Aharou：影子屏控制器 —— 聊天页小屏幕实时预览与 vscreen 工具共用。 */
    private val vdController: com.aharou.feature.agent.domain.vdisplay.VdController,
    private val workspaceRepository: com.aharou.feature.workspace.data.repository.WorkspaceRepository,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SlashCommandContext {

    private val sessionJobs = mutableMapOf<String, Job>()
    private val stoppingSessions = mutableMapOf<String, kotlinx.coroutines.CompletableDeferred<Unit>>()

    /** 记忆兑现节流：每个会话上次自动整理的时间戳，10 分钟内不重复跑。 */
    private val lastCurateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    /**
     * 用户主动切换会话的信号（仅 [selectSession] 触发，冷启动/恢复时的 id 变化不发）。
     * 聊天面板据此清空待发附件，避免把上一个会话的附件带过去。
     */
    private val _sessionSwitchEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val sessionSwitchEvents: SharedFlow<Unit> = _sessionSwitchEvents.asSharedFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val currentSessionTodoItems: StateFlow<List<TodoItem>> = _currentSessionId
        .flatMapLatest { id ->
            if (id.isNullOrBlank()) flowOf(emptyList())
            else todoItemDao.getBySession(id).map { entities -> entities.map { it.toDomain() } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _agentStates = MutableStateFlow<Map<String, AgentUIState>>(emptyMap())
    val agentStates: StateFlow<Map<String, AgentUIState>> = _agentStates.asStateFlow()

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

    /**
     * 失败文案：服务端给了类型码（如拒答/上下文超限）时用本地化说明，
     * 并附上服务端的具体理由（如果有）；无类型码时直接展示原错误文本。
     */
    private fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini 的 finishReason 全大写，这几种与 Anthropic 的 refusal 同义（内容策略拦截）。
            "refusal", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private val _messageLimit = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val defaultLimit = 30

    /** 聊天记录搜索：命中条数上限、输入防抖、片段上下文宽度、定位时预留的分页余量。 */
    private val chatSearchLimit = 50
    private val chatSearchDebounceMs = 300L
    private val messageLimitMargin = 5

    /**
     * 各会话各自的输入草稿，按会话区分持久化到磁盘：进程重启后草稿依然保留。
     * 以前是全局单一一份，在 A 打了半截话切到 B 那半截话会跟着跑过去。
     */
    private val draftPrefs = context.getSharedPreferences("agent_input_drafts", Context.MODE_PRIVATE)
    private val pastedPrefs = context.getSharedPreferences("agent_pasted_texts", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val _inputDrafts = MutableStateFlow<Map<String, String>>(
        draftPrefs.all.mapNotNull { (k, v) ->
            (v as? String)?.takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    )
    val inputDraft: StateFlow<String> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf("") else _inputDrafts.map { it[id].orEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun updateInputDraft(text: String) {
        val id = _currentSessionId.value ?: return
        val editor = draftPrefs.edit()
        if (text.isEmpty()) {
            _inputDrafts.value = _inputDrafts.value - id
            editor.remove(id)
        } else {
            _inputDrafts.value = _inputDrafts.value + (id to text)
            editor.putString(id, text)
        }
        editor.apply()
    }

    fun clearInputDraft() {
        val id = _currentSessionId.value ?: return
        _inputDrafts.value = _inputDrafts.value - id
        draftPrefs.edit().remove(id).apply()
    }

    /**
     * 折叠的粘贴块，按会话隔离。
     *
     * 与输入草稿共用同一生命周期：草稿持久化（draftPrefs），它也必须持久化，否则重启后
     * 草稿里留下的 `[Pasted#N]` 展开不出来，会把标记字面量发给模型。id 全局单调、不重用，
     * 免得旧标记撞上新内容。
     */
    private val _pastedTexts = MutableStateFlow(loadPastedTexts())
    val pastedTexts: StateFlow<List<PastedText>> = _currentSessionId
        .flatMapLatest { id -> _pastedTexts.map { it[id].orEmpty() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var nextPasteId: Int =
        (_pastedTexts.value.values.flatten().maxOfOrNull { it.id } ?: 0) + 1

    /**
     * 待发送附件（含外部分享投递进来的）。
     *
     * 状态必须活得比聊天面板的 Composable 长：分享文件是跨 Activity 投递的，面板若在投递前
     * 被销毁（冷启动时序、切换路由），remember 局部状态会连同附件一起丢，表现为「点了插入，
     * 当次不显示；退出重进才出现」。这里不按会话隔离——分享发生在会话确定之前，硬绑会话会让
     * 附件无处安放；切换会话时的清空统一在 init 里订阅。
     */
    private val _pendingAttachments = MutableStateFlow<List<PendingUploadAttachment>>(emptyList())
    internal val pendingAttachments: StateFlow<List<PendingUploadAttachment>> = _pendingAttachments.asStateFlow()

    internal fun addPendingAttachments(items: List<PendingUploadAttachment>) {
        if (items.isEmpty()) return
        _pendingAttachments.value = _pendingAttachments.value + items
    }

    internal fun setPendingAttachments(items: List<PendingUploadAttachment>) {
        _pendingAttachments.value = items
    }

    internal fun removePendingAttachmentAt(index: Int) {
        if (index !in _pendingAttachments.value.indices) return
        _pendingAttachments.value = _pendingAttachments.value.filterIndexed { i, _ -> i != index }
    }

    /**
     * 「加入输入栏」：把文件树里的一个文件 / 目录投递进待发附件列表。
     *
     * 目录只作为单个条目投递，不递归展开——具体要读什么交给 AI。这里也不预先拉本地副本：
     * 远程工作区拖一份大文件下来纯属浪费，分享 / 打开时再按需取（见 resolveLocalFile）。
     */
    internal fun attachWorkspaceEntryToInput(path: String) {
        if (path.isBlank()) return
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { entryAttachment(path) } ?: return@launch
            addPendingAttachments(listOf(item))
        }
    }

    /**
     * 「加入输入栏」：把编辑器里选中的片段落成文件再投递（附件通道只认文件）。
     *
     * 落在工作区 `.aharou/attachments/`（与分享进来的文件同目录，不污染源码树）。文件名取当前时间
     * （如 `20.22.txt`）——同一个文件选多少次都各成一个条目；同一分钟内选中多次时追加序号，
     * 后者不能把前者覆盖掉。
     */
    internal fun attachEditorSelectionToInput(sourcePath: String, text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            val item = withContext(Dispatchers.IO) { selectionAttachment(text) }
                ?: return@launch
            addPendingAttachments(listOf(item))
        }
    }

    /**
     * 读取待发附件的内容用于预览。
     *
     * 只给 UI 预览用，所以超过 [PREVIEW_MAX_CHARS] 就截断并在尾部标记——直接把几百 KB 的文本
     * 塞进 Compose 的 Text 会卡死主线程与测量。读不到（二进制/权限/已删）返回 null，由 UI 提示。
     */
    internal suspend fun readAttachmentText(containerPath: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val size = fileAccess.fileSize(containerPath)
            if (size > PREVIEW_MAX_BYTES) return@runCatching null
            val text = fileAccess.readFile(containerPath).take(PREVIEW_MAX_CHARS)
            if (!looksLikeText(text)) return@runCatching null
            text
        }.getOrNull()
    }

    /**
     * 二进制文件（zip / 图片 / 音频）读成字符串是一团乱码，比不预览还差。不维护后缀白名单，
     * 直接看内容：出现 NUL，或控制字符超过 5%，就当非文本——压缩包/媒体文件必定命中。
     */
    private fun looksLikeText(text: String): Boolean {
        if (text.isEmpty()) return true
        var bad = 0
        for (c in text) {
            if (c == '\u0000') return false
            if (c.code < 0x09 || c.code in 0x0E..0x1F) bad++
        }
        return bad * 100 <= text.length * 5
    }

    private fun entryAttachment(path: String): PendingUploadAttachment? {
        val name = path.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return null
        return runCatching {
            val isDirectory = fileAccess.isDirectory(path)
            PendingUploadAttachment(
                fileName = name,
                containerPath = path,
                localPath = "",
                mimeType = if (isDirectory) DIRECTORY_MIME_TYPE else mimeTypeForFileName(name),
                sizeBytes = if (isDirectory) 0L else fileAccess.fileSize(path),
                image = null
            )
        }.getOrNull()
    }

    private fun selectionAttachment(text: String): PendingUploadAttachment? = textAttachment(text, "")

    /**
     * 把一段文本落成 `.txt` 附件。[prefix] 只影响文件名（如 `pasted-20.22.txt`），
     * 好让人在附件卡片上一眼看出它从哪来。
     */
    private fun textAttachment(text: String, prefix: String): PendingUploadAttachment? {
        val stamp = java.time.LocalTime.now().format(SELECTION_STAMP_FORMAT)
        return runCatching {
            var fileName = "$prefix$stamp.txt"
            var index = 1
            while (fileAccess.exists("$ATTACHMENTS_DIR/$fileName")) {
                fileName = "$prefix$stamp-$index.txt"
                index += 1
            }
            val containerPath = "$ATTACHMENTS_DIR/$fileName"
            fileAccess.writeFile(containerPath, text, overwrite = false)
            PendingUploadAttachment(
                fileName = fileName,
                containerPath = containerPath,
                localPath = "",
                mimeType = mimeTypeForFileName(fileName),
                sizeBytes = fileAccess.fileSize(containerPath),
                image = null
            )
        }.getOrNull()
    }

    /** 按扩展名查 MIME：只用于附件卡片决定点击后怎么打开，查不到归为二进制流。 */
    private fun mimeTypeForFileName(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        if (extension.isEmpty()) return FALLBACK_MIME_TYPE
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: FALLBACK_MIME_TYPE
    }

    /** 缓冲 [text]，返回顶替它的 `[Pasted#N]` 标记。 */
    fun stashPastedText(text: String): String {
        val sessionId = _currentSessionId.value ?: return PastedText.placeholderFor(nextPasteId++)
        // 一次粘贴会被 IME / Compose 送来两遍（第二遍带的是原文而非增量），照单全收就会折两块、
        // 落两份文件。同内容直接复用已有的块与标记。
        _pastedTexts.value[sessionId].orEmpty().firstOrNull { it.text == text }
            ?.let { return it.placeholder }
        val id = nextPasteId++
        val entries = _pastedTexts.value[sessionId].orEmpty() + PastedText(id, text)
        _pastedTexts.value = _pastedTexts.value + (sessionId to entries)
        savePastedTexts(_pastedTexts.value)
        // 超大粘贴额外落一份 .txt：写成功就把标记抹了，内容由附件带走；写失败则什么都不做，
        // 标记留着，发送时照旧展开回原文——不丢东西。
        if (shouldPasteAsFile(text)) stashPastedFile(sessionId, id, text)
        return PastedText.placeholderFor(id)
    }

    private fun stashPastedFile(sessionId: String, id: Int, text: String) {
        viewModelScope.launch {
            val attachment = withContext(Dispatchers.IO) {
                textAttachment(text, PASTE_FILE_PREFIX)
            } ?: return@launch
            // 期间切了会话就什么都不做：标记留在草稿里，发送时展开回原文
            if (_currentSessionId.value != sessionId) return@launch
            // 附件与抹标记必须在同一次主线程续体里落地，中间一旦让出，用户恰好发送就会变成
            // 「文件 + 展开的原文」两份内容。
            addPendingAttachments(listOf(attachment))
            val marker = PastedText.placeholderFor(id)
            val draft = _inputDrafts.value[sessionId].orEmpty()
            if (marker in draft) updateInputDraft(draft.replace(marker, ""))
            val remaining = _pastedTexts.value[sessionId].orEmpty().filterNot { it.id == id }
            _pastedTexts.value =
                if (remaining.isEmpty()) _pastedTexts.value - sessionId
                else _pastedTexts.value + (sessionId to remaining)
            savePastedTexts(_pastedTexts.value)
        }
    }

    fun removePastedText(id: Int) {
        val sessionId = _currentSessionId.value ?: return
        val entries = _pastedTexts.value[sessionId].orEmpty().filterNot { it.id == id }
        _pastedTexts.value =
            if (entries.isEmpty()) _pastedTexts.value - sessionId
            else _pastedTexts.value + (sessionId to entries)
        savePastedTexts(_pastedTexts.value)
    }

    /** 发送时把标记还原成原文；[PastedText] 已被用户删掉的标记原样保留。 */
    fun expandPastes(text: String): String {
        val sessionId = _currentSessionId.value ?: return text
        val (expanded, consumed) = expandPastePlaceholders(
            text,
            _pastedTexts.value[sessionId].orEmpty(),
        )
        if (consumed.isEmpty()) return text
        val remaining = _pastedTexts.value[sessionId].orEmpty().filterNot { it.id in consumed }
        _pastedTexts.value =
            if (remaining.isEmpty()) _pastedTexts.value - sessionId
            else _pastedTexts.value + (sessionId to remaining)
        savePastedTexts(_pastedTexts.value)
        return expanded
    }

    private fun loadPastedTexts(): Map<String, List<PastedText>> {
        val raw = pastedPrefs.getString("buffers", null) ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, List<PastedTextEntry>>>(raw)
                .mapValues { (_, list) -> list.map { PastedText(it.id, it.text) } }
        }.getOrDefault(emptyMap())
    }

    private fun savePastedTexts(buffers: Map<String, List<PastedText>>) {
        val encoded = buffers.mapValues { (_, list) ->
            list.map { PastedTextEntry(it.id, it.text) }
        }
        pastedPrefs.edit().putString("buffers", json.encodeToString(encoded)).apply()
    }

    @Serializable
    private data class PastedTextEntry(val id: Int, val text: String)

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
     * 其余默认收起」推默认值（见 AIChatPanel.buildChatItems 的 activeTurnKey）；一旦用户手动点过，
     * 就以其选择为准，任务完成自动收起也不会覆盖它。
     */
    val turnExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次整轮折叠的手动展开/收起。 */
    fun setTurnExpanded(key: String, expanded: Boolean) {
        turnExpansionOverrides[key] = expanded
    }

    /**
     * 思考过程展开态的手动选择：key = 助手消息 id。与 [toolExpansionOverrides] 同款（进程内存、不落盘），
     * 使思考卡片划出视口回收、切页返回后仍保持展开/收起。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    /** 记录一次思考卡片的展开/收起。 */
    fun setReasoningExpanded(messageId: String, expanded: Boolean) {
        reasoningExpansionOverrides[messageId] = expanded
    }

    /** 思考过程耗时（毫秒）：key = 助手消息 id。记录流式思考的实际耗时，供落库后的卡片展示一位小数秒数。 */
    val reasoningDurations = mutableStateMapOf<String, Long>()

    fun loadMoreMessages() {
        val sid = _currentSessionId.value ?: return
        val currentLimit = _messageLimit.value[sid] ?: defaultLimit
        _messageLimit.value = _messageLimit.value + (sid to (currentLimit + 30))
    }

    /** 容器初始化实时进度（解压/部署/装包），AI 页底部气泡展示。 */
    val containerInit: StateFlow<ContainerInitState> = containerEngine.initProgress

    private val _currentWorkspace = MutableStateFlow<String>("")
    fun setWorkspace(path: String) {
        if (path.isBlank() || _currentWorkspace.value == path) return
        _currentWorkspace.value = path
        // 切到新工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根）。
        _expandedPaths.value = loadExpansion(path)
        // 搜索限定当前工作区，切区后旧结果无意义，一并清空。
        _chatSearchQuery.value = ""
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

    // ── 聊天记录全局搜索（限当前工作区）──
    private val _chatSearchQuery = MutableStateFlow("")
    val chatSearchQuery: StateFlow<String> = _chatSearchQuery.asStateFlow()

    /** 待定位的消息（会话 id to 消息 id）；聊天页滚动到位或确认无法定位后消费清除。 */
    private val _pendingScrollMessage = MutableStateFlow<Pair<String, String>?>(null)
    val pendingScrollMessage: StateFlow<Pair<String, String>?> = _pendingScrollMessage.asStateFlow()

    /**
     * 搜索状态：工作区与关键词变化时防抖后查询，命中映射为带片段的 UI 模型。
     * 查询走 IO 调度器，避免 LIKE 全表扫描卡住主线程。
     */
    val chatSearchState: StateFlow<ChatSearchState> =
        combine(_currentWorkspace, _chatSearchQuery) { ws, q -> ws to q }
            .debounce(chatSearchDebounceMs)
            .flatMapLatest { (workspace, query) ->
                val keyword = query.trim()
                if (workspace.isBlank() || keyword.isEmpty()) {
                    flowOf(ChatSearchState(query = query))
                } else {
                    flow {
                        emit(ChatSearchState(query = query, loading = true))
                        val hits = withContext(Dispatchers.IO) {
                            messagePersistenceUseCase.searchInWorkspace(
                                workspace,
                                keyword,
                                chatSearchLimit
                            ).map { m ->
                                ChatSearchHit(
                                    sessionId = m.sessionId,
                                    sessionTitle = m.sessionTitle,
                                    messageId = m.messageId,
                                    snippet = m.content,
                                    timestamp = m.timestamp
                                )
                            }
                        }
                        emit(ChatSearchState(query = query, hits = hits))
                    }
                }
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ChatSearchState())

    fun updateChatSearchQuery(query: String) {
        _chatSearchQuery.value = query
    }

    fun clearChatSearch() {
        _chatSearchQuery.value = ""
        _pendingScrollMessage.value = null
    }

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
            _pendingScrollMessage.value = hit.sessionId to hit.messageId
        }
    }

    fun consumePendingScroll() {
        _pendingScrollMessage.value = null
    }


    /** 侧边栏「文件」Tab 已展开的目录集合（容器路径）。含工作区根：根也可折叠，默认展开；按工作区持久化。 */
    private val _expandedPaths = MutableStateFlow(setOf(WorkspacePathMapper.CONTAINER_ROOT))

    /** 文件树展开状态按工作区持久化（重启保留）；key 为工作区路径，值为已展开的容器路径集合。 */
    private val expansionPrefs = context.getSharedPreferences("file_tree_expansion", Context.MODE_PRIVATE)

    /** 读取某工作区持久化的展开集；无记录时默认只展开工作区根。 */
    private fun loadExpansion(workspace: String): Set<String> =
        expansionPrefs.getStringSet(workspace, null)?.toSet()
            ?: setOf(WorkspacePathMapper.CONTAINER_ROOT)

    /** 持久化当前工作区的展开集（传新集合副本，SharedPreferences 禁止复用已存实例）。 */
    private fun saveExpansion(workspace: String, paths: Set<String>) {
        if (workspace.isBlank()) return
        expansionPrefs.edit().putStringSet(workspace, HashSet(paths)).apply()
    }
    val expandedPaths: StateFlow<Set<String>> = _expandedPaths.asStateFlow()

    /** 正在展开、等待列目录返回的目录路径（远程 SSH 下 listFiles 可能耗时数秒）；UI 在该行显示等待动画。 */
    private val _expandingPath = MutableStateFlow<String?>(null)
    val expandingPath: StateFlow<String?> = _expandingPath.asStateFlow()

    /** 正在执行写操作（新建/重命名/删除/复制/移动）的条目路径集合；UI 在对应行与工具栏显示等待动画。 */
    private val _fileOpPaths = MutableStateFlow<Set<String>>(emptySet())
    val fileOpPaths: StateFlow<Set<String>> = _fileOpPaths.asStateFlow()

    /** 手动刷新信号：远程模式无 inotify，只能靠它；本地模式作为兜底。 */
    private val _browseRefresh = MutableStateFlow(0)

    /**
     * 扁平化的可见文件树。listFiles 在本地是阻塞 IO、远程是网络调用，必须跑 IO 调度器。
     * 监听工作区根与所有已展开目录：任一发生变动（不限 AI，终端与其它 App 同样算）或手动刷新都会重建树。
     * 只监听展开中的目录、不递归整棵树，避免大仓库开出大量 inotify 句柄。
     */
    val browseState: StateFlow<FileBrowseState> = _expandedPaths
        .flatMapLatest { expanded ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map { fileChangeHub.watchWorkspace(it).asDirtySignal() }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) 丢掉 StateFlow 重建时的当前值，否则刚展开就会多读一次
                _browseRefresh.drop(1).map { }
            )
            flow {
                // 首次产出前由 stateIn 初值 Loading 占位；后续展开/折叠/刷新不再回到 Loading，
                // StateFlow 保留上一份 Success 直到新树就绪，避免闪加载动画与滚动位置丢失。
                emit(buildBrowseTree(expanded))
                triggers.collect { emit(buildBrowseTree(expanded)) }
            }.flowOn(Dispatchers.IO)
        }
        // 新树已就绪：清掉展开等待态（无论成功/出错都清，避免转圈卡死）。
        .onEach { _expandingPath.value = null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    /** 读取工作区根并按 [expanded] 递归展开，产出扁平的可见节点列表；根读取失败则整体报错。 */
    private fun buildBrowseTree(expanded: Set<String>): FileBrowseState {
        val root = WorkspacePathMapper.CONTAINER_ROOT
        val rootEntries = runCatching { fileAccess.listFiles(root) }.getOrElse { e ->
            FileLogger.w(TAG, "列目录失败: $root", e)
            return FileBrowseState.Error(e.message)
        }
        val ignorePatterns = loadRootGitignore(root)
        val rootExpanded = root in expanded
        val nodes = mutableListOf<FileTreeNode>()
        nodes += FileTreeNode(
            entry = FileEntry(name = "workspace", isDirectory = true, size = 0, lastModified = 0),
            path = root,
            depth = 0,
            isRoot = true,
            isExpanded = rootExpanded
        )
        if (rootExpanded) {
            appendBrowseChildren(root, rootEntries, depth = 1, expanded = expanded, ignorePatterns = ignorePatterns, relParts = emptyList(), out = nodes)
        }
        return FileBrowseState.Success(nodes)
    }

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!fileAccess.exists(path)) return emptyList()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    private fun appendBrowseChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
        // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = "$parent/${entry.name}"
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = false, ignored = ignored)
                continue
            }
            val children = runCatching { fileAccess.listFiles(path) }.getOrNull()
            out += FileTreeNode(entry, path, depth, isRoot = false, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendBrowseChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    fun refreshBrowse() {
        _browseRefresh.value++
    }

    /** 展开/折叠目录；折叠时连同其所有后代一并移出展开集，避免残留监听与再展开时意外深开。改变后按工作区持久化。
     *  展开需等列目录返回，期间标记 [expandingPath] 让 UI 显示等待动画，并忽略对同一目录的重复点击（否则会把它折回去）。 */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith("$path/") }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(_currentWorkspace.value, updated)
    }

    /**
     * 文件浏览的写操作共用包装：跑 IO 调度器，成功后主动重读目录（远程模式无 inotify）。
     * [busyPaths] 为本次操作涉及的条目路径，操作期间加入 [fileOpPaths] 让 UI 显示等待动画。
     * [block] 返回 false 表示名称非法或同名已存在，抛异常表示 IO 失败，两者均回报失败。
     */
    private fun mutateBrowse(
        busyPaths: Set<String> = emptySet(),
        onResult: (Boolean) -> Unit,
        block: () -> Boolean
    ) = viewModelScope.launch {
        if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value + busyPaths
        try {
            val success = withContext(Dispatchers.IO) {
                runCatching(block)
                    .onFailure { FileLogger.w(TAG, "文件操作失败", it) }
                    .getOrDefault(false)
            }
            if (success) refreshBrowse()
            onResult(success)
        } finally {
            if (busyPaths.isNotEmpty()) _fileOpPaths.value = _fileOpPaths.value - busyPaths
        }
    }

    /** [parent] 目录下的子路径；名称非法时返回 null。 */
    private fun browseChildPath(parent: String, name: String): String? =
        if (isValidFileEntryName(name)) "$parent/${name.trim()}" else null

    /** 在 [parent] 目录新建空文件。 */
    fun createBrowseFile(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.writeFile(target, "", overwrite = false)
                true
            }
        }
    }

    /** 在 [parent] 目录新建文件夹。 */
    fun createBrowseFolder(parent: String, name: String, onResult: (Boolean) -> Unit) {
        val target = browseChildPath(parent, name)
        mutateBrowse(busyPaths = target?.let { setOf(it) } ?: emptySet(), onResult = onResult) {
            if (target == null || fileAccess.exists(target)) {
                false
            } else {
                fileAccess.mkdirs(target)
                fileAccess.isDirectory(target)
            }
        }
    }

    /** 重命名条目（仅同目录内改名，不跨目录移动）。 */
    fun renameBrowseEntry(path: String, newName: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            val parent = path.substringBeforeLast('/', "")
            if (parent.isEmpty() || !isValidFileEntryName(newName)) {
                false
            } else {
                fileAccess.rename(path, "$parent/${newName.trim()}")
                true
            }
        }

    /** 删除条目；目录连同内容递归删除。 */
    fun deleteBrowseEntry(path: String, onResult: (Boolean) -> Unit) =
        mutateBrowse(busyPaths = setOf(path), onResult = onResult) {
            fileAccess.deleteRecursively(path)
            true
        }

    // region 文件复制 / 剪切 / 粘贴

    /** 文件浏览剪切板：复制或剪切后暂存源条目，供粘贴到其它目录。 */
    private val _browseClipboard = MutableStateFlow<BrowseClipboard?>(null)
    val browseClipboard: StateFlow<BrowseClipboard?> = _browseClipboard.asStateFlow()

    /** 复制条目进剪切板（剪切板只能存一项，直接覆盖旧的）。 */
    fun copyBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = false)
    }

    /** 剪切条目进剪切板（粘贴成功后删除源，且只允许粘贴一次）。 */
    fun cutBrowseEntry(path: String, name: String) {
        _browseClipboard.value = BrowseClipboard(path, name, isCut = true)
    }

    /** 清空剪切板。 */
    fun clearBrowseClipboard() {
        _browseClipboard.value = null
    }

    /** 粘贴冲突（目标已存在同名项）待用户确认覆盖。持有源路径与目标目录，确认后调 [pasteBrowseEntryOverwrite]。 */
    private val _pasteConflict = MutableStateFlow<Pair<String, String>?>(null)
    val pasteConflict: StateFlow<Pair<String, String>?> = _pasteConflict.asStateFlow()

    /** 把剪切板内容粘贴到 [targetDir]。目标已存在同名项时，发 [pasteConflict] 让 UI 弹窗询问是否覆盖，
     *  不执行粘贴；否则直接粘贴。粘贴成功即清空剪切板（无论复制/剪切，一次粘贴后失效），失败保留供重试。 */
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = "$targetDir/$name"
        if (target == clip.sourcePath || target.startsWith("${clip.sourcePath.trimEnd('/')}/")) {
            return@launch onResult(false)
        }
        if (withContext(Dispatchers.IO) { fileAccess.exists(target) }) {
            _pasteConflict.value = clip.sourcePath to target
            return@launch
        }
        performPaste(clip, target, overwrite = false, onResult)
    }

    /** 用户确认覆盖后强制粘贴（目标已存在同名项也会覆盖）。 */
    fun pasteBrowseEntryOverwrite() {
        val conflict = _pasteConflict.value ?: return
        val clip = _browseClipboard.value ?: return
        _pasteConflict.value = null
        performPaste(clip, conflict.second, overwrite = true) {}
    }

    /** 清空粘贴冲突待确认状态（用户点了「取消」时）。 */
    fun clearPasteConflict() {
        _pasteConflict.value = null
    }

    private fun performPaste(clip: BrowseClipboard, target: String, overwrite: Boolean, onResult: (Boolean) -> Unit) {
        val busyPaths = if (clip.isCut) setOf(clip.sourcePath, target) else setOf(target)
        mutateBrowse(busyPaths = busyPaths, onResult = { success ->
            // 无论复制还是剪切，粘贴成功即清空剪切板，避免重复粘贴；失败保留，允许重试。
            if (success) _browseClipboard.value = null
            onResult(success)
        }) {
            if (clip.isCut) {
                fileAccess.move(clip.sourcePath, target, overwrite)
                true
            } else {
                fileAccess.copy(clip.sourcePath, target, overwrite)
                true
            }
        }
    }

    // endregion

    /** 当前会话完整信息（根会话与子会话通用；null 表示尚未解析出会话）。 */
    val currentSessionState: StateFlow<ChatSession?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else chatSessionDao.getByIdFlow(id).map { it?.toDomain() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val currentSessionMode: StateFlow<AgentMode> = currentSessionState.map { it?.mode ?: AgentMode.BUILD }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentMode.BUILD)

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
            else agentMessageDao.getMessagesBySessionPaged(id, limit).map { list ->
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
                    isLoadingMore = false
                )
            }
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


    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())
    val runningTool: StateFlow<List<RunningToolOutput>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    private fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
        syncRuntimeStatus()
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    private fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
        syncRuntimeStatus()
    }

    /** 同步全局运行状态（悬浮窗等系统级 UI 读取；进程内零权限）。 */
    private fun syncRuntimeStatus() {
        val all = _runningTools.value.values.flatMap { it.values }
        val last = all.lastOrNull()
        com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus.set(
            busy = all.isNotEmpty(),
            toolName = last?.toolName.orEmpty(),
            statusText = last?.text.orEmpty(),
        )
    }

    private val _preparingTools = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * 模型正在流式产出、还没开始执行的工具名（如 `editFile`）。
     *
     * 长参数工具（写整份文件、长命令）的参数流式可能持续好几秒，期间没有正文也没有思考增量，
     * UI 只能显示笼统的「正在思考」。上游在工具名一出现就上报（[AgentEvent.ToolCallPreparing]），
     * UI 据此把状态换成具体场景（「正在编辑文件」）。工具真正开始执行后由 [runningTool] 接管。
     */
    val preparingTool: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _preparingTools.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setPreparingTool(sessionId: String, toolName: String?) {
        val current = _preparingTools.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) _preparingTools.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            _preparingTools.value = current + (sessionId to toolName)
        }
    }

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())
    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    private val _llmCallEvents = MutableSharedFlow<LlmCallEvent>(extraBufferCapacity = 16)
    /** 每次单次 LLM 请求返回事件（携带单次 Token 统计）。 */
    val llmCallEvents: SharedFlow<LlmCallEvent> = _llmCallEvents.asSharedFlow()
    val isCompacting: StateFlow<Boolean> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingText: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    val reasoningTiming: StateFlow<Pair<Long, Long?>?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())
    val streamingReasoning: StateFlow<String?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) _reasoningTimings.value = _reasoningTimings.value - sessionId
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    /** 按 sessionId 维护的重试状态；流式恢复或结束后置 null。 */
    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    val retryState: StateFlow<RetryState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    /** 按 sessionId 维护的多 Key 切换提示；重新出内容或本轮结束时置 null。 */
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())
    val keySwitchState: StateFlow<KeySwitchState?> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    val pendingToolPermission = toolPermissionManager.pendingRequest

    /** 当前展示的授权弹窗所属会话标题（多会话并行时供弹窗标注归属）。会话不存在时回退空串。 */
    val pendingToolPermissionSessionTitle: StateFlow<String> = combine(
        toolPermissionManager.pendingRequest,
        sessions,
        subSessionsByParent
    ) { req, roots, subs ->
        val sid = req?.sessionId.orEmpty()
        if (sid.isBlank()) return@combine ""
        val title = roots.firstOrNull { it.id == sid }?.title
            ?: subs.values.asSequence().flatten().firstOrNull { it.id == sid }?.title
        title.orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** 正在等待用户授权的会话 id 集合，供侧边栏会话行点亮橙色指示灯。 */
    val awaitingPermissionSessionIds: StateFlow<Set<String>> = toolPermissionManager.awaitingSessionIds

    val pendingUserQuestion = askUserQuestionManager.pendingQuestion

    private val _queuedRequests = MutableStateFlow<Map<String, List<QueuedRequest>>>(emptyMap())
    // 正在执行斜杠命令的会话集合：命令执行期间同样视为 busy（
    // 不注册 sessionJobs，否则 /compress 等命令内部的自检会误判为运行中），
    // 用于 enqueueAgentRequest 判断新消息应入队而非并行执行。
    private val _runningCommandSessions = MutableStateFlow<Set<String>>(emptySet())
    val queuedRequests: StateFlow<List<QueuedRequest>> = _currentSessionId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else _queuedRequests.map { it[id] ?: emptyList() }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pendingPlanApproval: StateFlow<PlanApprovalRequest?> = planApprovalManager.pendingApproval

    // 工具调用传入参数（argsPreview）按落库消息 id 暂存：ToolCallStarted 落库后，
    // ToolCallFinished / 用户停止会用同 id REPLACE 整行，需在此把参数带到后续落库。
    private val toolArgsByMsgId = mutableMapOf<String, String>()

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
        if (wakeLock.isHeld) return
        runCatching { wakeLock.acquire(KEEPALIVE_TIMEOUT_MS) }
            .onFailure { FileLogger.e(TAG, "acquire wakeLock failed", it) }
    }

    /** 任务收尾：释放唤醒锁；仅当既无后台终端任务、用户也没手动开保活时才停前台服务。 */
    private fun releaseKeepalive() {
        if (wakeLock.isHeld) {
            runCatching { wakeLock.release() }
                .onFailure { FileLogger.e(TAG, "release wakeLock failed", it) }
        }
        if (!userKeepaliveEnabled && !terminalSessionManager.hasBackgroundTabs()) {
            TerminalKeepaliveService.disablePersistent(context)
        }
    }

    /** App 退到后台时 Agent 完成，弹一条可点击的系统通知（标题=任务完成，正文=用户消息）。 */
    private fun showAgentCompletedNotification(userRequest: String) {
        val openAppIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, AGENT_COMPLETE_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(context.getString(R.string.agent_complete_notification_title))
            .setContentText(agentCompleteNotificationBody(userRequest))
            .setContentIntent(openAppIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(AGENT_COMPLETE_NOTIFICATION_ID, notification)
        }.onFailure { FileLogger.e(TAG, "发送 agent 完成通知失败", it) }
    }

    /**
     * 系统通知正文：普通用户消息直接展示；后台回调触发的轮次不裸露
     * 「[系统通知 - 非用户输入]…」内部构造文本，改为展示任务标题。
     */
    private fun agentCompleteNotificationBody(userRequest: String): String {
        if (userRequest.startsWith(BACKGROUND_NOTIFICATION_PREFIX)) {
            val titles = TASK_NOTIFICATION_TITLE_REGEX.findAll(userRequest)
                .map { it.groupValues[1] }
                .filter { it.isNotBlank() }
                .toList()
            return when {
                titles.isEmpty() -> context.getString(R.string.agent_complete_notification_body)
                titles.size == 1 -> context.getString(
                    R.string.agent_complete_notification_background_body, titles.first()
                )
                else -> context.getString(
                    R.string.agent_complete_notification_background_multi_body, titles.size
                )
            }
        }
        return userRequest.ifBlank { context.getString(R.string.agent_complete_notification_body) }
    }

    private companion object {
        const val TAG = "AIAgentViewModel"
        /** 「加入输入栏」投递的文件落点：当前工作区里的附件目录，与分享进来的文件同处一地。 */
        val ATTACHMENTS_DIR = "${WorkspacePathMapper.CONTAINER_ROOT}/.aharou/attachments"
        const val FALLBACK_MIME_TYPE = "application/octet-stream"
        /** 源文件没有可用文件名时（如无扩展名的隐藏文件）给选中片段兜个名字。 */
        /** 选中片段落盘的文件名格式（`20.22.txt`，当前时分）；同一分钟内的重名由序号兜底。 */
        val SELECTION_STAMP_FORMAT: java.time.format.DateTimeFormatter =
            java.time.format.DateTimeFormatter.ofPattern("HH.mm")

        /** 粘贴超长文本落盘时的文件名前缀，与选中片段区分开。 */
        const val PASTE_FILE_PREFIX = "pasted-"

        /** 附件预览的字节上限：超过就不预览，免得把大文件拉进内存。 */
        const val PREVIEW_MAX_BYTES = 256L * 1024

        /** 附件预览的字符上限。 */
        const val PREVIEW_MAX_CHARS = 200_000
        const val AGENT_COMPLETE_CHANNEL = "agent_complete"
        /** 记忆兑现节流：同一会话两次自动整理的最小间隔。 */
        const val MEMORY_CURATE_INTERVAL_MS = 10 * 60 * 1000L
        const val AGENT_COMPLETE_NOTIFICATION_ID = 100
        /** wakeLock 超时保险：构建、装依赖类工具动辄十几分钟，给足 60 分钟；任务正常结束会主动释放。 */
        const val KEEPALIVE_TIMEOUT_MS = 60 * 60 * 1000L
        /** 从后台任务通知文本中提取 <title> 内容，供系统通知正文展示。 */
        val TASK_NOTIFICATION_TITLE_REGEX = Regex("<title>([^<]+)</title>")
        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 写文件会连珠触发多个 inotify 事件，合并后再重读目录。 */
        const val BROWSE_DEBOUNCE_MS = 300L
    }

    init {
        // 冷启动收尾：上次进程被杀时残留的「执行中」工具回填为「已中断」。在后台执行，不阻塞首屏与会话展示。
        viewModelScope.launch(Dispatchers.IO) {
            sessionUseCase.initColdStartCleanup()
        }

        // 附件按会话隔离：用户主动切换会话时清空。原本挂在聊天面板的 collect 里，
        // 面板被销毁时清不掉，且分享投递与面板组合时序耦合，这里提到 VM 统一处理。
        viewModelScope.launch {
            _sessionSwitchEvents.collect { _pendingAttachments.value = emptyList() }
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

        // 订阅后台命令完成事件：notify=true 的命令结束后自动注入消息并触发 AI 新一轮。
        // 会话忙碌期间到达的事件会被缓存，待本轮结束后合并成一条发送（见 [flushMergedNotifications]）。
        viewModelScope.launch {
            terminalSessionManager.tabFinishedEvents.collect { event ->
                handleBackgroundCommandFinished(event)
            }
        }

        viewModelScope.launch {
            keepaliveSettings.enabledFlow.collect { userKeepaliveEnabled = it }
        }

        // 订阅子代理生命周期事件（类比 terminal 的 notify=true 异步回调）：
        // - SPAWNED：task 工具已创建子会话并替用户发消息，这里在子会话上自动启动 AI 工作流；
        // - STOPPED：task(action="stop") 请求停止子代理，取消对应会话的 AI 任务；
        // - COMPLETED/FAILED：由子会话工作流结束时发出（见 handleSubAgentFinished），注入父会话通知。
        viewModelScope.launch {
            subAgentEventBus.events.collect { event ->
                when (event.type) {
                    SubAgentEventType.SPAWNED -> spawnSubAgentWorkflow(event)
                    SubAgentEventType.STOPPED -> stopAgentSession(event.subSessionId)
                    SubAgentEventType.COMPLETED, SubAgentEventType.FAILED -> {
                        enqueueSubAgentNotification(event)
                    }
                    SubAgentEventType.MESSAGE_FROM_PARENT -> deliverMessageToSubAgent(event)
                    SubAgentEventType.MESSAGE_FROM_SUB -> deliverMessageToParent(event)
                }
            }
        }

        // 定时任务到点：Worker 经总线派发，这里负责真正跑起来（复用普通会话链路）。
        viewModelScope.launch {
            scheduledRunBus.requests.collect { request -> runScheduledTask(request) }
        }
    }

    /**
     * 跑一轮定时任务：目标会话存在就在它里面跑；没有目标会话就按任务记的工作区新建一个。
     * 标题用任务名，并跳过首条消息的标题推导，免得被指令内容覆盖。
     */
    private suspend fun runScheduledTask(request: ScheduledRunRequest) {
        val existing = request.targetSessionId?.let { sessionUseCase.getSessionById(it) }
        val sessionId = existing?.id ?: run {
            val root = request.workspacePath?.takeIf { it.isNotBlank() }
            if (root == null) {
                FileLogger.w(TAG, "定时任务缺少工作区且无目标会话，跳过: ${request.taskName}")
                return
            }
            val created = sessionUseCase.newSessionEntity(workspacePath = root).copy(title = request.taskName)
            sessionUseCase.upsertSession(created)
            created.id
        }
        val workspacePath = sessionUseCase.getSessionById(sessionId)?.workspacePath.orEmpty()
        FileLogger.i(TAG, "定时任务开始运行: ${request.taskName} -> session=$sessionId")
        executeAgentRequestStream(
            request = request.prompt,
            projectRoot = workspacePath,
            targetSessionId = sessionId,
            isAutoTrigger = true,
            skipTitleUpdate = true
        )
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
            return
        }
        // 子会话可能已被用户删除，跳过
        if (sessionUseCase.getSessionById(event.subSessionId) == null) {
            FileLogger.w(TAG, "子代理会话已被删除，跳过启动: ${event.subSessionId}")
            return
        }
        // 子代理运行中不允许重复启动（同一会话已有活跃 job）
        if (sessionJobs[event.subSessionId]?.isActive == true) return

        executeAgentRequestStream(
            request = event.detail,
            projectRoot = parentSession.workspacePath,
            targetSessionId = event.subSessionId,
            skipTitleUpdate = true
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
            notifyParentSubAgentFinished(
                parentSessionId = event.parentSessionId,
                subSessionId = event.subSessionId,
                title = title,
                outcome = if (event.type == SubAgentEventType.FAILED || event.accepted == false) {
                    NotificationOutcome.FAILED
                } else {
                    NotificationOutcome.COMPLETED
                },
                detail = event.detail.takeIf { it.isNotBlank() }
            )
        }
    }

    /**
     * 主会话发给运行中/已完成子代理的消息：收件人忙碌时入队搭车，空闲时触发新一轮。
     * 与 [notifyParentSubAgentFinished] 同分发逻辑，方向相反。
     */
    private suspend fun deliverMessageToSubAgent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.parentSessionId)?.title ?: "主会话"
        deliverAgentMessage(
            recipientSessionId = event.subSessionId,
            senderSessionId = event.parentSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = true
        )
    }

    /** 子代理发给主会话的消息：同上，收件人为主会话。 */
    private suspend fun deliverMessageToParent(event: SubAgentEvent) {
        val senderTitle = sessionUseCase.getSessionById(event.subSessionId)?.title ?: "子代理"
        deliverAgentMessage(
            recipientSessionId = event.parentSessionId,
            senderSessionId = event.subSessionId,
            senderTitle = senderTitle,
            message = event.detail,
            fromParent = false
        )
    }

    /**
     * 投递一条代理间消息：收件人忙碌时入 [AgentNotificationCenter]（本轮内工具结果搭车，或整轮结束后兜底），
     * 空闲时以一条通知消息触发其新一轮。消息正文随通知一并送达，收件方无需再另行读取。
     */
    private suspend fun deliverAgentMessage(
        recipientSessionId: String,
        senderSessionId: String,
        senderTitle: String,
        message: String,
        fromParent: Boolean
    ) {
        if (message.isBlank()) return
        if (sessionUseCase.getSessionById(recipientSessionId) == null) return
        val item = PendingNotification(
            kind = AgentNotificationKind.AGENT_MESSAGE,
            sourceId = senderSessionId,
            title = senderTitle,
            outcome = NotificationOutcome.COMPLETED,
            message = message,
            fromParent = fromParent
        )
        deliverSystemEvent(recipientSessionId, item)
    }

    /**
     * 向父会话投递一条子代理结束通知：父会话忙碌时入队搭车，空闲时立即注入触发新一轮。
     * 失败与被终止都要带上原因，否则父代理只知道「没成」，还得再 read 一次才可能拿到线索。
     */
    private suspend fun notifyParentSubAgentFinished(
        parentSessionId: String,
        subSessionId: String,
        title: String,
        outcome: NotificationOutcome,
        detail: String?
    ) {
        val item = PendingNotification(
            kind = AgentNotificationKind.SUBAGENT,
            sourceId = subSessionId,
            title = title,
            outcome = outcome,
            detail = detail
        )
        deliverSystemEvent(parentSessionId, item)
    }

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
     * 统一投递一条系统事件给指定会话：忙碌则入 [agentNotificationCenter]，由本轮内工具结果搭车送达；
     * 空闲则以一条系统通知消息触发新一轮。后台任务完成、子代理结束、代理间消息、模式切换共用此分发，
     * 避免各处重复判断忙碌/空闲。
     */
    /**
     * 会话绑定的工作区路径；会话不存在或没绑定时回退全局当前工作区。
     *
     * 让「文件落哪个工作区」跟会话走，而不是跟界面上选中的工作区走——否则切工作区后
     * 老会话的工具调用会写到新工作区里去。
     */
    private suspend fun workspaceOf(sessionId: String): String =
        sessionUseCase.getSessionById(sessionId)?.workspacePath?.takeIf { it.isNotBlank() }
            ?: _currentWorkspace.value

    private fun deliverSystemEvent(sessionId: String, item: PendingNotification) {
        if (sessionId in stoppingSessions || sessionJobs[sessionId]?.isCompleted == false) {
            agentNotificationCenter.enqueue(sessionId, item)
            return
        }
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(listOf(item)),
                projectRoot = workspaceOf(sessionId),
                targetSessionId = sessionId
            )
        }
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
        val items = agentNotificationCenter.drain(sessionId)
        if (items.isEmpty()) return
        FileLogger.d(TAG, "flushPendingNotifications: sid=$sessionId items=${items.size} state=${_agentStates.value[sessionId]}")
        viewModelScope.launch {
            enqueueAgentRequest(
                request = AgentNotificationFormatter.buildMessage(items),
                projectRoot = workspaceOf(sessionId),
                targetSessionId = sessionId
            )
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
        targetSessionId: String? = null
    ) {
        val sid = targetSessionId ?: _currentSessionId.value
        val isCurrentRunning = sid != null &&
            (sid in stoppingSessions || sessionJobs[sid]?.isCompleted == false || sid in _runningCommandSessions.value)
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
                isAutoTrigger = isAutoTrigger
            )
            val currentList = _queuedRequests.value[sid] ?: emptyList()
            _queuedRequests.value = _queuedRequests.value + (sid to (currentList + req))
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
                isAutoTrigger = isAutoTrigger
            )
        }
    }

    /** 从当前会话队列移除指定条目（队列面板删除按钮）。 */
    fun removeQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
    }

    /** 调整当前会话队列条目的顺序（队列面板拖拽排序）。 */
    fun moveQueuedRequest(fromIndex: Int, toIndex: Int) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        if (fromIndex !in queue.indices || toIndex !in queue.indices || fromIndex == toIndex) return
        val reordered = queue.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
        _queuedRequests.value = _queuedRequests.value + (sid to reordered)
    }

    /** 编辑当前会话队列条目的文本（队列面板编辑）。 */
    fun updateQueuedRequest(id: String, newText: String) {
        val text = newText.trim()
        if (text.isEmpty()) return
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.map { req ->
            if (req.id != id) req
            else req.copy(request = text, modelRequest = syncQueuedModelRequest(req, text))
        })
    }

    /**
     * 用编辑后的文本重建 modelRequest：无附件注入时与 request 同步；有附件注入时
     * （拼成 `request + "\n\n" + 附件清单`）只替换文本前缀、保留附件部分。
     */
    private fun syncQueuedModelRequest(req: QueuedRequest, newText: String): String {
        if (req.modelRequest == req.request) return newText
        val prefix = req.request.trimEnd()
        return if (req.modelRequest.startsWith(prefix)) {
            newText.trimEnd() + req.modelRequest.removePrefix(prefix)
        } else {
            newText
        }
    }

    /**
     * 把当前会话队列中某条消息立即插入正在运行的轮次：从队列移除，转成用户插话通知交给
     * [deliverSystemEvent]——忙碌时搭车注入本批工具结果，AI 当前轮即可感知；空闲时作为
     * 新一轮用户消息发送。
     */
    fun interjectQueuedRequest(id: String) {
        val sid = _currentSessionId.value ?: return
        val queue = _queuedRequests.value[sid] ?: return
        val req = queue.firstOrNull { it.id == id } ?: return
        _queuedRequests.value = _queuedRequests.value + (sid to queue.filterNot { it.id == id })
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
        val queue = _queuedRequests.value[sessionId] ?: return
        val next = queue.firstOrNull() ?: return
        _queuedRequests.value = _queuedRequests.value + (sessionId to queue.drop(1))
        executeAgentRequestStream(
            request = next.request,
            modelRequest = next.modelRequest,
            currentFile = next.currentFile,
            selectedCode = next.selectedCode,
            projectRoot = next.projectRoot,
            inputImages = next.inputImages,
            inputAttachments = next.inputAttachments,
            targetSessionId = sessionId,
            isAutoTrigger = next.isAutoTrigger
        )
    }

    /**
     * 执行斜杠命令：先把命令文本作为用户消息落库（进入对话上下文），再按类型派发——
     * 内置命令走本地动作，技能则把其正文作为本轮指令触发 agent 回合。
     * 执行期间标记为命令占用（防新消息并行执行），结束后接续队列中排队的下一条。
     */
    private fun runResolvedCommand(resolved: ResolvedCommand, input: String, sessionId: String) {
        viewModelScope.launch {
            _runningCommandSessions.value = _runningCommandSessions.value + sessionId
            try {
                messagePersistenceUseCase.persist(sessionId, MessageRole.USER, input)
                sessionUseCase.touch(sessionId, messagePersistenceUseCase.nextTimestamp())
                when (resolved) {
                    is ResolvedCommand.Action -> resolved.handler.execute(this@AIAgentViewModel, resolved.args)
                    is ResolvedCommand.SkillCommand -> runSkill(resolved.skill, resolved.args)
                }
            } finally {
                _runningCommandSessions.value = _runningCommandSessions.value - sessionId
                processNextInQueue(sessionId)
            }
        }
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
        skipTitleUpdate: Boolean = false
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
            if (request.startsWith("/")) {
                slashCommandRegistry.resolve(request)?.let { command ->
                    runResolvedCommand(command, request, sessionId)
                    return@launch
                }
            }
            sessionJobs[sessionId] = currentJob
            currentJob.invokeOnCompletion {
                viewModelScope.launch {
                    if (sessionJobs[sessionId] !== currentJob) return@launch
                    sessionJobs.remove(sessionId)
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
            var failed = false

            // 会话就绪、历史组装、工具装配、落库全在 runner 里；这里只管界面状态。
            var currentReasoningStart: Long? = null
            var currentReasoningEnd: Long? = null
            fun finishReasoning() {
                val start = currentReasoningStart ?: return
                if (currentReasoningEnd == null) {
                    val end = System.currentTimeMillis()
                    currentReasoningEnd = end
                    _reasoningTimings.value = _reasoningTimings.value + (sessionId to (start to end))
                }
            }
            agentTurnRunner.run(
                AgentTurnRequest(
                    sessionId = sessionId,
                    text = request,
                    modelRequest = modelRequest,
                    currentFile = currentFile,
                    selectedCode = selectedCode,
                    projectRoot = projectRoot,
                    inputImages = inputImages,
                    inputAttachments = inputAttachments,
                    isAutoTrigger = isAutoTrigger,
                    skipTitleUpdate = skipTitleUpdate
                )
            ).collect { event ->
                if (sessionJobs[sessionId] !== currentJob) return@collect
                when (event) {
                    is AgentEvent.AssistantDelta -> {
                        if (event.accumulated.hasVisibleContent()) finishReasoning()
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setStreamingText(sessionId, event.accumulated)
                    }
                    is AgentEvent.ReasoningDelta -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        if (currentReasoningStart == null) {
                            currentReasoningStart = System.currentTimeMillis()
                            currentReasoningEnd = null
                        }
                        _reasoningTimings.value = _reasoningTimings.value +
                            (sessionId to (currentReasoningStart!! to currentReasoningEnd))
                        setStreamingReasoning(sessionId, event.accumulated)
                    }
                    is AgentEvent.ToolCallPreparing -> {
                        finishReasoning()
                        // 工具名先于参数到达：让 UI 把「正在思考」换成具体场景（「正在编辑文件」）。
                        // 参数流完、工具真正开始执行后由 ToolCallStarted 清掉，改由工具行表达。
                        setPreparingTool(sessionId, event.toolName)
                    }
                    is AgentEvent.Retrying -> {
                        currentReasoningStart = null
                        currentReasoningEnd = null
                        setRetryState(sessionId, RetryState(event.attempt, event.maxRetries, event.error))
                        // 重试会从头重新流式输出：清掉已展示的正文/思维链气泡，
                        // 否则重连后思维链重新生成而旧正文残留（workflow 已同步清空累积器）。
                        setStreamingText(sessionId, null)
                        setStreamingReasoning(sessionId, null)
                        setKeySwitchState(sessionId, null)
                    }
                    is AgentEvent.KeySwitched -> {
                        currentReasoningStart = null
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
                        _contextUsages.value = _contextUsages.value - sessionId
                    }
                    is AgentEvent.ContextUsage -> {
                        _contextUsages.value = _contextUsages.value + (sessionId to event)
                    }
                    is AgentEvent.CompactionFailed -> {
                        setCompacting(sessionId, false)
                        // 失败记录的落库在 runner 里做（无配对的 TOOL 消息，界面渲染成失败卡片）
                    }
                    is AgentEvent.AssistantText -> {
                        val reasoningDuration = currentReasoningStart?.let { System.currentTimeMillis() - it }
                        currentReasoningStart = null

                        // 流式收尾：落库在 runner 里做，但它先把事件转给我们，
                        // 所以这里能先清空流式状态，避免「落库消息与流式气泡同屏并存」的时差。
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
                    is AgentEvent.ToolCallStarted -> {
                        val msgId = "tool_${event.id}"
                        setStreamingText(sessionId, null)
                        setPreparingTool(sessionId, null)
                        toolArgsByMsgId[msgId] = event.argsPreview
                        setRunningTool(sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
                    }
                    is AgentEvent.ToolCallProgress -> {
                        val msgId = "tool_${event.id}"
                        setRunningTool(sessionId, msgId, RunningToolOutput(
                            msgId,
                            event.accumulated,
                            event.toolName,
                            toolArgsByMsgId[msgId] ?: ""
                        ))
                    }
                    is AgentEvent.ToolCallFinished -> {
                        val msgId = "tool_${event.id}"
                        toolArgsByMsgId.remove(msgId)
                        removeRunningTool(sessionId, msgId)
                    }
                    is AgentEvent.Failed -> {
                        failed = true
                        setCompacting(sessionId, false)
                        val failureMessage = describeFailure(event)
                        setAgentState(sessionId, AgentUIState.Error(failureMessage))
                        // 落库：重开 App / 切会话后错误横幅与一键重试仍在。
                        persistFailureMessage(sessionId, failureMessage)
                        // 子代理失败时通知父会话的事在 runner 里做
                    }
                    AgentEvent.Completed -> {
                        setRetryState(sessionId, null)
                        setKeySwitchState(sessionId, null)
                        setCompacting(sessionId, false)
                        // 子代理完成时通知父会话的事在 runner 里做
                        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
                        val inForeground = ProcessLifecycleOwner.get().lifecycle.currentState
                            .isAtLeast(Lifecycle.State.STARTED)
                        if (!inForeground && agentSoundSettings.isEnabled()) {
                            showAgentCompletedNotification(modelRequest)
                        }
                        // 引擎级记忆兜底：主模型当轮没调 memory 工具时，后台用轻量模型抽取沉淀。
                        // 静默失败、不进对话流；同一会话 10 分钟内不重复跑。
                        val now = System.currentTimeMillis()
                        if (now - (lastCurateAt[sessionId] ?: 0L) >= MEMORY_CURATE_INTERVAL_MS) {
                            viewModelScope.launch {
                                // 本轮对话文本：用户请求 + 流式回答快照。
                                val transcript = buildString {
                                    append("用户: ").appendLine(modelRequest)
                                    _streamingTexts.value[sessionId]?.take(4000)
                                        ?.let { append("助手: ").appendLine(it) }
                                }.trim()
                                if (transcript.isNotBlank()) {
                                    agentWorkflow.curateMemory(sessionId, projectRoot, transcript)
                                    // 成功后才记账：失败/取消时不占用 10 分钟窗口，下一轮重试。
                                    lastCurateAt[sessionId] = System.currentTimeMillis()
                                }
                            }
                        }
                    }
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
            if (!failed && (finishedState is AgentUIState.Loading || finishedState is AgentUIState.Streaming)) {
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
             FileLogger.e(TAG, "executeAgentRequestStream 失败: request=$request", e)
             if (sessionJobs[sessionId] === currentJob) {
                 val failureMessage = e.toUserMessage()
                 setAgentState(sessionId, AgentUIState.Error(failureMessage))
                 persistFailureMessage(sessionId, failureMessage)
                 _completedSessions.value = _completedSessions.value - sessionId
             }
        } finally {
            val isOwnJob = sessionJobs[sessionId] == coroutineContext[Job]
            FileLogger.d(TAG, "stream finally: sid=$sessionId isOwnJob=$isOwnJob state=${_agentStates.value[sessionId]}")
            if (isOwnJob) {
                _runningTools.value = _runningTools.value - sessionId
                setStreamingText(sessionId, null)
                setStreamingReasoning(sessionId, null)
                setPreparingTool(sessionId, null)
                setCompacting(sessionId, false)
                setRetryState(sessionId, null)
                setKeySwitchState(sessionId, null)
                val currentState = _agentStates.value[sessionId]
                if (currentState !is AgentUIState.Error && currentState !is AgentUIState.Loading && currentState !is AgentUIState.Streaming) {
                    setAgentState(sessionId, AgentUIState.Idle)
                }
            }
        }
    }

    fun resolveToolPermission(id: String, choice: PermissionChoice) {
        toolPermissionManager.resolve(id, choice)
    }

    fun resolveUserQuestion(id: String, answer: UserQuestionAnswer) {
        askUserQuestionManager.resolve(id, answer)
    }

    /**
     * 停止所有正在运行的 AI 会话并关闭终端标签（切换/重置容器前调用）。
     *
     * 容器 rootfs 会被删掉，会话与终端必须全停；切工作区不走这里——那个只换目录，什么都不用停。
     */
    fun stopAllAndCloseTerminal() {
        stopAllAgents()
        terminalSessionManager.tabs.value.map { it.id }.forEach { terminalSessionManager.closeTab(it) }
    }

    /** 停止所有正在运行的 AI 会话（仅由容器切换/重置流程调用）。 */
    fun stopAllAgents() {
        val jobs = sessionJobs.values.filter { it.isActive }
        jobs.forEach { it.cancel() }
        sessionJobs.clear()
        agentNotificationCenter.clearAll()
        _queuedRequests.value = emptyMap()
        _runningCommandSessions.value = emptySet()
        _agentStates.value = _agentStates.value.mapValues { AgentUIState.Idle }
        syncRuntimeActive()
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
        releaseKeepalive()
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
     * 走「软打断」：只投递一条 [AgentNotificationKind.USER_INTERRUPT]，agent 在当前这步做完后
     * 就不再继续（不再调下一个工具、不再起新轮）。**不取消 job**——正在跑的命令与网络请求
     * 留在后台跑完，这是用户要的语义：「任务放着继续做，你先停下来听我说」（2026-10-07 主人明确）。
     * 界面立刻放开，不等收尾（旧写法 cancel + 等 join，命令一慢就永久卡住停止按钮）。
     */
    fun stopAgentSession(sessionId: String) {
        if (sessionId in stoppingSessions) return
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        releaseStoppedSubAgent(sessionId)
        agentNotificationCenter.enqueue(
            sessionId,
            PendingNotification(
                kind = AgentNotificationKind.USER_INTERRUPT,
                sourceId = "user",
                title = context.getString(R.string.agent_stopped_by_user),
                outcome = NotificationOutcome.STOPPED
            )
        )
        FileLogger.d(TAG, "stopAgent: 投递用户打断 sid=$sessionId state=${_agentStates.value[sessionId]}")
        // stoppingSessions 只用于「停止期间不再起新轮」；清理由后台等 job 真正结束时做，不挡界面。
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        stoppingSessions[sessionId] = stopped
        viewModelScope.launch {
            job.join()
            if (stoppingSessions[sessionId] === stopped) stoppingSessions.remove(sessionId)
            stopped.complete(Unit)
            if (sessionJobs[sessionId]?.let { it !== job } != true) {
                flushPendingNotifications(sessionId)
                processNextInQueue(sessionId)
            }
        }
        // 界面立刻放开：输入框马上能用，不用等后台那步收尾。
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        setAgentState(sessionId, AgentUIState.Idle)
    }

    /**
     * 强制打断指定会话（用户长按停止键）。与 [stopAgentSession] 的软打断相对：软打断只投一条
     * USER_INTERRUPT，在跑的命令留着跑完；这里直接取消协程，[LinuxContainerEngine] 的取消钩子会把
     * 容器内进程 destroy 掉，挂起的网络请求同时断开。
     *
     * 不投 USER_INTERRUPT：那条通知是给主循环看的开关，协程一取消就没人消费它，残留到下一轮
     * 会在主循环顶部把新一轮也一并打断。
     */
    fun forceStopAgentSession(sessionId: String) {
        if (sessionId in stoppingSessions) return
        val job = sessionJobs[sessionId] ?: return
        if (!job.isActive) return
        releaseStoppedSubAgent(sessionId)
        // 取消前先取快照：协程被取消后 _runningTools 与待授权请求会随之清空，那时就取不到了。
        val runningTools = _runningTools.value[sessionId]?.values?.toList() ?: emptyList()
        val pendingPermission = toolPermissionManager.pendingForSession(sessionId)
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        FileLogger.d(
            TAG,
            "forceStopAgent: sid=$sessionId runningTools=${runningTools.size} pendingPerm=${pendingPermission?.id}"
        )
        val stopped = kotlinx.coroutines.CompletableDeferred<Unit>()
        stoppingSessions[sessionId] = stopped
        job.cancel()
        viewModelScope.launch {
            job.join()
            if (stoppingSessions[sessionId] === stopped) stoppingSessions.remove(sessionId)
            stopped.complete(Unit)
            if (sessionJobs[sessionId]?.let { it !== job } == true) return@launch
            settleStoppedTools(sessionId, runningTools, pendingPermission, stoppedText)
            setStreamingText(sessionId, null)
            setStreamingReasoning(sessionId, null)
            setCompacting(sessionId, false)
            setRetryState(sessionId, null)
            setAgentState(sessionId, AgentUIState.Idle)
            flushPendingNotifications(sessionId)
            processNextInQueue(sessionId)
        }
        // 界面立刻放开：与软打断一致，不等后台那份收尾。
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        setAgentState(sessionId, AgentUIState.Idle)
    }

    /**
     * 强制打断后的收尾：把还挂在「执行中」的工具占位行落库为「已停止」，否则会留下悬挂的 spinner。
     *
     * 授权弹窗挂起中的调用同样要补：awaitApproval 挂起期间 [_runningTools] 为空（ToolCallStarted 在
     * 授权通过后才发出），但 assistant 消息里已经落了带 tool_call 的声明。不补结果，这条 tool_call
     * 就成了孤儿记录，被 buildHistory 的 validIds 交集滤掉，AI 不知道自己曾调用过。
     */
    private suspend fun settleStoppedTools(
        sessionId: String,
        runningTools: List<RunningToolOutput>,
        pendingPermission: PendingToolPermission?,
        stoppedText: String
    ) {
        suspend fun needsStoppedResult(messageId: String): Boolean {
            val message = agentMessageDao.getMessageById(messageId) ?: return true
            return message.sessionId == sessionId && message.role == MessageRole.TOOL.name &&
                (message.content.startsWith(SessionUseCase.PENDING_TOOL_MARKER) ||
                    message.content.startsWith(SessionUseCase.LEGACY_PENDING_TOOL_MARKER))
        }
        runningTools.forEach { running ->
            if (!needsStoppedResult(running.messageId)) {
                toolArgsByMsgId.remove(running.messageId)
                return@forEach
            }
            val partial = running.text.trimEnd()
            val content = if (partial.isNotEmpty()) "$partial\n\n$stoppedText" else stoppedText
            messagePersistenceUseCase.persist(
                sessionId = sessionId,
                role = MessageRole.TOOL,
                content = content,
                id = running.messageId,
                toolCallId = running.messageId.removePrefix("tool_"),
                toolName = running.toolName.ifBlank { null },
                toolArgs = running.toolArgs.ifBlank { toolArgsByMsgId[running.messageId] },
                isError = true
            )
            toolArgsByMsgId.remove(running.messageId)
        }
        if (pendingPermission != null && needsStoppedResult("tool_${pendingPermission.id}")) {
            val msgId = "tool_${pendingPermission.id}"
            messagePersistenceUseCase.persist(
                sessionId = sessionId,
                role = MessageRole.TOOL,
                content = stoppedText,
                id = msgId,
                toolCallId = pendingPermission.id,
                toolName = pendingPermission.toolName,
                isError = true
            )
        }
    }

    /** 停止运行中的子代理：交回并发槽位并告知父代理，否则槽位泄漏到进程重启。 */
    private fun releaseStoppedSubAgent(sessionId: String) {
        // TaskTool 的 stop/del 已在 emit(STOPPED) 时释放过，那条路径下 release 返回 false，不会重复通知。
        if (!subAgentEventBus.release(sessionId)) return
        viewModelScope.launch {
            val sub = sessionUseCase.getSessionById(sessionId)
            val parentId = sub?.parentId
            if (parentId != null) {
                notifyParentSubAgentFinished(
                    parentSessionId = parentId,
                    subSessionId = sessionId,
                    title = sub.title,
                    outcome = NotificationOutcome.STOPPED,
                    detail = "用户在界面上手动停止了这个子代理，任务未完成。"
                )
            }
        }
    }

    // region 会话管理

    /** 新建会话；若当前会话还是空的则直接复用，避免堆积空会话。 */
    fun newSession() = viewModelScope.launch {
        if (_currentWorkspace.value.isBlank()) return@launch
        val curId = _currentSessionId.value
        if (curId != null && sessionUseCase.isSessionEmpty(curId)) {
            setAgentState(curId, AgentUIState.Idle)
            return@launch
        }
        // 新会话时异步重连未连接的 MCP server，让 manageMcp 新增的配置真正生效；
        // 不阻塞会话创建——MCP 环境未就绪/超时时不能卡住「新建会话」。
        mcpManager.reconnectUnconnectedAsync()
        val sid = createAndUpsertSession(_currentWorkspace.value)
        _currentSessionId.value = sid
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
            _contextUsages.value = _contextUsages.value - sid
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
                isAutoTrigger = true
            )
        }
    }

    /** 技能触发 —— 把技能正文作为本轮指令执行。 */
    override fun runSkill(skill: Skill, args: String) {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            executeAgentRequestStream(
                request = SlashCommandRegistry.buildSkillPrompt(skill, args),
                projectRoot = workspaceOf(sid),
                targetSessionId = sid,
                isAutoTrigger = true
            )
        }
    }

    /** /usage —— 以 Markdown 表格输出今日与累计的调用次数、token 用量与预估费用。 */
    override fun showUsage() {
        val sid = _currentSessionId.value ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val todayStart = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val today = loadUsageSummary(todayStart)
            val allTime = loadUsageSummary(0L)
            val table = buildString {
                appendLine("| 项目 | 今日 | 累计 |")
                appendLine("|---|---|---|")
                appendLine("| 调用次数 | ${today.calls} | ${allTime.calls} |")
                appendLine("| 输入 tokens | ${formatTokenCount(today.inputTokens)} | ${formatTokenCount(allTime.inputTokens)} |")
                appendLine("| 输出 tokens | ${formatTokenCount(today.outputTokens)} | ${formatTokenCount(allTime.outputTokens)} |")
                appendLine("| 缓存命中 tokens | ${formatTokenCount(today.cachedInputTokens)} | ${formatTokenCount(allTime.cachedInputTokens)} |")
                appendLine("| 预估费用 | ${formatCostUsd(today.costUsd)} | ${formatCostUsd(allTime.costUsd)} |")
            }
            sessionUseCase.touch(sid, messagePersistenceUseCase.nextTimestamp())
            messagePersistenceUseCase.persist(sid, MessageRole.ASSISTANT, table.trimEnd(), isCompacted = true)
        }
    }

    private data class UsageSummary(
        val calls: Int,
        val inputTokens: Long,
        val outputTokens: Long,
        val cachedInputTokens: Long,
        val costUsd: Double
    )

    /** 汇总自 [start] 起的调用次数、token 与预估费用；费用按「渠道 + 模型」分组逐组估算，才取得到自定义单价。 */
    private suspend fun loadUsageSummary(start: Long): UsageSummary = withContext(Dispatchers.IO) {
        val summary = llmCallRecordDao.getSummary(start).first()
        val providerTypes = aiProviderRepository.getAllProviders().first().associate { it.id to it.type }
        val cost = llmCallRecordDao.getModelProviderCostStats(start).first().sumOf { stat ->
            val model = stat.model ?: return@sumOf 0.0
            modelCostCalculator.costUsd(
                providerId = stat.providerId.orEmpty(),
                providerType = stat.providerId?.let { providerTypes[it] } ?: ProviderType.OPENAI,
                model = model,
                inputTokens = stat.inputTokens,
                cachedInputTokens = stat.cachedInputTokens,
                outputTokens = stat.outputTokens,
                cacheCreationTokens = stat.cacheCreationTokens
            ) ?: 0.0
        }
        UsageSummary(summary.calls, summary.inputTokens, summary.outputTokens, summary.cachedInputTokens, cost)
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

    /** 用户批准计划，唤醒 workflow 继续在 BUILD 模式执行。 */
    fun approvePlanAndBuild() {
        planApprovalManager.resolve(PlanApprovalChoice.APPROVE)
    }

    /** 用户选择继续反馈，唤醒 workflow 回滚到 PLAN 模式。 */
    fun refinePlan() {
        planApprovalManager.resolve(PlanApprovalChoice.REFINE)
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
                subAgentEventBus.release(id)
            }
            checkpointManager.clearSessionCheckpoints(id)
            val deleted = sessionUseCase.deleteSession(id)
            allDeletedIds.addAll(deleted)
        }

        allDeletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            _streamingReasonings.value = _streamingReasonings.value - sid
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
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
        val stoppedSubAgent = deletedSession?.takeIf { it.parentId != null && subAgentEventBus.release(id) }

        checkpointManager.clearSessionCheckpoints(id)
        val deletedIds = sessionUseCase.deleteSession(id)

        deletedIds.forEach { sid ->
            subAgentEventBus.release(sid)
            sessionJobs[sid]?.cancel()
            sessionJobs.remove(sid)
            _agentStates.value = _agentStates.value - sid
            _streamingTexts.value = _streamingTexts.value - sid
            _streamingReasonings.value = _streamingReasonings.value - sid
            _runningTools.value = _runningTools.value - sid
            _retryStates.value = _retryStates.value - sid
            _queuedRequests.value = _queuedRequests.value - sid
            _inputDrafts.value = _inputDrafts.value - sid
            draftPrefs.edit().remove(sid).apply()
            agentNotificationCenter.clear(sid)
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
            notifyParentSubAgentFinished(
                parentSessionId = sub.parentId!!,
                subSessionId = sub.id,
                title = sub.title,
                outcome = NotificationOutcome.STOPPED,
                detail = "用户删除了这个子代理会话，任务未完成，其对话记录已不可读取。"
            )
        }
    }

    // Checkpoint Rewind 选中的目标消息 id
    private val _targetRewindMessageId = MutableStateFlow<String?>(null)
    val targetRewindMessageId: StateFlow<String?> = _targetRewindMessageId.asStateFlow()

    // 回退时因「被检查点之外的操作改过」而跳过的文件；非空时界面提示
    private val _rewindConflicts = MutableStateFlow<List<String>>(emptyList())
    val rewindConflicts: StateFlow<List<String>> = _rewindConflicts.asStateFlow()

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
    }

    fun dismissRewindMenu() {
        _targetRewindMessageId.value = null
    }

    fun executeRewindOption(
        messageId: String,
        option: RewindOption,
        onFillPrompt: (String, List<AgentAttachment>) -> Unit
    ) = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        dismissRewindMenu()

        // 1. 停止当前正在运行的 Agent 任务及后续排队
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        val runningJob = sessionJobs[sessionId]
        if (runningJob != null && runningJob.isActive) {
            runningJob.cancelAndJoin()
        }
        sessionJobs.remove(sessionId)

        // 2. 重置会话运行、流式与检查点状态
        setAgentState(sessionId, AgentUIState.Idle)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        checkpointManager.setActiveCheckpointId(sessionId, null)

        val checkpoint = checkpointDao.getCheckpointBySessionAndMessage(sessionId, messageId)
        val targetMsgEntity = messagePersistenceUseCase.restore(agentMessageDao.getMessageById(messageId) ?: return@launch)
        val attachments = targetMsgEntity.toUIMessage().attachments

        var conflicts: List<String> = emptyList()
        when (option) {
            RewindOption.RESTORE_CODE_AND_CONVERSATION -> {
                if (checkpoint != null) {
                    conflicts = checkpointManager.restoreCodeToCheckpoint(sessionId, checkpoint.id).conflicts
                }
                messagePersistenceUseCase.deleteMessagesFromTimestamp(sessionId, targetMsgEntity.timestamp)
                withContext(Dispatchers.Main) { onFillPrompt(targetMsgEntity.content, attachments) }
            }
            RewindOption.RESTORE_CONVERSATION -> {
                messagePersistenceUseCase.deleteMessagesFromTimestamp(sessionId, targetMsgEntity.timestamp)
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
     * 错误横幅的一键重试：取当前会话最后一条用户输入，连同其后所有消息（半截回答、工具结果、
     * 错误行）一并删掉，再用它原本的文本与附件重跑一轮——不回填输入框。
     *
     * 注意删除从用户消息自身开始：重跑时 runner 会重新落一条同样的用户消息，只删「其后」会多出气泡。
     */
    fun retryLastFailedRequest() = viewModelScope.launch {
        val sessionId = _currentSessionId.value ?: return@launch
        val userMessage = lastUserInputMessage(sessionId) ?: return@launch
        cancelRunningTurn(sessionId)
        messagePersistenceUseCase.deleteMessagesFromTimestamp(sessionId, userMessage.timestamp)
        agentMessageDao.repairSingletonVariantGroups(sessionId)
        setAgentState(sessionId, AgentUIState.Idle)
        executeAgentRequestStream(
            request = userMessage.content,
            projectRoot = workspaceOf(sessionId),
            inputAttachments = decodeAttachments(userMessage.attachmentsJson),
            targetSessionId = sessionId
        )
    }

    /**
     * 「重新生成」：旧回答不删，而是连同本轮的工具过程一起标记为变体组的一版（退出上下文回放），
     * 删掉本轮的用户消息后原样重跑，把新产出标成下一版；气泡下方据此左右切换。
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
            inputAttachments = decodeAttachments(userMessage.attachmentsJson),
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

    /** 切换变体组展示的版本（[position] 为组内排序后的下标）：被选中的参与上下文回放，其余退出。 */
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

    /** 删掉变体组的一个版本（只剩一个版本时不动，UI 也不给入口）；选不中已删的下标时回退到前一个。 */
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

    private fun decodeAttachments(raw: String?): List<AgentAttachment> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<AgentAttachment>>(raw) }.getOrDefault(emptyList())
    }

    /** 中断当前轮并清掉运行态（重试 / 重新生成前调用），与回退流程同款收尾。 */
    private suspend fun cancelRunningTurn(sessionId: String) {
        _queuedRequests.value = _queuedRequests.value + (sessionId to emptyList())
        agentNotificationCenter.clear(sessionId)
        sessionJobs[sessionId]?.takeIf { it.isActive }?.cancelAndJoin()
        sessionJobs.remove(sessionId)
        _runningTools.value = _runningTools.value - sessionId
        setStreamingText(sessionId, null)
        setStreamingReasoning(sessionId, null)
        setCompacting(sessionId, false)
        setRetryState(sessionId, null)
        setKeySwitchState(sessionId, null)
    }

    /**
     * 错误文本落库：写进末条助手行；本轮还没产出助手行（如首次调用就报错）时补插一条 content 为空的
     * 助手行专门承载它——它不渲染气泡、也不参与上下文回放，只用于重载后恢复错误横幅与重试入口。
     */
    private suspend fun persistFailureMessage(sessionId: String, error: String) {
        if (error.isBlank()) return
        runCatching {
            val last = agentMessageDao.getMessagesBySessionOnce(sessionId).lastOrNull()
            if (last != null && last.role == MessageRole.ASSISTANT.name) {
                agentMessageDao.updateMessageError(last.id, error)
            } else {
                agentMessageDao.insert(
                    AgentMessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = MessageRole.ASSISTANT.name,
                        content = "",
                        timestamp = messagePersistenceUseCase.nextTimestamp(),
                        error = error
                    )
                )
            }
        }.onFailure { FileLogger.e(TAG, "持久化失败信息出错", it) }
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

}
