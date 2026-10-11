package com.aharou.feature.settings.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aharou.feature.im.data.repository.TelegramConfig
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.domain.TelegramBotBridge
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.model.PendingPairing
import com.aharou.feature.im.domain.model.TelegramState
import com.aharou.feature.im.domain.pairing.ImPairingStore
import com.aharou.feature.im.service.TelegramBotService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Telegram 机器人设置页的一次性快照。 */
data class TelegramBotUiState(
    val config: TelegramConfig = TelegramConfig(),
    val connection: TelegramState = TelegramState.Stopped,
    val pending: List<PendingPairing> = emptyList(),
    val paired: List<PairedChat> = emptyList()
)

/**
 * 「设置 → Telegram 机器人」页的状态。
 *
 * 配置读写走 [TelegramSettingsRepository]（DataStore），连接状态与已配对名单来自共享单例
 * [TelegramBotBridge]，待配对列表按需从 [ImPairingStore] 拉取——配对码有 10 分钟过期语义，
 * 不做定时刷新，页面内 approve/deny/remove 后各刷新一次即可。
 *
 * 开关即启停：由本页负责拉起/停掉 [TelegramBotService] 前台服务，注意 Android 8.0+ 需走
 * startForegroundService，服务内部已处理；通知权限（13+）由界面侧申请。
 */
@HiltViewModel
class TelegramBotViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: TelegramSettingsRepository,
    private val pairing: ImPairingStore,
    private val bridge: TelegramBotBridge
) : ViewModel() {

    /** 待配对列表由本页主动拉取（DataStore 与 bridge 都不持有它）。 */
    private val pending = MutableStateFlow<List<PendingPairing>>(emptyList())

    val state: StateFlow<TelegramBotUiState> = combine(
        settings.configFlow,
        bridge.state,
        pending,
        bridge.pairedChats
    ) { config, connection, pendingList, pairedList ->
        TelegramBotUiState(config, connection, pendingList, pairedList)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TelegramBotUiState())

    init {
        refresh()
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

    fun approve(code: String) {
        viewModelScope.launch {
            pairing.approve(code)
            refresh()
        }
    }

    fun deny(code: String) {
        viewModelScope.launch {
            pairing.deny(code)
            refresh()
        }
    }

    fun removePaired(senderId: String) {
        viewModelScope.launch {
            pairing.remove(senderId)
            refresh()
        }
    }

    /** 待配对与已配对一起刷新：bridge 的已配对名单不会自己变，approve/deny/remove 后都要推一次。 */
    fun refresh() {
        viewModelScope.launch {
            pending.value = runCatching { pairing.pending() }.getOrDefault(emptyList())
            bridge.refreshPaired()
        }
    }
}
