package com.aharou.feature.agent.presentation.notification

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.terminal.domain.TabInfo
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import com.aharou.feature.voice.call.VoiceCallService
import com.aharou.feature.voice.call.VoiceCallSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 把三路运行态（语音通话 / Agent 任务 / 后台终端）汇成一条灵动岛通知。
 *
 * 挂载点是 App 进程启动即拉起（见 AIEditorApp.onCreate），不挂在 [com.aharou.feature.pet.PetOverlayService]
 * 上——小染开关关着时也必须能用，故做成独立的 [Singleton]。
 *
 * 合并优先级（高到低）：通话中 > Agent 跑任务中 > 后台终端运行中 > 刚结束的收尾提示。
 * 同优先级内多路只取一路代表（终端多开时取第一个标签，正文附总数）；收尾提示只显示 [DONE_WINDOW_MS]
 * 后自动收起，期间若有更高优先级状态出现则被顶掉。
 */
@Singleton
internal class LiveUpdateCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notifier: LiveUpdateNotifier,
    private val callSession: VoiceCallSession,
    private val terminalSessionProvider: TerminalSessionProvider,
) {

    /** 所有 collector 都跑在主线程，共享状态读写天然串行，无需加锁。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var callRunning = false
    private var callState = VoiceCallSession.State.Idle
    private var callDisplayText = ""
    private var agentState = AgentRuntimeStatus.State()
    private var runningTabs: List<TabInfo> = emptyList()

    /** 上一帧的 Agent 运行开关与后台标签集合，用于识别「刚结束」这一刻。 */
    private var prevAgentActive = false
    private var prevRunningIds: Set<String> = emptySet()

    /** 标签 id → 展示名；后台标签消失后仍要靠它拼收尾文案。 */
    private val knownCommands = mutableMapOf<String, String>()

    /** 标签 id → 退出码；只有 notify=true 的后台命令才会经由 tabFinishedEvents 带出。 */
    private val exitCodes = mutableMapOf<String, Int>()

    private var done: DoneEvent? = null
    private var doneJob: Job? = null

    private data class DoneEvent(val title: String, val text: String, val tabId: String? = null)

    fun start() {
        scope.launch { observeCall() }
        scope.launch { observeAgent() }
        scope.launch { observeTerminal() }
        scope.launch { observeFinished() }
    }

    private suspend fun observeCall() {
        combine(
            VoiceCallService.running,
            callSession.state,
            callSession.displayText,
        ) { running, state, display -> Triple(running, state, display) }
            .collect { (running, state, display) ->
                callRunning = running
                callState = state
                callDisplayText = display
                render()
            }
    }

    private suspend fun observeAgent() {
        AgentRuntimeStatus.state.collect { state ->
            val wasActive = prevAgentActive
            prevAgentActive = state.active
            agentState = state
            if (wasActive && !state.active) {
                showDone(
                    context.getString(R.string.agent_complete_notification_title),
                    context.getString(R.string.live_update_agent_done_text),
                )
            }
            render()
        }
    }

    private suspend fun observeTerminal() {
        terminalSessionProvider.runningBackgroundTabs.collect { tabs ->
            runningTabs = tabs
            val ids = tabs.mapTo(mutableSetOf()) { it.id }
            tabs.forEach { tab ->
                knownCommands[tab.id] = tab.command?.takeIf { it.isNotBlank() } ?: tab.title
            }
            (prevRunningIds - ids).lastOrNull()?.let { endedId ->
                showTerminalDone(endedId, knownCommands[endedId] ?: endedId)
            }
            prevRunningIds = ids
            render()
        }
    }

    /** notify=true 的后台命令会走事件带出真实退出码，用来把收尾文案升级一下。 */
    private suspend fun observeFinished() {
        terminalSessionProvider.tabFinishedEvents.collect { event ->
            exitCodes[event.tabId] = event.exitCode
            val current = done
            if (current != null && current.tabId == event.tabId) {
                val name = knownCommands[event.tabId] ?: event.command ?: event.tabId
                done = current.copy(
                    text = context.getString(
                        R.string.live_update_terminal_done_text_exit,
                        name,
                        event.exitCode,
                    )
                )
                render()
            }
        }
    }

    private fun showTerminalDone(tabId: String, name: String) {
        val exitCode = exitCodes[tabId]
        val text = if (exitCode != null) {
            context.getString(R.string.live_update_terminal_done_text_exit, name, exitCode)
        } else {
            context.getString(R.string.live_update_terminal_done_text, name)
        }
        showDone(context.getString(R.string.live_update_terminal_done_title), text, tabId)
    }

    private fun showDone(title: String, text: String, tabId: String? = null) {
        done = DoneEvent(title, text, tabId)
        doneJob?.cancel()
        doneJob = scope.launch {
            delay(DONE_WINDOW_MS)
            done = null
            render()
        }
    }

    private fun render() {
        val content = decide()
        if (content == null) notifier.cancel() else notifier.update(content)
    }

    private fun decide(): LiveUpdateContent? {
        if (callRunning) {
            val text = callDisplayText.ifBlank { context.getString(callStateLabel(callState)) }
            return LiveUpdateContent(
                title = context.getString(R.string.voice_call_notification_title),
                text = text,
                ongoing = true,
            )
        }
        if (agentState.active) {
            val detail = agentState.toolName.ifBlank { agentState.statusText }
            return LiveUpdateContent(
                title = context.getString(R.string.live_update_agent_running_title),
                text = if (detail.isNotBlank()) {
                    context.getString(R.string.live_update_agent_running_text, detail)
                } else {
                    context.getString(R.string.live_update_agent_running_generic)
                },
                ongoing = true,
            )
        }
        runningTabs.firstOrNull()?.let { tab ->
            val name = tab.command?.takeIf { it.isNotBlank() } ?: tab.title
            return LiveUpdateContent(
                title = context.getString(R.string.live_update_terminal_running_title),
                text = if (runningTabs.size > 1) {
                    context.getString(R.string.live_update_terminal_text_multi, runningTabs.size, name)
                } else {
                    context.getString(R.string.live_update_terminal_text, name)
                },
                ongoing = true,
            )
        }
        done?.let { return LiveUpdateContent(it.title, it.text, ongoing = false) }
        return null
    }

    private fun callStateLabel(state: VoiceCallSession.State): Int = when (state) {
        VoiceCallSession.State.Listening -> R.string.voice_call_state_listening
        VoiceCallSession.State.WakeListening -> R.string.voice_wake_on
        VoiceCallSession.State.Thinking -> R.string.voice_call_state_thinking
        VoiceCallSession.State.Speaking -> R.string.voice_call_state_speaking
        VoiceCallSession.State.Preparing -> R.string.voice_call_state_preparing
        VoiceCallSession.State.Idle -> R.string.voice_call_state_idle
    }

    private companion object {
        /** 收尾提示的展示时长：够用户瞥一眼「已就绪」，又不至于一直占着胶囊。 */
        const val DONE_WINDOW_MS = 8_000L
    }
}
