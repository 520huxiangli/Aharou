package com.aharou.feature.agent.presentation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.widget.Toast
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
import com.aharou.feature.agent.domain.inbox.InboxDirectory
import com.aharou.feature.agent.domain.inbox.SharedInboxDelivery
import com.aharou.feature.agent.domain.inbox.SharedInbox
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationFormatter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.permission.SessionGrantStore
import com.aharou.feature.agent.domain.permission.TaintTracker
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
import com.aharou.feature.agent.domain.schedule.ScheduledRunBus
import com.aharou.feature.agent.domain.schedule.ScheduledRunRequest
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.terminal.domain.TabFinishedEvent
import com.aharou.feature.terminal.domain.TerminalKeepaliveService
import com.aharou.feature.terminal.domain.TerminalSessionManager
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import com.aharou.feature.terminal.domain.renewWakeLockUntilIdle
import com.aharou.feature.terminal.domain.takeTailLines
import com.aharou.feature.workspace.domain.FileAccessProvider
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
import com.aharou.feature.agent.presentation.notification.AgentCompletedNotifier
import com.aharou.feature.agent.presentation.notification.AgentPermissionNotifier
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import javax.inject.Singleton

/**
 * 侧边栏「文件」Tab 的浏览状态：展开集合、目录树、刷新信号、剪贴板与写操作中的路径集合。
 *
 * 从 `AIAgentViewModel` 整块搬出——这一块只依赖文件访问与文件变更监听，和对话核心没有耦合。
 * 工作区切换由 [onWorkspaceChanged] 同步进来（展开状态按工作区持久化）。
 */
@Singleton
class FileBrowseController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val fileChangeHub: FileChangeHub,
    private val fileTreeRoots: ContainerTreeRootProvider,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前工作区路径（容器视角）；由 ViewModel 在切换工作区时同步。 */
    @Volatile
    private var currentWorkspace: String = ""

    /** 切换工作区：恢复该工作区上次持久化的展开状态（无记录则只展开根）。 */
    fun onWorkspaceChanged(path: String) {
        currentWorkspace = path
        _expandedPaths.value = loadExpansion(path)
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

    private val browseTreeBuilder = FileBrowseTreeBuilder(fileAccess)

    /**
     * 扁平化的可见文件树（工作区根 + 当前容器根）。listFiles 在本地是阻塞 IO、远程是网络调用，必须跑 IO 调度器。
     * 监听工作区根与所有已展开目录：任一发生变动（不限 AI，终端与其它 App 同样算）或手动刷新都会重建树。
     * 只监听展开中的目录、不递归整棵树，避免大仓库开出大量 inotify 句柄。
     */
    val browseState: StateFlow<FileBrowseState> = combine(
        _expandedPaths,
        fileTreeRoots.containerRoot
    ) { expanded, root -> expanded to root }
        .flatMapLatest { (expanded, root) ->
            val watched = expanded + WorkspacePathMapper.CONTAINER_ROOT
            val triggers = merge(
                watched.map { fileChangeHub.watchWorkspace(it).asDirtySignal() }.merge().debounce(BROWSE_DEBOUNCE_MS),
                // drop(1) 丢掉 StateFlow 重建时的当前值，否则刚展开就会多读一次
                _browseRefresh.drop(1).map { }
            )
            flow {
                // 首次产出前由 stateIn 初值 Loading 占位；后续展开/折叠/刷新不再回到 Loading，
                // StateFlow 保留上一份 Success 直到新树就绪，避免闪加载动画与滚动位置丢失。
                emit(browseTreeBuilder.build(expanded, root))
                triggers.collect { emit(browseTreeBuilder.build(expanded, root)) }
            }.flowOn(Dispatchers.IO)
        }
        // 新树已就绪：清掉展开等待态（无论成功/出错都清，避免转圈卡死）。
        .onEach { _expandingPath.value = null }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), FileBrowseState.Loading)

    fun refreshBrowse() {
        _browseRefresh.value++
    }

    /** 展开/折叠目录；折叠时连同其所有后代一并移出展开集，避免残留监听与再展开时意外深开。改变后按工作区持久化。
     *  展开需等列目录返回，期间标记 [expandingPath] 让 UI 显示等待动画，并忽略对同一目录的重复点击（否则会把它折回去）。 */
    fun toggleExpand(path: String) {
        if (path == _expandingPath.value) return
        val current = _expandedPaths.value
        val updated = if (path in current) {
            current.filterNot { it == path || it.startsWith(childPathPrefix(path)) }.toSet()
        } else {
            _expandingPath.value = path
            current + path
        }
        _expandedPaths.value = updated
        saveExpansion(currentWorkspace, updated)
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
    ) = scope.launch {
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
        if (isValidFileEntryName(name)) childPathPrefix(parent) + name.trim() else null

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
            val parent = parentPathOf(path)
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
    fun pasteBrowseEntry(targetDir: String, onResult: (Boolean) -> Unit) = scope.launch {
        val clip = _browseClipboard.value ?: return@launch onResult(false)
        if (clip.sourcePath == targetDir) return@launch onResult(false)
        val name = clip.sourceName
        if (!isValidFileEntryName(name)) return@launch onResult(false)
        val target = childPathPrefix(targetDir) + name
        if (target == clip.sourcePath || target.startsWith(childPathPrefix(clip.sourcePath.trimEnd('/')))) {
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



    private companion object {
        const val TAG = "FileBrowseController"
        const val BROWSE_DEBOUNCE_MS = 300L
    }
}
