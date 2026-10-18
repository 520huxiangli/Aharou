package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.WeixinConfig
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.model.PendingPairing
import com.aharou.feature.im.domain.pairing.ImPairingStore
import com.aharou.feature.im.domain.WeixinIlinkClient
import com.aharou.feature.im.service.WeixinBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 微信机器人设置页的一次性快照。 */
data class WeixinBotUiState(
    val config: WeixinConfig = WeixinConfig(),
    val connection: ImChannelState = ImChannelState.Stopped,
    val pending: List<PendingPairing> = emptyList(),
    val paired: List<PairedChat> = emptyList()
)

/** 扫码绑定的界面状态。 */
sealed interface QrScanState {
    data object Idle : QrScanState
    data object Loading : QrScanState

    /** display 是二维码图片地址或可直接复制的内容。 */
    data class Showing(val display: String) : QrScanState
    data object Scanned : QrScanState
    data object Expired : QrScanState
    data class Done(val userId: String) : QrScanState
    data class Failed(val message: String) : QrScanState
}

/**
 * 「设置 → 微信机器人」页的状态。
 *
 * 与 [TelegramBotViewModel] 同构：配置读写走 [WeixinSettingsRepository]，
 * 连接状态来自共享的 [ImBotBridge]（`@Named("weixin")` 那个实例），
 * 配对沿用同一份 [ImPairingStore]，但按 `weixin` 隔离：四个渠道各看各的名单。
 */
@HiltViewModel
class WeixinBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: WeixinSettingsRepository,
    private val pairing: ImPairingStore,
    private val client: WeixinIlinkClient,
    @param:Named("weixin") private val bridge: ImBotBridge
) : ViewModel() {

    private val _qr = MutableStateFlow<QrScanState>(QrScanState.Idle)
    val qr: StateFlow<QrScanState> = _qr.asStateFlow()
    private var qrJob: Job? = null

    private val pending = MutableStateFlow<List<PendingPairing>>(emptyList())

    val uiState: StateFlow<WeixinBotUiState> = combine(
        settings.configFlow,
        bridge.state,
        bridge.pairedChats,
        pending
    ) { cfg, state, paired, pendingList ->
        WeixinBotUiState(config = cfg, connection = state, pending = pendingList, paired = paired)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WeixinBotUiState()
    )

    init {
        viewModelScope.launch { refreshPaired() }
    }

    fun setBotToken(token: String) {
        viewModelScope.launch { settings.setBotToken(token) }
    }

    fun setIlinkUserId(id: String) {
        viewModelScope.launch { settings.setIlinkUserId(id) }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { settings.setBaseUrl(url) }
    }

    fun setProxy(proxy: String) {
        viewModelScope.launch { settings.setProxy(proxy) }
    }

    /** 开关即启停：开则拉起前台服务（内部开始长轮询），关则停掉。 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) WeixinBotService.start(context) else WeixinBotService.stop(context)
        }
    }

    fun approve(code: String) {
        viewModelScope.launch {
            pairing.approve(bridge.id, code)
            refreshPaired()
        }
    }

    fun deny(code: String) {
        viewModelScope.launch {
            pairing.deny(bridge.id, code)
            refreshPaired()
        }
    }

    fun removePaired(senderId: String) {
        viewModelScope.launch {
            pairing.remove(bridge.id, senderId)
            refreshPaired()
        }
    }

    fun refreshPaired() {
        viewModelScope.launch {
            pending.value = runCatching { pairing.pending(bridge.id) }.getOrDefault(emptyList())
            bridge.refreshPaired()
        }
    }

    /**
     * 开始扫码绑定：拿码 → 每 2 秒查一次状态 → 成功后把 token / ilinkUserId 写进配置。
     *
     * 二维码有效期有限，这里最多跟 3 分钟；用户可随时取消。
     */
    fun startQrLogin() {
        if (qrJob?.isActive == true) return
        qrJob = viewModelScope.launch {
            _qr.value = QrScanState.Loading
            val cfg = settings.current()
            var ticket = runCatching { client.requestQrCode(cfg) }.getOrNull()
            if (ticket == null) {
                _qr.value = QrScanState.Failed("拿二维码失败，检查网络或代理")
                return@launch
            }
            var refreshes = 0
            _qr.value = QrScanState.Showing(ticket!!.display)
            val deadline = System.currentTimeMillis() + QR_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                delay(QR_POLL_MS)
                // ticket 是 var，编译器不会 smart cast，每次循环先取当前值
                val current = ticket ?: run {
                    _qr.value = QrScanState.Expired
                    return@launch
                }
                when (val state = runCatching { client.pollQrStatus(cfg, current.qrcode) }.getOrNull()) {
                    null -> continue
                    WeixinIlinkClient.QrLoginState.Waiting -> Unit
                    WeixinIlinkClient.QrLoginState.Scanned -> _qr.value = QrScanState.Scanned
                    WeixinIlinkClient.QrLoginState.Expired -> {
                        // 官方实现最多自动换 3 张码；换不出来或超次数就交给用户手动重扫
                        if (refreshes >= MAX_QR_REFRESH) {
                            _qr.value = QrScanState.Expired
                            return@launch
                        }
                        refreshes++
                        val fresh = runCatching { client.requestQrCode(cfg) }.getOrNull()
                        if (fresh == null) {
                            _qr.value = QrScanState.Expired
                            return@launch
                        }
                        ticket = fresh
                        _qr.value = QrScanState.Showing(fresh.display)
                    }
                    is WeixinIlinkClient.QrLoginState.Done -> {
                        settings.setBotToken(state.botToken)
                        settings.setIlinkUserId(state.ilinkUserId)
                        state.baseUrl?.takeIf { it.isNotBlank() }?.let { settings.setBaseUrl(it) }
                        _qr.value = QrScanState.Done(state.ilinkUserId)
                        return@launch
                    }
                    is WeixinIlinkClient.QrLoginState.Unknown -> Unit
                }
            }
            _qr.value = QrScanState.Expired
        }
    }

    /** 取消扫码（关闭弹层时调）。 */
    fun cancelQrLogin() {
        qrJob?.cancel()
        qrJob = null
        _qr.value = QrScanState.Idle
    }

    private companion object {
        const val QR_POLL_MS = 2_000L
        const val QR_TIMEOUT_MS = 3 * 60 * 1000L

        /** 二维码过期后自动换码的次数上限（与官方实现一致：最多展示 3 张）。 */
        const val MAX_QR_REFRESH = 3
    }
}
