package com.aharou.feature.im.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
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
    private val weixin: WeixinSettingsRepository,
    private val feishu: FeishuSettingsRepository,
    private val qq: QqSettingsRepository,
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

        registerWeixin(registry)
        registerFeishu(registry)
        registerQq(registry)
    }

    /** 微信 iLink：扫码绑定后凭证会自动填入，也可以手填。 */
    private fun registerWeixin(registry: ConfigRegistry) = with(registry) {
        register(
            DataStoreBoolField(
                path = "im.weixin.enabled",
                displayName = "启用微信机器人",
                description = "开启后以前台服务长轮询微信 iLink，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = weixin.configFlow.map { it.enabled },
                setter = { weixin.setEnabled(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.weixin.bot_token",
                displayName = "微信 Bot Token",
                description = "iLink 绑定（或扫码）后拿到的 botToken；填了才能连接。留空即清除。",
                flow = weixin.configFlow.map { it.botToken },
                setter = { weixin.setBotToken(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
        register(
            DataStoreStrField(
                path = "im.weixin.ilink_user_id",
                displayName = "iLink 用户 ID",
                description = "绑定时分配的 ilinkUserId，与 Token 成对使用。留空即清除。",
                flow = weixin.configFlow.map { it.ilinkUserId },
                setter = { weixin.setIlinkUserId(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.weixin.base_url",
                displayName = "iLink 接入地址",
                description = "微信 iLink 的服务地址；留空用官方 https://ilinkai.weixin.qq.com。扫码登录会返回实际地址。",
                flow = weixin.configFlow.map { it.baseUrl },
                setter = { weixin.setBaseUrl(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.weixin.proxy",
                displayName = "微信代理",
                description = "可选代理，如 http://127.0.0.1:7890；留空则跟随全局代理设置。",
                flow = weixin.configFlow.map { it.proxy },
                setter = { weixin.setProxy(it) },
            ),
        )
    }

    /** 飞书：WebSocket 长连接，只需自建应用的 App ID / Secret。 */
    private fun registerFeishu(registry: ConfigRegistry) = with(registry) {
        register(
            DataStoreBoolField(
                path = "im.feishu.enabled",
                displayName = "启用飞书机器人",
                description = "开启后以前台服务维持飞书 WebSocket 长连接，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = feishu.configFlow.map { it.enabled },
                setter = { feishu.setEnabled(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.feishu.app_id",
                displayName = "飞书 App ID",
                description = "开放平台自建应用的 App ID。留空即清除。",
                flow = feishu.configFlow.map { it.appId },
                setter = { feishu.setAppId(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.feishu.app_secret",
                displayName = "飞书 App Secret",
                description = "开放平台自建应用的 App Secret，与 App ID 成对使用。留空即清除。",
                flow = feishu.configFlow.map { it.appSecret },
                setter = { feishu.setAppSecret(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
        register(
            DataStoreStrField(
                path = "im.feishu.base_url",
                displayName = "飞书接入地址",
                description = "留空用官方 https://open.feishu.cn。",
                flow = feishu.configFlow.map { it.baseUrl },
                setter = { feishu.setBaseUrl(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.feishu.proxy",
                displayName = "飞书代理",
                description = "可选代理；留空则跟随全局代理设置。",
                flow = feishu.configFlow.map { it.proxy },
                setter = { feishu.setProxy(it) },
            ),
        )
    }

    /** QQ 开放平台：WebSocket 网关，需要 AppID + AppSecret。 */
    private fun registerQq(registry: ConfigRegistry) = with(registry) {
        register(
            DataStoreBoolField(
                path = "im.qq.enabled",
                displayName = "启用 QQ 机器人",
                description = "开启后以前台服务维持 QQ 机器人 WebSocket 连接，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = qq.configFlow.map { it.enabled },
                setter = { qq.setEnabled(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.qq.app_id",
                displayName = "QQ AppID",
                description = "QQ 开放平台机器人管理端的 AppID。留空即清除。",
                flow = qq.configFlow.map { it.appId },
                setter = { qq.setAppId(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.qq.app_secret",
                displayName = "QQ AppSecret",
                description = "QQ 开放平台机器人管理端的 AppSecret（只显示一次），与 AppID 成对使用。留空即清除。",
                flow = qq.configFlow.map { it.appSecret },
                setter = { qq.setAppSecret(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
        register(
            DataStoreStrField(
                path = "im.qq.base_url",
                displayName = "QQ 接入地址",
                description = "留空用官方 https://api.sgroup.qq.com。",
                flow = qq.configFlow.map { it.baseUrl },
                setter = { qq.setBaseUrl(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.qq.proxy",
                displayName = "QQ 代理",
                description = "可选代理；留空则跟随全局代理设置。",
                flow = qq.configFlow.map { it.proxy },
                setter = { qq.setProxy(it) },
            ),
        )
    }
}
