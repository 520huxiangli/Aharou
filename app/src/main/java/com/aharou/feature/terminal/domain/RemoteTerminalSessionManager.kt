package com.aharou.feature.terminal.domain

import android.content.Context
import android.content.Intent
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteTerminalSessionManager"
private const val TRANSCRIPT_ROWS = 2000
private const val DEFAULT_COLUMNS = 80
private const val DEFAULT_ROWS = 24

/** 命令打印 `[command exited: N]` 后等待正常 onFinished 回调的缓冲（毫秒）。 */
private const val EXIT_MARKER_GRACE_MS = 1_500L
/** 完成兜底监控轮询屏幕缓冲的间隔（毫秒）。 */
private const val EXIT_MARKER_POLL_MS = 1_000L
/** 匹配命令退出标记 `[command exited: N]` 的定位前缀。 */
private const val EXIT_MARKER_PREFIX = "[command exited: "

/**
 * 远程 SSH 终端会话管理器：用 sshj shell channel 驱动 [TerminalSession]（接 [SshShellBackend]），
 * 与本地 [TerminalSessionManager]（fork PTY 进程）共用同一套 UI/工具接口。
 *
 * 生命周期、tab 管理、事件流与本地版对齐；区别仅在 backend。
 */
@Singleton
class RemoteTerminalSessionManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val connection: RemoteSshConnection,
    private val modeHolder: ExecutionModeHolder,
    private val workspaceRepository: WorkspaceRepository
) : TerminalSessionProvider {

    private val _tabs = MutableStateFlow<List<TerminalTab>>(emptyList())
    val tabs: StateFlow<List<TerminalTab>> = _tabs.asStateFlow()

    private val _activeTabId = MutableStateFlow<String?>(null)
    val activeTabId: StateFlow<String?> = _activeTabId.asStateFlow()

    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    private val _tabFinishedEvents = MutableSharedFlow<TabFinishedEvent>(extraBufferCapacity = 16)
    override val tabFinishedEvents: SharedFlow<TabFinishedEvent> = _tabFinishedEvents.asSharedFlow()

    private val idCounter = AtomicInteger(0)

    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val activeTab: TerminalTab? get() = _tabs.value.firstOrNull { it.id == _activeTabId.value }

    fun tab(id: String): TerminalTab? = _tabs.value.firstOrNull { it.id == id }

    /** 仅当当前模式是 REMOTE_SSH 且已连接时才可使用。 */
    private fun ensureRemote(): Boolean =
        modeHolder.currentMode() == ExecutionMode.REMOTE_SSH && connection.isConnected()

    /** 终端页进入时调用：没有任何标签则建一个交互 shell。幂等。 */
    suspend fun ensureInitialTab() {
        if (_tabs.value.isEmpty()) {
            createInteractiveTab()
        } else if (_activeTabId.value == null) {
            _activeTabId.value = _tabs.value.first().id
        }
    }

    /** 新建一个交互 shell 标签并设为当前。返回新标签 id。 */
    suspend fun createInteractiveTab(): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        return openShellTab(command = null, isBackground = false, notify = false, title = null, sourceSessionId = null).also { id ->
            _activeTabId.value = id
            FileLogger.i(TAG, "新建交互远程终端标签 $id")
        }
    }

    override suspend fun startBackgroundCommand(
        command: String,
        title: String?,
        notify: Boolean,
        sourceSessionId: String?,
        workspacePath: String?
    ): String {
        if (!ensureRemote()) throw IllegalStateException("非远程模式或 SSH 未连接")
        val id = openShellTab(
            command,
            isBackground = true,
            notify = notify,
            title = title,
            sourceSessionId = sourceSessionId,
            workspacePath = workspacePath
        )
        FileLogger.i(TAG, "后台命令标签 $id: $command")
        return id
    }

    /**
     * 开一个 SSH shell channel，分配 PTY，构造 [TerminalSession]（接 [SshShellBackend]），
     * 加入标签列表。交互标签与后台命令共用此路径，区别仅在元数据。
     */
    private suspend fun openShellTab(
        command: String?,
        isBackground: Boolean,
        notify: Boolean,
        title: String?,
        sourceSessionId: String?,
        workspacePath: String? = null
    ): String {
        val id = nextId()
        // sshj startSession/startShell 走网络 I/O，必须离开主线程，否则 NetworkOnMainThreadException。
        // 但 TerminalSession 构造时会 new Handler()（绑当前线程 Looper），必须在有 Looper 的线程（主线程）构造，
        // 所以只把 sshj channel 建立切到 IO，拿到 shell 句柄后回主线程构造 session。
        val shell = withContext(Dispatchers.IO) {
            connection.startShellSession().also { it.allocateDefaultPTY() }.startShell()
        }
        val backend = SshShellBackend(shell)
        val termSession = TerminalSession(TRANSCRIPT_ROWS, AppRemoteSessionClient(), backend)
        termSession.updateSize(DEFAULT_COLUMNS, DEFAULT_ROWS)
        // shell 登录后默认在 home，先 cd 到当前工作区，与命令执行链路（RemoteSshEngine.buildCdCommand）保持一致：
        // 优先 ~/workspace 符号链接，失败回退到真实工作区路径。
        val wsPath = workspacePath?.takeIf { it.isNotBlank() } ?: workspaceRepository.currentPath()
        if (wsPath.isNotBlank() && wsPath != "/") {
            termSession.write("cd ~/workspace 2>/dev/null || cd ${shellQuote(wsPath.trimEnd('/'))} 2>/dev/null\n")
        }
        if (command != null) {
            // sshj 的 Session.Shell 没有退出状态（SshShellBackend.waitForExit 恒 0），真实退出码只能靠命令
            // 回显的 `[command exited: N]` 标记解析；notify 时 `exit $ec` 让 shell 自然结束触发 onFinished，
            // 非 notify 时 `exec /bin/sh` 保活供后续复用（与本地 TerminalSessionManager 的三件套一致）。
            val afterCommand = if (notify) "; exit \$ec" else "; exec /bin/sh"
            val init = "$command; ec=\$?; echo \"[command exited: \$ec]\"$afterCommand"
            termSession.write(init + "\n")
        }
        val tab = TerminalTab(
            id = id,
            title = title ?: id,
            session = termSession,
            isBackground = isBackground,
            command = command,
            startedAt = System.currentTimeMillis(),
            notifyOnExit = notify,
            sourceSessionId = sourceSessionId,
            workspacePath = wsPath,
            runState = RunState.Running
        )
        addTab(tab)
        if (_activeTabId.value == null) _activeTabId.value = id
        if (isBackground) startKeepaliveService()
        if (command != null && notify) monitorBackgroundExit(id)
        return id
    }

    override fun sendInput(id: String, input: String, appendNewline: Boolean): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val text = if (appendNewline && !input.endsWith("\n")) input + "\n" else input
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeToTab(id: String, text: String): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        tab.session.write(bytes, 0, bytes.size)
        return true
    }

    override fun writeBytesToTab(id: String, vararg bytes: Int): Boolean {
        val tab = tab(id) ?: return false
        if (tab.runState !is RunState.Running) return false
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
        return true
    }

    override fun getTabOutput(id: String): String? {
        val tab = tab(id) ?: return null
        return runCatching {
            tab.session.emulator?.screen?.transcriptText?.trimEnd('\n')
        }.getOrNull() ?: ""
    }

    override fun listTabs(): List<TabInfo> = _tabs.value.map { it.toTabInfo() }

    override val runningBackgroundTabs: Flow<List<TabInfo>> =
        combine(_tabs, revision) { tabs, _ -> tabs.runningBackgroundTabInfos() }.distinctUntilChanged()

    override fun closeTab(id: String): Boolean {
        val tab = tab(id) ?: return false
        runCatching { tab.session.finishIfRunning() }
        tab.view = null
        val remaining = _tabs.value.filterNot { it.id == id }
        _tabs.value = remaining
        if (_activeTabId.value == id) {
            _activeTabId.value = remaining.lastOrNull()?.id
        }
        bumpRevision()
        if (tab.isBackground) maybeStopKeepalive(tab)
        FileLogger.i(TAG, "关闭远程终端标签 $id")
        return true
    }

    fun activate(id: String) {
        if (_tabs.value.any { it.id == id }) _activeTabId.value = id
    }

    fun rename(id: String, title: String) {
        tab(id)?.let {
            it.title = title
            bumpRevision()
        }
    }

    /** 向当前活动标签写入文本（额外按键行：方向键/Tab 等）。 */
    fun writeToActive(text: String) {
        activeTab?.let { tab ->
            if (tab.runState !is RunState.Running) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            tab.session.write(bytes, 0, bytes.size)
        }
    }

    /** 向当前活动标签写入原始字节（控制字符，如 Ctrl-C=0x03）。 */
    fun writeBytesToActive(vararg bytes: Int) {
        val tab = activeTab ?: return
        if (tab.runState !is RunState.Running) return
        val arr = ByteArray(bytes.size) { bytes[it].toByte() }
        tab.session.write(arr, 0, arr.size)
    }

    private fun nextId(): String = "term-${idCounter.incrementAndGet()}"

    private fun addTab(tab: TerminalTab) {
        _tabs.value = _tabs.value + tab
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value = _revision.value + 1
    }

    /** 在输出末尾定位 `[command exited: N]` 标记并解析退出码；要求标记独立成行，避免命令回显误判。 */
    private fun extractExitCode(output: String): Int? {
        val tail = output.takeLast(1000)
        val idx = tail.lastIndexOf(EXIT_MARKER_PREFIX)
        if (idx < 0) return null
        if (idx > 0 && tail[idx - 1] != '\n' && tail[idx - 1] != '\r') return null
        var end = idx + EXIT_MARKER_PREFIX.length
        if (end >= tail.length || !tail[end].isDigit()) return null
        var code = 0
        while (end < tail.length && tail[end].isDigit()) {
            code = code * 10 + (tail[end] - '0')
            end++
        }
        return if (end < tail.length && tail[end] == ']') code else null
    }

    /**
     * 命令结束的统一收尾：写 RunState、按需停保活、发完成事件（finishedNotified 保证只发一次）。
     * 已非 Running 说明另一条收尾路径已处理，直接返回，避免重复。
     */
    private fun finalizeTab(tabId: String, exitCode: Int) {
        val current = tab(tabId) ?: return
        if (current.runState !is RunState.Running) return
        current.runState = RunState.Finished(exitCode)
        bumpRevision()
        FileLogger.i(TAG, "远程标签 $tabId 结束 exit=$exitCode")
        if (current.isBackground) maybeStopKeepalive(current)
        if (current.notifyOnExit && !current.finishedNotified) {
            current.finishedNotified = true
            _tabFinishedEvents.tryEmit(
                TabFinishedEvent(
                    current.id, current.title, current.command, exitCode, current.sourceSessionId,
                    startedAt = current.startedAt,
                    tailOutput = getTabOutput(current.id)?.takeTailLines(TAIL_LINES)
                )
            )
        }
    }

    /**
     * shell 流 EOF（onFinished）后收尾：sshj 退出码恒 0，真码在命令回显的 `[command exited: N]` 里。
     * onFinished 与输出 reader 线程存在竞态、标记可能尚未刷进屏幕缓冲，故短暂轮询；仍取不到才用
     * backend 报的退出码兜底（交互标签/异常结束没有标记）。
     */
    private fun resolveExitCodeThenFinalize(tabId: String, fallbackExitCode: Int) {
        monitorScope.launch {
            var waited = 0L
            while (waited <= EXIT_MARKER_GRACE_MS) {
                val code = extractExitCode(getTabOutput(tabId) ?: "")
                if (code != null) {
                    finalizeTab(tabId, code)
                    return@launch
                }
                delay(50)
                waited += 50
            }
            finalizeTab(tabId, fallbackExitCode)
        }
    }

    /**
     * 完成兜底监控：notify 命令以 `exit $ec` 结束本应触发 onFinished，但网络/回调异常时可能不触发。
     * 轮询屏幕缓冲，一旦出现退出标记即收尾；输出无增长时跳过扫描，避免长命令期间持续占 CPU。
     */
    private fun monitorBackgroundExit(tabId: String) {
        monitorScope.launch {
            var lastOutputLen = -1
            while (true) {
                val tab = tab(tabId) ?: return@launch
                if (tab.runState !is RunState.Running) return@launch
                val output = getTabOutput(tabId) ?: return@launch
                if (output.length != lastOutputLen) {
                    lastOutputLen = output.length
                    val code = extractExitCode(output)
                    if (code != null) {
                        finalizeTab(tabId, code)
                        return@launch
                    }
                }
                delay(EXIT_MARKER_POLL_MS)
            }
        }
    }

    /** 单引号包裹并转义内部单引号，与命令执行链路（RemoteSshEngine.shellQuote）一致。 */
    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun startKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_START_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已启动")
    }

    private fun stopKeepaliveService() {
        val intent = Intent(appContext, TerminalKeepaliveService::class.java).apply {
            action = TerminalKeepaliveService.ACTION_STOP_SESSION
        }
        appContext.startService(intent)
        FileLogger.i(TAG, "后台保活 Service 已停止")
    }

    private fun maybeStopKeepalive(closingTab: TerminalTab) {
        if (closingTab.isBackground && _tabs.value.none { it.isBackground && it.runState is RunState.Running }) {
            stopKeepaliveService()
        }
    }

    /** 远程模式的 [TerminalSessionClient] 实现，回调与本地一致。 */
    private inner class AppRemoteSessionClient : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === changedSession }?.view?.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {
            _tabs.value.firstOrNull { it.session === finishedSession }?.let { target ->
                // sshj shell 无退出状态，真实退出码来自命令回显标记；标记尚未刷进缓冲时短暂轮询再收尾。
                resolveExitCodeThenFinalize(target.id, finishedSession.exitStatus)
            }
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) { FileLogger.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { FileLogger.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { FileLogger.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { FileLogger.d(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { FileLogger.e(tag ?: TAG, message ?: "", e) }
        override fun logStackTrace(tag: String?, e: Exception?) { FileLogger.e(tag ?: TAG, "", e) }
    }
}
