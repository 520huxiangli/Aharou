package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.DingTalkSettingsRepository
import com.aharou.feature.im.domain.DingTalkChannelConfig
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.service.DingTalkBotService
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

/** 钉钉机器人设置页的一次性快照。 */
data class DingTalkBotUiState(
    val config: DingTalkChannelConfig = DingTalkChannelConfig(),
    val connection: ImChannelState = ImChannelState.Stopped
)

/** 「设置 → 钉钉机器人」页的状态，与 [QqBotViewModel] 同构。 */
@HiltViewModel
class DingTalkBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: DingTalkSettingsRepository,
    @param:Named("dingtalk") private val bridge: ImBotBridge
) : ViewModel() {

    val uiState: StateFlow<DingTalkBotUiState> = combine(
        settings.configFlow,
        bridge.state
    ) { cfg, state ->
        DingTalkBotUiState(config = cfg, connection = state)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DingTalkBotUiState()
    )

    /** 凭据写入失败（系统密钥库不可用）：界面据此提示「未保存」，否则用户会以为已经存好。 */
    private val _secretWriteFailed = MutableStateFlow(false)
    val secretWriteFailed: StateFlow<Boolean> = _secretWriteFailed.asStateFlow()

    fun setClientId(id: String) {
        viewModelScope.launch { settings.setClientId(id) }
    }

    fun setClientSecret(secret: String) {
        viewModelScope.launch { _secretWriteFailed.value = !settings.setClientSecret(secret) }
    }

    fun setRobotCode(code: String) {
        viewModelScope.launch { settings.setRobotCode(code) }
    }

    fun setProxy(proxy: String) {
        viewModelScope.launch { settings.setProxy(proxy) }
    }

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) DingTalkBotService.start(context) else DingTalkBotService.stop(context)
        }
    }
}
