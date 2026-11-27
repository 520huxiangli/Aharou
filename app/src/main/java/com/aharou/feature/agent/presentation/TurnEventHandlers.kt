package com.aharou.feature.agent.presentation

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.presentation.notification.AgentCompletedNotifier
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import com.aharou.feature.settings.data.repository.AgentSoundSettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ViewModelScoped
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 一轮事件流里「副作用较重」的那几支：工具卡片、失败落库、完成通知与记忆沉淀。
 *
 * 单独成类的直接目的是把 [AIAgentViewModel.executeAgentRequestStream] 那个超大协程状态机切小——
 * ART 对指令量过大的挂起函数会放弃 JIT、只能解释执行，在取消展开路径上会 native 崩溃
 * （见记忆 `agent_loop_jit_abort_split`）。纯展示类的轻分支仍留在 ViewModel。
 */
@ViewModelScoped
class TurnEventHandlers @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val turnRuntime: TurnRuntimeStateController,
    private val runRegistry: SessionRunRegistry,
    private val agentCompletedNotifier: AgentCompletedNotifier,
    private val agentSoundSettings: AgentSoundSettingsRepository,
    private val agentWorkflow: AgentWorkflow,
    private val turnEndBridge: AgentTurnEndBridge,
    private val agentMessageDao: AgentMessageDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) {

    private companion object {
        const val NOTIFICATION_BODY_MAX = 60
        const val MEMORY_CURATE_INTERVAL_MS = 10 * 60 * 1000L
        const val TOOL_TRACE_MAX_CHARS = 6_000
        const val TOOL_TRACE_ENTRY_CHARS = 800
    }

    /** 工具轨迹缓冲（按会话），供完成时喂给记忆整理器。 */
    private val toolTraceBySession = java.util.concurrent.ConcurrentHashMap<String, StringBuilder>()

    /** 记忆兑现节流：同一会话两次自动整理的最小间隔。 */
    private val lastCurateAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 一轮的事件作用域：`failed` 与思考计时都是「这一轮内」的状态，随轮次生灭。
     */
    class TurnScope(
        val sessionId: String,
        val currentJob: Job,
        val modelRequest: String,
        val projectRoot: String,
        /** 整轮结束后要续跑时用（turnEndBridge 的 send 回调）。 */
        val onContinue: (String) -> Unit,
    ) {
        var failed = false
        var reasoningStart: Long? = null
        var reasoningEnd: Long? = null
    }

    /** 思考计时收尾：拿到结束时刻就写一次时长，重复调用无副作用。 */
    fun finishReasoning(scope: TurnScope) {
        val start = scope.reasoningStart ?: return
        if (scope.reasoningEnd == null) {
            val end = System.currentTimeMillis()
            scope.reasoningEnd = end
            turnRuntime.setReasoningTiming(scope.sessionId, start to end)
        }
    }

    fun handleToolCallStarted(scope: TurnScope, event: AgentEvent.ToolCallStarted, clearPreparing: () -> Unit) {
        val msgId = "tool_${event.id}"
        turnRuntime.setStreamingText(scope.sessionId, null)
        clearPreparing()
        runRegistry.toolArgsByMsgId[msgId] = event.argsPreview
        turnRuntime.setRunningTool(scope.sessionId, msgId, RunningToolOutput(msgId, "", event.toolName, event.argsPreview))
    }

    fun handleToolCallProgress(scope: TurnScope, event: AgentEvent.ToolCallProgress) {
        val msgId = "tool_${event.id}"
        turnRuntime.setRunningTool(
            scope.sessionId,
            msgId,
            RunningToolOutput(msgId, event.accumulated, event.toolName, runRegistry.toolArgsByMsgId[msgId] ?: "")
        )
    }

    fun handleToolCallFinished(scope: TurnScope, event: AgentEvent.ToolCallFinished) {
        val msgId = "tool_${event.id}"
        appendToolTrace(scope.sessionId, event.toolName, event.result)
        runRegistry.toolArgsByMsgId.remove(msgId)
        turnRuntime.removeRunningTool(scope.sessionId, msgId)
    }

    /** 失败：置错误态并把错误文本落到末条助手消息上（重开 App / 切会话后横幅与重试仍在）。 */
    suspend fun handleFailed(scope: TurnScope, event: AgentEvent.Failed) {
        scope.failed = true
        turnRuntime.setCompacting(scope.sessionId, false)
        val failureMessage = describeFailure(event)
        setAgentState(scope.sessionId, AgentUIState.Error(failureMessage))
        persistFailure(scope.sessionId, failureMessage)
    }

    /**
     * 整轮完成：清提示、按需发完成通知、兜底做一次记忆沉淀，并把收尾动作交给 turnEndBridge。
     */
    suspend fun handleCompleted(scope: TurnScope, appScope: CoroutineScope) {
        val sessionId = scope.sessionId
        turnRuntime.setRetryState(sessionId, null)
        turnRuntime.setKeySwitchState(sessionId, null)
        turnRuntime.setCompacting(sessionId, false)
        // 仅当 App 不在前台时发 agent 完成通知（避免打扰正在看对话的用户）。
        val inForeground = androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.currentState
            .isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        if (!inForeground && agentSoundSettings.isEnabled()) {
            agentCompletedNotifier.notify(
                sessionId,
                agentCompleteNotificationBody(turnRuntime.streamingTextOf(sessionId).orEmpty())
            )
        }
        // 引擎级记忆兜底：主模型当轮没调 memory 工具时，后台用轻量模型抽取沉淀。
        // 静默失败、不进对话流；同一会话 10 分钟内不重复跑。
        val now = System.currentTimeMillis()
        if (now - (lastCurateAt[sessionId] ?: 0L) >= MEMORY_CURATE_INTERVAL_MS) {
            appScope.launch {
                // 本轮对话文本：用户请求 + 工具轨迹 + 流式回答快照。
                // 工具轨迹不能少：一轮活里最值得沉淀的结论大多在工具输出里。
                val toolTrace = toolTraceBySession[sessionId]?.toString()?.takeIf { it.isNotBlank() }
                val transcript = buildString {
                    append("用户: ").appendLine(scope.modelRequest)
                    toolTrace?.let { appendLine(it) }
                    turnRuntime.streamingTextOf(sessionId)?.take(4000)?.let { append("助手: ").appendLine(it) }
                }.trim()
                if (transcript.isNotBlank()) {
                    agentWorkflow.curateMemory(sessionId, scope.projectRoot, transcript)
                    // 调用后才清轨迹并记账：失败/取消时不占用 10 分钟窗口，下一轮带着轨迹重试。
                    toolTraceBySession.remove(sessionId)
                    lastCurateAt[sessionId] = System.currentTimeMillis()
                }
            }
        }
        turnEndBridge.onTurnFinished(appScope, sessionId) { next ->
            scope.onContinue(next)
        }
    }

    /**
     * 失败文案：服务端给了类型码（如拒答/上下文超限）时用本地化说明，
     * 并附上服务端的具体理由（如果有）；无类型码时直接展示原错误文本。
     */
    fun describeFailure(event: AgentEvent.Failed): String {
        val localized = when (event.reasonCode) {
            // Gemini 的 finishReason 全大写，这几种与 Anthropic 的 refusal 同义（内容策略拦截）。
            "refusal", "content_filter", "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" -> context.getString(R.string.agent_stop_refusal)
            "model_context_window_exceeded" -> context.getString(R.string.agent_stop_context_exceeded)
            "input_budget_exceeded" -> context.getString(R.string.agent_input_budget_exceeded)
            "empty_response" -> context.getString(R.string.agent_stop_empty_response)
            else -> null
        } ?: return event.error
        return if (event.error.isBlank()) localized else "$localized\n${event.error}"
    }

    private fun appendToolTrace(sessionId: String, toolName: String, result: String) {
        val buffer = toolTraceBySession.getOrPut(sessionId) { StringBuilder() }
        synchronized(buffer) {
            if (buffer.length >= TOOL_TRACE_MAX_CHARS) return
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append("工具 ").append(toolName).append(": ")
                .append(result.trim().replace('\n', ' ').take(TOOL_TRACE_ENTRY_CHARS))
        }
    }

    private fun agentCompleteNotificationBody(reply: String): String {
        val text = reply.replace('\n', ' ').trim()
        return text.take(NOTIFICATION_BODY_MAX).ifBlank {
            context.getString(R.string.agent_complete_notification_body)
        }
    }

    private fun setAgentState(sessionId: String, state: AgentUIState) {
        runRegistry.agentStates.value = runRegistry.agentStates.value + (sessionId to state)
        val active = runRegistry.agentStates.value.values.any {
            it is AgentUIState.Loading || it is AgentUIState.Streaming
        }
        AgentRuntimeStatus.setActive(active)
    }

    /** 失败文本落到末条助手消息；没有助手行时补一条，避免横幅只活在内存里。 */
    suspend fun persistFailure(sessionId: String, error: String) {
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
        }.onFailure { FileLogger.e("TurnEventHandlers", "持久化失败信息出错", it) }
    }
}
