package com.aharou.feature.settings.data.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 执行模式的同步缓存：App 启动时从 [ExecutionModeRepository] 读首帧模式缓存到内存，
 * 供各委托层（`DelegatingCommandEngine`/`DelegatingFileAccess`/`DelegatingTerminalSessionProvider`）
 * 每次调用时同步读取，决定转发到本地还是远程实现。
 *
 * 模式切换后（设置页）调 [setMode] 更新缓存，切换即时生效，无需重启。
 */
@Singleton
class ExecutionModeHolder @Inject constructor() {
    private val _mode = MutableStateFlow(ExecutionMode.LOCAL_PROOT)
    val mode: StateFlow<ExecutionMode> = _mode.asStateFlow()

    fun setMode(mode: ExecutionMode) {
        _mode.value = mode
    }

    fun currentMode(): ExecutionMode = _mode.value
}
