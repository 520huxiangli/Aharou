package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.QqConfig
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.service.QqBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** QQ 机器人设置页的一次性快照。 */
data class QqBotUiState(
    val config: QqConfig = QqConfig(),
    val connection: ImChannelState = ImChannelState.Stopped
)

/** 「设置 → QQ 机器人」页的状态，与 [WeixinBotViewModel] 同构。 */
@HiltViewModel
class QqBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: QqSettingsRepository,
    @param:Named("qq") private val bridge: ImBotBridge
) : ViewModel() {

    val uiState: StateFlow<QqBotUiState> = combine(
        settings.configFlow,
        bridge.state
    ) { cfg, state ->
        QqBotUiState(config = cfg, connection = state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = QqBotUiState()
    )

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
}
