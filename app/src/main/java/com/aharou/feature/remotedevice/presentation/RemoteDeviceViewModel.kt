package com.aharou.feature.remotedevice.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.remotedevice.RemoteDeviceController
import com.aharou.feature.remotedevice.RemoteDeviceUiState
import com.aharou.feature.remotedevice.domain.RemoteDeviceConfig
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.server.RemoteServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 配对码弹层的数据：6 位码 + 到期时刻（毫秒）。 */
data class RemotePairingUi(val code: String, val expiresAt: Long)

/** 接受协助的界面状态。 */
sealed interface AssistUiState {
    data object Idle : AssistUiState
    data object Working : AssistUiState

    /** 结果提示；[ok] 决定用正色还是警示色。 */
    data class Done(val message: String, val ok: Boolean) : AssistUiState
}

/**
 * 「跨设备协作」设置页的 ViewModel：把 [RemoteDeviceController] 的状态与动作转成 UI 可直接消费的流。
 *
 * 控制器是 @Singleton，这里只做一层薄封装，不复制任何业务逻辑——开关、端口、授权列表的真源都在控制器里。
 */
@HiltViewModel
class RemoteDeviceViewModel @Inject constructor(
    private val controller: RemoteDeviceController
) : ViewModel() {

    /** 配置 + 服务状态 + 本机端点。首帧给一份全关的默认快照，避免 UI 空窗。 */
    val state: StateFlow<RemoteDeviceUiState> = controller.state.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RemoteDeviceUiState(
            config = RemoteDeviceConfig(),
            server = RemoteServerState.Stopped,
            endpoints = emptyList(),
            capabilities = emptyList()
        )
    )

    private val _devices = MutableStateFlow<List<RemotePairedDevice>>(emptyList())
    val devices: StateFlow<List<RemotePairedDevice>> = _devices.asStateFlow()

    private val _pairing = MutableStateFlow<RemotePairingUi?>(null)
    val pairing: StateFlow<RemotePairingUi?> = _pairing.asStateFlow()

    private val _assist = MutableStateFlow<AssistUiState>(AssistUiState.Idle)
    val assist: StateFlow<AssistUiState> = _assist.asStateFlow()

    init {
        refreshDevices()
    }

    fun refreshDevices() = viewModelScope.launch(Dispatchers.IO) {
        _devices.value = controller.devices()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        controller.setEnabled(enabled)
        // 关开关不再清空授权（只停服务），但仍刷新一次列表，让「最后活跃」这类字段跟上。
        _devices.value = controller.devices()
    }

    fun setPort(port: Int) = viewModelScope.launch(Dispatchers.IO) { controller.setPort(port) }

    fun setDeviceName(name: String) = viewModelScope.launch(Dispatchers.IO) { controller.setDeviceName(name) }

    fun setAlwaysAskRemote(value: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        controller.setAlwaysAskRemote(value)
    }

    fun setAllowPublicPeer(value: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        controller.setAllowPublicPeer(value)
    }

    fun acceptAssistance(host: String, port: Int, code: String) = viewModelScope.launch(Dispatchers.IO) {
        _assist.value = AssistUiState.Working
        _assist.value = when (val outcome = controller.acceptAssistance(host, port, code)) {
            is RemoteDeviceController.AssistOutcome.Ok ->
                AssistUiState.Done("配对成功，已登记为 MCP 服务器「${outcome.serverName}」", ok = true)
            is RemoteDeviceController.AssistOutcome.Rejected -> AssistUiState.Done(outcome.reason, ok = false)
            is RemoteDeviceController.AssistOutcome.Unreachable -> AssistUiState.Done(outcome.reason, ok = false)
        }
    }

    fun clearAssist() {
        _assist.value = AssistUiState.Idle
    }

    fun revoke(deviceId: String) = viewModelScope.launch(Dispatchers.IO) {
        controller.revoke(deviceId)
        _devices.value = controller.devices()
    }

    /** 生成（或复用）一枚配对码。码不过期，界面无需倒计时。 */
    fun generatePairingCode() = viewModelScope.launch(Dispatchers.IO) {
        controller.pairingCode()
        controller.livePairingCode()?.let { (code, expiresAt) ->
            _pairing.value = RemotePairingUi(code, expiresAt)
        }
    }

    fun clearPairing() {
        _pairing.value = null
    }
}
