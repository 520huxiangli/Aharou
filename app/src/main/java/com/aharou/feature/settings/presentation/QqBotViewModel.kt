package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.QqConfig
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.model.PendingPairing
import com.aharou.feature.im.domain.pairing.ImPairingStore
import com.aharou.feature.im.service.QqBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 飞书机器人设置页的一次性快照。 */
data class QqBotUiState(
    val config: QqConfig = QqConfig(),
    val connection: ImChannelState = ImChannelState.Stopped,
    val pending: List<PendingPairing> = emptyList(),
    val paired: List<PairedChat> = emptyList()
)

/** 「设置 → 飞书机器人」页的状态，与 [WeixinBotViewModel] 同构。 */
@HiltViewModel
class QqBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: QqSettingsRepository,
    private val pairing: ImPairingStore,
    @param:Named("qq") private val bridge: ImBotBridge
) : ViewModel() {

    private val pending = MutableStateFlow<List<PendingPairing>>(emptyList())

    val uiState: StateFlow<QqBotUiState> = combine(
        settings.configFlow,
        bridge.state,
        bridge.pairedChats,
        pending
    ) { cfg, state, paired, pendingList ->
        QqBotUiState(config = cfg, connection = state, pending = pendingList, paired = paired)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QqBotUiState()
    )

    init {
        viewModelScope.launch { refreshPaired() }
    }

    fun setAppId(id: String) {
        viewModelScope.launch { settings.setAppId(id) }
    }

    fun setAppSecret(secret: String) {
        viewModelScope.launch { settings.setAppSecret(secret) }
    }

    fun setBaseUrl(url: String) {
        viewModelScope.launch { settings.setBaseUrl(url) }
    }

    fun setProxy(proxy: String) {
        viewModelScope.launch { settings.setProxy(proxy) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) QqBotService.start(context) else QqBotService.stop(context)
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
}
