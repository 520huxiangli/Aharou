package com.aharou.feature.terminal.domain

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 保活前台服务的实际运行状态。
 *
 * `startForeground` 失败以前只落日志：开关开着、通知却没出现，用户没有任何地方能看出
 * 「到底有没有生效」。这里把结果摊开给设置页读。
 *
 * 内存态：进程重启后由服务重建时重新写入，不持久化——跨进程的旧状态比没有更误导。
 */
@Singleton
class KeepaliveStatus @Inject constructor() {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 最近一次进入前台失败的原因；成功进入后清空。 */
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun markRunning() {
        _running.value = true
        _lastError.value = null
    }

    fun markStopped() {
        _running.value = false
    }

    fun markFailure(message: String) {
        _running.value = false
        _lastError.value = message
    }
}
