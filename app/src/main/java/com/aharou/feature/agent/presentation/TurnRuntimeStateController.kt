package com.aharou.feature.agent.presentation

import androidx.compose.runtime.mutableStateMapOf
import com.aharou.feature.agent.domain.runtime.AgentRuntimeStatus
import com.aharou.feature.agent.domain.workflow.AgentEvent
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 一轮执行过程中按会话维护的实时展示状态：运行中的工具、流式正文与思考、重试/换 Key/压缩提示、
 * 上下文用量。
 *
 * 从 [AIAgentViewModel] 整组搬出来（那个文件顶在行数基线上）。这些状态彼此同生共死——一轮开始时
 * 被写入、收尾时一起清掉，拆散到多个类反而要维护「谁先清」的隐式约定，所以留在一个类里。
 *
 * 业务语义（什么时候开始/结束一轮、什么时候该清）仍留在 ViewModel，这里只负责存取。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ViewModelScoped
class TurnRuntimeStateController @Inject constructor() {

    // ── 运行中的工具 ──

    private val _runningTools = MutableStateFlow<Map<String, Map<String, RunningToolOutput>>>(emptyMap())

    /** 当前会话正在执行的工具（按 msgId 索引后取列表）。 */
    fun runningTool(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<List<RunningToolOutput>> =
        sessionIds
            .flatMapLatest { id ->
                if (id == null) flowOf(emptyList())
                else _runningTools.map { it[id]?.values?.toList() ?: emptyList() }
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** 指定会话正在执行的工具快照（停止前取，取消后这里就空了）。 */
    fun runningToolsOf(sessionId: String): List<RunningToolOutput> =
        _runningTools.value[sessionId]?.values?.toList() ?: emptyList()

    /** 添加/更新一个运行中工具（按 msgId 定位，支持多个工具并行）。 */
    fun setRunningTool(sessionId: String, msgId: String, tool: RunningToolOutput) {
        val sessionTools = _runningTools.value[sessionId] ?: emptyMap()
        _runningTools.value = _runningTools.value + (sessionId to (sessionTools + (msgId to tool)))
        syncRuntimeStatus()
    }

    /** 移除一个运行中工具；会话无剩余运行工具时清除该会话条目。 */
    fun removeRunningTool(sessionId: String, msgId: String) {
        val sessionTools = _runningTools.value[sessionId] ?: return
        val updated = sessionTools - msgId
        _runningTools.value = if (updated.isEmpty()) {
            _runningTools.value - sessionId
        } else {
            _runningTools.value + (sessionId to updated)
        }
        syncRuntimeStatus()
    }

    fun clearRunningTools(sessionId: String) {
        _runningTools.value = _runningTools.value - sessionId
        syncRuntimeStatus()
    }

    /** 同步全局运行状态（悬浮窗等系统级 UI 读取；进程内零权限）。 */
    private fun syncRuntimeStatus() {
        val all = _runningTools.value.values.flatMap { it.values }
        val last = all.lastOrNull()
        AgentRuntimeStatus.set(
            busy = all.isNotEmpty(),
            toolName = last?.toolName.orEmpty(),
            statusText = last?.text.orEmpty(),
        )
    }

    // ── 压缩与上下文用量 ──

    private val _compactingSessions = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    private val compactionUsage = CompactionUsageTracker()
    private val _contextUsages = MutableStateFlow<Map<String, AgentEvent.ContextUsage>>(emptyMap())

    val contextUsages: StateFlow<Map<String, AgentEvent.ContextUsage>> = _contextUsages.asStateFlow()

    fun isCompacting(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<Boolean> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(false)
            else _compactingSessions.map { it[id] == true }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    fun setCompacting(sessionId: String, compacting: Boolean) {
        _compactingSessions.value = if (compacting) {
            _compactingSessions.value + (sessionId to true)
        } else {
            _compactingSessions.value - sessionId
        }
    }

    /** 记一次用量上报：它同时清掉该会话的压缩失败标记（新上报比上一轮失败更新）。 */
    fun reportContextUsage(sessionId: String, usage: AgentEvent.ContextUsage) {
        _contextUsages.value = compactionUsage.report(sessionId, usage, _contextUsages.value)
    }

    /** 压缩结束：成功则丢掉该会话的估算，失败则原样保留。 */
    fun afterCompaction(sessionId: String) {
        _contextUsages.value = compactionUsage.afterCompaction(sessionId, _contextUsages.value)
    }

    fun markCompactionFailed(sessionId: String) {
        compactionUsage.markFailed(sessionId)
    }

    fun clearContextUsage(sessionId: String) {
        _contextUsages.value = _contextUsages.value - sessionId
    }

    // ── 流式正文与思考 ──

    private val _streamingTexts = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val _reasoningTimings = MutableStateFlow<Map<String, Pair<Long, Long?>>>(emptyMap())
    private val _streamingReasonings = MutableStateFlow<Map<String, String?>>(emptyMap())

    fun streamingText(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<String?> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingTexts.map { it[id] }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun streamingTextOf(sessionId: String): String? = _streamingTexts.value[sessionId]

    fun setStreamingText(sessionId: String, text: String?) {
        _streamingTexts.value = if (text == null) _streamingTexts.value - sessionId else _streamingTexts.value + (sessionId to text)
    }

    fun reasoningTiming(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<Pair<Long, Long?>?> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else _reasoningTimings.map { it[id] }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setReasoningTiming(sessionId: String, timing: Pair<Long, Long?>) {
        _reasoningTimings.value = _reasoningTimings.value + (sessionId to timing)
    }

    fun streamingReasoning(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<String?> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _streamingReasonings.map { it[id] }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setStreamingReasoning(sessionId: String, text: String?) {
        if (text == null) {
            _reasoningTimings.value = _reasoningTimings.value - sessionId
            // 这一轮的流式思考结束了（落库/重试/中止）：临时展开态没有意义，别留给下一轮。
            // 落库那条路径先调 migrateStreamingReasoningExpansion 再调这里，迁移不会被提前抹掉。
            reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId))
        }
        _streamingReasonings.value = if (text == null) _streamingReasonings.value - sessionId else _streamingReasonings.value + (sessionId to text)
    }

    // ── 重试与换 Key 提示 ──

    private val _retryStates = MutableStateFlow<Map<String, RetryState?>>(emptyMap())
    private val _keySwitchStates = MutableStateFlow<Map<String, KeySwitchState?>>(emptyMap())

    fun retryState(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<RetryState?> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _retryStates.map { it[id] }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setRetryState(sessionId: String, state: RetryState?) {
        _retryStates.value = if (state == null) _retryStates.value - sessionId else _retryStates.value + (sessionId to state)
    }

    fun keySwitchState(sessionIds: Flow<String?>, scope: CoroutineScope): StateFlow<KeySwitchState?> = sessionIds
        .flatMapLatest { id ->
            if (id == null) flowOf(null)
            else _keySwitchStates.map { it[id] }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    fun setKeySwitchState(sessionId: String, state: KeySwitchState?) {
        _keySwitchStates.value = if (state == null) _keySwitchStates.value - sessionId else _keySwitchStates.value + (sessionId to state)
    }

    // ── 思考块展开态 ──

    /**
     * 工具调用与思考块的手动展开态：key 为消息 id 或 `reasoning:streaming:<sessionId>`。
     *
     * 放这里而不是组合里：窄窗下打开设置 / 终端 / 编辑器都是全屏路由，聊天页整棵组合被 dispose，
     * remember 的 map 会一起丢。按进程内存保留、不落盘。
     */
    val reasoningExpansionOverrides = mutableStateMapOf<String, Boolean>()

    fun streamingReasoningKey(sessionId: String?): String = "reasoning:streaming:${sessionId.orEmpty()}"

    /**
     * 流式思考落库时把临时展开态迁到新消息 id 上；用户在思考过程中展开了，落库后不该又收回去。
     */
    fun migrateStreamingReasoningExpansion(sessionId: String, messageId: String) {
        if (messageId.isBlank()) return
        val expanded = reasoningExpansionOverrides.remove(streamingReasoningKey(sessionId)) ?: return
        reasoningExpansionOverrides[messageId] = expanded
    }

    // ── 整轮收尾 ──

    /** 一轮结束后清掉「还挂在这轮上」的实时态：正文、思考、运行中工具、重试提示。 */
    fun clearLiveTurnStates() {
        _streamingTexts.value = emptyMap()
        _streamingReasonings.value = emptyMap()
        _runningTools.value = emptyMap()
        _retryStates.value = emptyMap()
    }
}
