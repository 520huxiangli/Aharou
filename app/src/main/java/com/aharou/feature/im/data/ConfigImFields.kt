package com.aharou.feature.im.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * IM 接入设置 → 配置通道。
 *
 * 目前只有 Telegram 机器人：启停、Bot Token、Bot API 基址、可选代理。全部存在
 * [TelegramSettingsRepository] 的 DataStore（`telegram_im_prefs`）里，故直接走
 * [DataStoreBoolField] / [DataStoreStrField]，不另立读镜像。
 *
 * 长轮询游标 `telegram_last_update_id` 是运行时状态（重启接续用），不在这里暴露。
 */
@Singleton
class ConfigImFields @Inject constructor(
    private val telegram: TelegramSettingsRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(
            DataStoreBoolField(
                path = "im.telegram.enabled",
                displayName = "启用 Telegram 机器人",
                description = "开启后 Aharou 以前台服务长轮询 Telegram，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = telegram.configFlow.map { it.enabled },
                setter = { telegram.setEnabled(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "im.telegram.bot_token",
                displayName = "机器人 Token",
                description = "BotFather 下发的机器人令牌；填了才能连接。留空即清除。",
                flow = telegram.configFlow.map { it.botToken },
                setter = { telegram.setBotToken(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "im.telegram.base_url",
                displayName = "Bot API 基址",
                description = "Telegram Bot API 地址。境内 api.telegram.org 不可直连时改为自建反代/镜像地址；留空则用默认 " +
                    "https://api.telegram.org。",
                flow = telegram.configFlow.map { it.baseUrl },
                setter = { telegram.setBaseUrl(it) },
            ),
        )

        registry.register(
            DataStoreStrField(
                path = "im.telegram.proxy",
                displayName = "代理",
                description = "可选代理，如 http://127.0.0.1:7890 或 socks5://127.0.0.1:1080；留空则跟随全局代理设置。",
                flow = telegram.configFlow.map { it.proxy },
                setter = { telegram.setProxy(it) },
            ),
        )
    }
}
