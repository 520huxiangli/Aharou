package com.aharou.feature.agent.presentation.component

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「Minis Computer」（工具详情面板）打开信号。
 *
 * 点聊天里的工具行 → [request]；聊天宿主（AIChatPanel）监听后掀开面板。
 * 值 = 最近一次请求的工具消息 id（null = 无请求）。
 */
internal object ToolComputerSignal {
    private val _target = MutableStateFlow<String?>(null)
    val target: StateFlow<String?> = _target.asStateFlow()

    fun request(messageId: String) {
        _target.value = messageId
    }

    fun consume() {
        _target.value = null
    }
}
