package com.aicode.feature.agent.domain.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局 Agent 运行状态（跨进程内各模块共享；供悬浮窗等系统级 UI 读取）。
 *
 * 由 [com.aicode.feature.agent.presentation.AIAgentViewModel] 在运行工具增删时同步；
 * 悬浮窗服务按「不在前台 && busy」的门控显示拖拽胶囊。
 */
object AgentRuntimeStatus {

    data class State(
        val busy: Boolean = false,
        /** 最近一个运行中工具名（如 Bash / browser / a11y）。 */
        val toolName: String = "",
        /** 状态短文本（工具输出尾部 / 执行中提示）。 */
        val statusText: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun set(busy: Boolean, toolName: String = "", statusText: String = "") {
        _state.value = State(busy = busy, toolName = toolName, statusText = statusText)
    }

    fun clear() {
        _state.value = State()
    }
}
