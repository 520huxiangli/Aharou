package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.TelegramConfig
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.im.service.TelegramBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Named
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Telegram 机器人设置页的一次性快照。 */
data class TelegramBotUiState(
    val config: TelegramConfig = TelegramConfig(),
    val connection: ImChannelState = ImChannelState.Stopped
)

/**
 * 「设置 → Telegram 机器人」页的状态。
 *
 * 配置读写走 [TelegramSettingsRepository]（DataStore），连接状态来自共享单例 [ImBotBridge]。
 *
 * 开关即启停：由本页负责拉起/停掉 [TelegramBotService] 前台服务，注意 Android 8.0+ 需走
 * startForegroundService，服务内部已处理；通知权限（13+）由界面侧申请。
 */
@HiltViewModel
class TelegramBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: TelegramSettingsRepository,
    @param:Named("telegram") private val bridge: ImBotBridge
) : ViewModel() {

    val state: StateFlow<TelegramBotUiState> = combine(
        settings.configFlow,
        bridge.state
    ) { config, connection ->
        TelegramBotUiState(config, connection)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelegramBotUiState())

    init {
        // 配置里已启用、但进程重启后服务未起时补一次启动：否则开关显示「开」而实际没人轮询。
        viewModelScope.launch {
            if (settings.current().enabled && !TelegramBotService.isRunning(context)) {
                TelegramBotService.start(context)
            }
        }
    }

    fun setBotToken(token: String) {
        viewModelScope.launch { settings.setBotToken(token) }
    }

    fun setBaseUrl(baseUrl: String) {
        viewModelScope.launch { settings.setBaseUrl(baseUrl) }
    }

    fun setProxy(proxy: String) {
        viewModelScope.launch { settings.setProxy(proxy) }
    }

    /** 开关即启停：开则拉起前台服务（内部开始长轮询），关则停掉。 */
    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) TelegramBotService.start(context) else TelegramBotService.stop(context)
        }
    }
}
