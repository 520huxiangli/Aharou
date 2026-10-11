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

    init {
        refreshDevices()
    }

    fun refreshDevices() = viewModelScope.launch(Dispatchers.IO) {
        _devices.value = controller.devices()
    }

    fun setEnabled(enabled: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        controller.setEnabled(enabled)
        // 关闭会清空全部授权，列表得跟着刷新，否则关掉开关后旧记录还挂在页面上。
        _devices.value = controller.devices()
    }

    fun setPort(port: Int) = viewModelScope.launch(Dispatchers.IO) { controller.setPort(port) }

    fun setDeviceName(name: String) = viewModelScope.launch(Dispatchers.IO) { controller.setDeviceName(name) }

    fun setAlwaysAskRemote(value: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        controller.setAlwaysAskRemote(value)
    }

    fun revoke(deviceId: String) = viewModelScope.launch(Dispatchers.IO) {
        controller.revoke(deviceId)
        _devices.value = controller.devices()
    }

    /** 生成（或复用有效期内）一枚配对码，并取回到期时刻供弹层倒计时。 */
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
