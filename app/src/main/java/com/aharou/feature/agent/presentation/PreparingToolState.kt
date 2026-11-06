package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.workflow.ToolArgsProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * 「模型正在准备哪个工具、参数写到多少了」的会话级状态。
 *
 * 从 [AIAgentViewModel] 里抽出来，是因为那个文件已顶到单文件行数棘轮的上限；这两个值本来
 * 就同生共死（工具开始执行、本轮结束都会一起清掉），放在一处比散在 ViewModel 里更不容易漏清。
 *
 * [currentSessionId] 只是「看哪个会话」，所以 UI 侧取到的永远是当前会话的工具名与进度。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PreparingToolState(
    currentSessionId: StateFlow<String?>,
    scope: CoroutineScope
) {
    private val names = MutableStateFlow<Map<String, String>>(emptyMap())
    private val progress = MutableStateFlow<Map<String, ToolArgsProgress>>(emptyMap())

    /** 当前会话正在准备（参数还在流）的工具名；null 表示没有。 */
    val toolName: StateFlow<String?> = currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else names.map { it[id] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** 当前会话的参数流式进度；null 时 UI 用工具名对应的通用场景文案。 */
    val argsProgress: StateFlow<ToolArgsProgress?> = currentSessionId
        .flatMapLatest { id -> if (id == null) flowOf(null) else progress.map { it[id] } }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * 更新某个会话的准备态。[toolName] 为 null 表示准备结束（工具开始执行或本轮收尾），
     * 此时进度一并清掉。
     *
     * 进度总是按本次事件覆写（包括 null）：换到下一个工具时如果沿用上一笔的进度，
     * UI 会把 B 的工具名配上 A 的「写入 342 行」。
     */
    fun update(sessionId: String, toolName: String?, argsProgress: ToolArgsProgress?) {
        setToolName(sessionId, toolName)
        setProgress(sessionId, argsProgress)
    }

    private fun setToolName(sessionId: String, toolName: String?) {
        val current = names.value
        if (toolName == null) {
            if (current.containsKey(sessionId)) names.value = current - sessionId
        } else if (current[sessionId] != toolName) {
            names.value = current + (sessionId to toolName)
        }
    }

    private fun setProgress(sessionId: String, argsProgress: ToolArgsProgress?) {
        val current = progress.value
        if (argsProgress == null) {
            if (current.containsKey(sessionId)) progress.value = current - sessionId
        } else if (current[sessionId] != argsProgress) {
            progress.value = current + (sessionId to argsProgress)
        }
    }
}
