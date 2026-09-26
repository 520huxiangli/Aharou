package com.aicode.feature.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「围观信号」（Aharou 定制）。
 *
 * Agent 的 browser 工具每次开始执行时 ping 一下，聊天页据此**自动弹出
 * 浏览器面板**——不用手点即可围观 Agent 在访问什么（主人点名要求）。
 * 值 = 最近一次 ping 的时间戳（0 = 从未）。
 */
object BrowserWatchSignal {
    private val _tick = MutableStateFlow(0L)

    val tick: StateFlow<Long> = _tick.asStateFlow()

    fun ping() {
        _tick.value = System.currentTimeMillis()
    }
}
