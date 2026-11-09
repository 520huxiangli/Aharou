package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.WeComSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.domain.WeComChannelConfig
import com.aharou.feature.im.service.WeComBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 企业微信机器人设置页的一次性快照。 */
data class WeComBotUiState(
    val config: WeComChannelConfig = WeComChannelConfig(),
    val connection: ImChannelState = ImChannelState.Stopped
)

/** 「设置 → 企业微信机器人」页的状态，与 [QqBotViewModel] 同构。 */
@HiltViewModel
class WeComBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: WeComSettingsRepository,
    @param:Named("wecom") private val bridge: ImBotBridge
) : ViewModel() {

    val uiState: StateFlow<WeComBotUiState> = combine(
        settings.configFlow,
        bridge.state
    ) { cfg, state ->
        WeComBotUiState(config = cfg, connection = state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = WeComBotUiState()
    )

    /** 凭据写入失败（系统密钥库不可用）：界面据此提示「未保存」，否则用户会以为已经存好。 */
    private val _secretWriteFailed = MutableStateFlow(false)
    val secretWriteFailed: StateFlow<Boolean> = _secretWriteFailed.asStateFlow()

    fun setBotId(id: String) {
        viewModelScope.launch { settings.setBotId(id) }
    }

    fun setSecret(secret: String) {
        viewModelScope.launch { _secretWriteFailed.value = !settings.setSecret(secret) }
    }

    fun setProxy(proxy: String) {
        viewModelScope.launch { settings.setProxy(proxy) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) WeComBotService.start(context) else WeComBotService.stop(context)
        }
    }
}
