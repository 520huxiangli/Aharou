package com.aicode.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aicode.feature.agent.domain.shizuku.ShizukuManager
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import com.aicode.feature.agent.domain.vdisplay.VdController
import com.aicode.feature.agent.domain.vdisplay.VdInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 设置页「影子屏」分区的状态与操作入口。 */
@HiltViewModel
class VdViewModel @Inject constructor(
    private val vdController: VdController,
    private val shizukuManager: ShizukuManager,
) : ViewModel() {

    val vdState: StateFlow<VdInfo?> = vdController.state

    val shizukuState: StateFlow<ShizukuState> = shizukuManager.state

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch { runCatching { vdController.refresh() } }
    }

    fun start() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            runCatching { vdController.start() }
                .onFailure { _message.value = it.message ?: "启动失败" }
            _busy.value = false
        }
    }

    fun stop() {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            runCatching { vdController.stop() }
                .onFailure { _message.value = it.message ?: "停止失败" }
            _busy.value = false
        }
    }

    fun consumeMessage() {
        _message.value = null
    }
}
