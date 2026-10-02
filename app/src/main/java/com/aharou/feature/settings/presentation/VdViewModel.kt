package com.aharou.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.agent.domain.shell.HostShellManager
import com.aharou.feature.agent.domain.shell.HostShellMode
import com.aharou.feature.agent.domain.shizuku.ShizukuState
import com.aharou.feature.agent.domain.vdisplay.VdController
import com.aharou.feature.agent.domain.vdisplay.VdInfo
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
    private val hostShell: HostShellManager,
) : ViewModel() {

    val vdState: StateFlow<VdInfo?> = vdController.state

    /** 实际执行的通道：root / Shizuku / 不可用。 */
    val hostShellMode: StateFlow<HostShellMode> = hostShell.mode

    /** Shizuku 侧的细分状态，通道不可用时给出具体原因。 */
    val shizukuState: StateFlow<ShizukuState> = hostShell.shizukuState

    /** root 探测结论；null = 尚未探测。 */
    val rootAvailable: StateFlow<Boolean?> = hostShell.rootAvailable

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { vdController.refresh() }
            runCatching { hostShell.refresh() }
        }
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
