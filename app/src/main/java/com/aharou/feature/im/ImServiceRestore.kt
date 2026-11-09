package com.aharou.feature.im

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.data.repository.DingTalkSettingsRepository
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeComSettingsRepository
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
import com.aharou.feature.im.service.DingTalkBotService
import com.aharou.feature.im.service.FeishuBotService
import com.aharou.feature.im.service.QqBotService
import com.aharou.feature.im.service.TelegramBotService
import com.aharou.feature.im.service.WeComBotService
import com.aharou.feature.im.service.WeixinBotService
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 冷启动时按各自开关把 IM 前台服务拉起来。
 *
 * 各平台的服务都是「开关打开才长轮询/长连接」，进程被杀后不会自己复活；
 * 之前没有任何恢复入口，表现就是「每次进 App 都得先关开关再打开」。
 */
@Singleton
class ImServiceRestore @Inject constructor(
    private val telegram: TelegramSettingsRepository,
    private val weixin: WeixinSettingsRepository,
    private val feishu: FeishuSettingsRepository,
    private val qq: QqSettingsRepository,
    private val dingtalk: DingTalkSettingsRepository,
    private val wecom: WeComSettingsRepository,
    private val napcat: com.aharou.feature.im.data.repository.NapCatSettingsRepository,
    private val napcatRuntime: com.aharou.feature.im.domain.napcat.NapCatRuntimeManager,
) {

    fun restore(context: Context, scope: CoroutineScope) {
        scope.launch {
            runCatching {
                if (telegram.current().enabled) TelegramBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复 Telegram 机器人失败", it) }
            runCatching {
                if (weixin.current().enabled) WeixinBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复微信机器人失败", it) }
            runCatching {
                if (feishu.current().enabled) FeishuBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复飞书机器人失败", it) }
            runCatching {
                if (qq.current().enabled) QqBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复 QQ 机器人失败", it) }
            runCatching {
                if (dingtalk.current().enabled) DingTalkBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复钉钉机器人失败", it) }
            runCatching {
                if (wecom.current().enabled) WeComBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复企业微信机器人失败", it) }
            runCatching {
                if (napcat.current().enabled) com.aharou.feature.im.service.NapCatBotService.start(context)
            }.onFailure { FileLogger.w(TAG, "恢复个人 QQ 失败", it) }
        }
    }

    fun restoreNapCatIfEnabled(context: Context, scope: CoroutineScope) {
        scope.launch {
            try {
                if (napcat.current().enabled && napcatRuntime.state.value.version.isNotBlank() &&
                    !com.aharou.feature.im.service.NapCatBotService.isRunning()) {
                    com.aharou.feature.im.service.NapCatBotService.start(context)
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                FileLogger.w(TAG, "前台恢复个人 QQ 失败", error)
            }
        }
    }

    private companion object {
        const val TAG = "ImServiceRestore"
    }
}
