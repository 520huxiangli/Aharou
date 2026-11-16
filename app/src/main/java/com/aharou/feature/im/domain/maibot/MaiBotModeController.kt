package com.aharou.feature.im.domain.maibot

import com.aharou.core.util.FileLogger
import com.aharou.feature.im.data.repository.MaiBotSettingsRepository
import com.aharou.feature.im.data.repository.MaiBotSettingsRepository.Mode
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.domain.BotModeState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作/日常模式的执行者。
 *
 * 两种模式下 NapCat 进程都要在线：日常模式由 MaiBot 通过它的 OneBot 通道回消息，
 * 工作模式让 Aharou 的桥恢复处理。切换只动「谁回」和 MaiBot 进程，不碰 QQ 登录态。
 */
@Singleton
class MaiBotModeController @Inject constructor(
    private val settings: MaiBotSettingsRepository,
    private val botMode: BotModeState,
    private val maiBot: MaiBotRuntimeManager,
    private val napCatSettings: NapCatSettingsRepository,
) {
    suspend fun current(): Mode = settings.current()

    /** 供设置页观察当前模式。 */
    val mode: kotlinx.coroutines.flow.Flow<Mode> = settings.mode

    suspend fun setMode(mode: Mode) {
        settings.setMode(mode)
        apply(mode)
    }

    /** 进程重启后按已保存的模式恢复一次。 */
    suspend fun restore() {
        apply(settings.current())
    }

    private suspend fun apply(mode: Mode) {
        botMode.handedToMaiBot = mode == Mode.DAILY
        when (mode) {
            Mode.DAILY -> {
                if (!napCatSettings.current().enabled) napCatSettings.setEnabled(true)
                runCatching { maiBot.start() }
            }
            Mode.WORK -> {
                runCatching { maiBot.stop() }
            }
        }
        FileLogger.i(TAG, "机器人模式：${mode.name}")
    }

    private companion object {
        const val TAG = "MaiBotMode"
    }
}
