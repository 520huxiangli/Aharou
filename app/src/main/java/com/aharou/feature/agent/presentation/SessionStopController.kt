package com.aharou.feature.agent.presentation

import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.domain.notification.AgentNotificationCenter
import com.aharou.feature.agent.domain.notification.AgentNotificationKind
import com.aharou.feature.agent.domain.notification.NotificationOutcome
import com.aharou.feature.agent.domain.notification.PendingNotification
import com.aharou.feature.agent.domain.permission.SessionGrantStore
import com.aharou.feature.agent.domain.permission.TaintTracker
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentRunLifecycle
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 停止会话的编排：软打断（投一条 USER_INTERRUPT，命令留着跑完）与强打断（取消协程 + 把挂起的工具
 * 行补成「已停止」）。
 *
 * 从 [AIAgentViewModel] 搬出来是因为那个文件顶在行数基线上。运行态（job / 停止占位 / 界面状态）
 * 由 [SessionRunRegistry] 共享，语义与原来完全一致。
 *
 * 两条硬约束（都出过事故）：
 * - 强打断**不能**因为 [SessionRunRegistry.stopping] 已有占位就退出，否则点过一次停止的用户再也强停不了；
 *   叠加时先把旧占位 complete 放行，再挂新的，直接替换引用会让正 await 旧 deferred 的协程永远醒不来。
 * - 收尾里的 join 都不用无限等：job 卡在阻塞读上时，无限等待会让停止占位永不清、后续消息全挂死。
 */
@ViewModelScoped
class SessionStopController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val runRegistry: SessionRunRegistry,
    private val turnRuntime: TurnRuntimeStateController,
    private val sessionQueue: SessionQueueController,
    private val agentNotificationCenter: AgentNotificationCenter,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val agentMessageDao: AgentMessageDao,
    private val toolPermissionManager: ToolPermissionManager,
    private val subAgentEventBus: SubAgentEventBus,
    private val subAgentRunLifecycle: SubAgentRunLifecycle,
) {

    private companion object {
        const val TAG = "SessionStopController"
    }

    /**
     * 软打断：只投一条 USER_INTERRUPT，agent 在当前这步做完后不再继续（不调下一个工具、不起新轮）。
     *
     * **不取消 job**：正在跑的命令与网络请求留在后台跑完，这是用户要的语义「任务放着继续做，
     * 你先停下来听我说」。界面立刻放开，不等收尾。
     *
     * @param onSettled 本轮真正结束后调用（投递积压通知、消费排队消息由调用方决定）
     */
    fun stopSession(sessionId: String, scope: CoroutineScope, onSettled: (String) -> Unit) {
        if (sessionId in runRegistry.stopping) return
        val job = runRegistry.jobs[sessionId] ?: return
        if (!job.isActive) return
        releaseSubAgent(sessionId, scope)
        agentNotificationCenter.enqueue(
            sessionId,
            PendingNotification(
                kind = AgentNotificationKind.USER_INTERRUPT,
                sourceId = "user",
                title = context.getString(R.string.agent_stopped_by_user),
                outcome = NotificationOutcome.STOPPED
            )
        )
        FileLogger.d(TAG, "stopSession: 投递用户打断 sid=$sessionId state=${runRegistry.agentStates.value[sessionId]}")
        // 停止占位只用于「停止期间不再起新轮」；清理等 job 真正结束时做，不挡界面。
        val stopped = CompletableDeferred<Unit>()
        runRegistry.stopping[sessionId] = stopped
        scope.launch {
            job.join()
            if (runRegistry.stopping[sessionId] === stopped) runRegistry.stopping.remove(sessionId)
            stopped.complete(Unit)
            if (runRegistry.jobs[sessionId]?.let { it !== job } != true) {
                onSettled(sessionId)
            }
        }
        // 界面立刻放开：输入框马上能用，不用等后台那步收尾。
        turnRuntime.setCompacting(sessionId, false)
        turnRuntime.setRetryState(sessionId, null)
        setAgentState(sessionId, AgentUIState.Idle)
    }

    /**
     * 强打断：取消协程，容器引擎的取消钩子会 destroy 容器内进程，挂起的网络请求同时断开。
     *
     * 不投 USER_INTERRUPT——协程一取消就没人消费它，残留到下一轮会把新一轮也打断。
     */
    fun forceStopSession(sessionId: String, scope: CoroutineScope, onSettled: (String) -> Unit) {
        val job = runRegistry.jobs[sessionId]
        releaseSubAgent(sessionId, scope)
        // 取消前先取快照：协程被取消后运行中工具与待授权请求会随之清空，那时就取不到了。
        val runningTools = turnRuntime.runningToolsOf(sessionId)
        val pendingPermissions = buildList {
            toolPermissionManager.pendingForSession(sessionId)?.let { add(it) }
            toolPermissionManager.pendingBatchForSession(sessionId)?.items?.let { addAll(it) }
        }
        val stoppedText = context.getString(R.string.agent_stopped_by_user)
        FileLogger.d(
            TAG,
            "forceStopSession: sid=$sessionId active=${job?.isActive == true} runningTools=${runningTools.size} pendingPerm=${pendingPermissions.map { it.id }}"
        )
        // 软打断留下的占位先放行：可能有协程正挂在它上面等（排队的新消息），只替换引用会让那些
        // 等待者永远醒不过来。放行后它们会重新读 map，挂到下面这份新占位上。
        runRegistry.stopping.remove(sessionId)?.complete(Unit)
        val stopped = CompletableDeferred<Unit>()
        runRegistry.stopping[sessionId] = stopped
        job?.cancel()
        scope.launch {
            job?.join()
            if (runRegistry.stopping[sessionId] === stopped) runRegistry.stopping.remove(sessionId)
            stopped.complete(Unit)
            if (runRegistry.jobs[sessionId]?.let { it !== job } == true) return@launch
            settleStoppedTools(sessionId, runningTools, pendingPermissions, stoppedText)
            turnRuntime.setStreamingText(sessionId, null)
            turnRuntime.setStreamingReasoning(sessionId, null)
            turnRuntime.setCompacting(sessionId, false)
            turnRuntime.setRetryState(sessionId, null)
            setAgentState(sessionId, AgentUIState.Idle)
            onSettled(sessionId)
        }
        // 界面立刻放开：与软打断一致，不等后台那份收尾。
        turnRuntime.setCompacting(sessionId, false)
        turnRuntime.setRetryState(sessionId, null)
        setAgentState(sessionId, AgentUIState.Idle)
    }

    /**
     * 停止所有正在运行的会话（仅由容器切换 / 重置流程调用）。
     *
     * @param onReleased 最后释放保活等宿主资源，必须在状态清空之后
     */
    fun stopAll(scope: CoroutineScope, onReleased: () -> Unit) {
        val jobs = runRegistry.jobs.values.filter { it.isActive }
        // 先清队列再 cancel：cancel 会触发每个 job 的 invokeOnCompletion，那里会补送队列里的通知；
        // 后清就会在「已停止」之后再冒出一轮 AI。
        agentNotificationCenter.clearAll()
        sessionQueue.clearAll()
        val stoppedRuns = subAgentEventBus.activeSubSessionIds.value.mapNotNull { subAgentEventBus.currentRun(it) }
        stoppedRuns.forEach { runRegistry.suppressedSubAgentStops.add(it.runId) }
        scope.launch {
            try {
                stoppedRuns.forEach { run ->
                    if (subAgentRunLifecycle.stopRun(run.sessionId, run.parentSessionId, broadcast = false, expectedRun = run)) {
                        runRegistry.suppressedSubAgentStops.remove(run.runId)
                    }
                }
            } finally {
                jobs.forEach { it.cancel() }
            }
        }
        runRegistry.jobs.clear()
        runRegistry.activeSessions.value = emptySet()
        runRegistry.runningCommandSessions.value = emptySet()
        runRegistry.agentStates.value = runRegistry.agentStates.value.mapValues { AgentUIState.Idle }
        syncRuntimeActive()
        turnRuntime.clearLiveTurnStates()
        onReleased()
    }

    /**
     * 强制打断后的收尾：把还挂在「执行中」的工具占位行落库为「已停止」，否则会留下悬挂的 spinner。
     *
     * 授权弹窗挂起中的调用同样要补：awaitApproval 期间拿不到运行中工具（ToolCallStarted 在授权通过
     * 后才发出），但 assistant 消息里已经落了带 tool_call 的声明。不补结果，这条 tool_call 就成了孤儿
     * 记录，被 buildHistory 的 validIds 交集滤掉，AI 不知道自己曾调用过。
     */
    private suspend fun settleStoppedTools(
        sessionId: String,
        runningTools: List<RunningToolOutput>,
        pendingPermissions: List<PendingToolPermission>,
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
                runRegistry.toolArgsByMsgId.remove(running.messageId)
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
                toolArgs = running.toolArgs.ifBlank { runRegistry.toolArgsByMsgId[running.messageId] },
                isError = true
            )
            runRegistry.toolArgsByMsgId.remove(running.messageId)
        }
        pendingPermissions.forEach { pending ->
            if (!needsStoppedResult("tool_${pending.id}")) return@forEach
            val msgId = "tool_${pending.id}"
            messagePersistenceUseCase.persist(
                sessionId = sessionId,
                role = MessageRole.TOOL,
                content = stoppedText,
                id = msgId,
                toolCallId = pending.id,
                toolName = pending.toolName,
                isError = true
            )
        }
    }

    /** 会话被删除时清掉与它绑定的内存态：污点标记、会话级授权。 */
    fun clearSessionRuntimeState(sessionId: String) {
        TaintTracker.clear(sessionId)
        SessionGrantStore.clearSession(sessionId)
    }

    /** 停止运行中的子代理：交回并发槽位并告知父代理，否则槽位泄漏到进程重启。 */
    fun releaseSubAgent(sessionId: String, scope: CoroutineScope) {
        val run = subAgentEventBus.currentRun(sessionId) ?: return
        scope.launch {
            subAgentRunLifecycle.stopRun(sessionId, run.parentSessionId, expectedRun = run)
        }
    }

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        runRegistry.agentStates.value = runRegistry.agentStates.value + (sessionId to state)
        syncRuntimeActive()
    }

    /** 同步全局运行状态（悬浮窗等系统级 UI 读取；进程内零权限）。 */
    private fun syncRuntimeActive() {
        val active = runRegistry.agentStates.value.values.any {
            it is AgentUIState.Loading || it is AgentUIState.Streaming
        }
        AgentRuntimeStatus.setActive(active)
    }
}
