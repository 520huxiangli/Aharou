package com.aharou.feature.im.data

import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.im.domain.ImBotBridge
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.core.util.SecretMasker
import javax.inject.Named
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.fields.DataStoreBoolField
import com.aharou.core.config.fields.DataStoreStrField
import com.aharou.feature.im.data.repository.DingTalkSettingsRepository
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeComSettingsRepository
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
    private val dingTalk: DingTalkSettingsRepository,
    private val weCom: WeComSettingsRepository,
    private val napCat: ConfigNapCatFields,
    @Named("telegram") private val telegramBridge: ImBotBridge,
    @Named("weixin") private val weixinBridge: ImBotBridge,
    @Named("feishu") private val feishuBridge: ImBotBridge,
    @Named("qq") private val qqBridge: ImBotBridge,
    @Named("dingtalk") private val dingTalkBridge: ImBotBridge,
    @Named("wecom") private val weComBridge: ImBotBridge,
) {

    fun registerInto(registry: ConfigRegistry) {
        mapOf(
            "telegram" to telegramBridge,
            "weixin" to weixinBridge,
            "feishu" to feishuBridge,
            "qq" to qqBridge,
            "dingtalk" to dingTalkBridge,
            "wecom" to weComBridge,
        ).forEach { (id, bridge) ->
            registry.register(ReadOnlyField("im.$id.state", "实际连接状态（$id）", "运行时状态，不等同于启用开关。", ConfigSchema.Str()) {
                ConfigValue.Str(when (bridge.state.value) {
                    ImChannelState.Stopped -> "stopped"
                    ImChannelState.Connecting -> "connecting"
                    is ImChannelState.Connected -> "connected"
                    is ImChannelState.Error -> "error"
                })
            })
            registry.register(ReadOnlyField("im.$id.error", "连接错误（$id）", "脱敏后的运行时错误，非错误状态为空。", ConfigSchema.Str()) {
                ConfigValue.Str(SecretMasker.mask((bridge.state.value as? ImChannelState.Error)?.message.orEmpty()))
            })
        }
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
        registerDingTalk(registry)
        registerWeCom(registry)
        napCat.registerInto(registry)
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

    /** 钉钉：Stream 长连接，需要自建应用的 AppKey + AppSecret。 */
    private fun registerDingTalk(registry: ConfigRegistry) = with(registry) {
        register(
            DataStoreBoolField(
                path = "im.dingtalk.enabled",
                displayName = "启用钉钉机器人",
                description = "开启后以前台服务维持钉钉 Stream 长连接，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = dingTalk.configFlow.map { it.enabled },
                setter = { dingTalk.setEnabled(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.dingtalk.client_id",
                displayName = "钉钉 AppKey",
                description = "开放平台「企业内部应用」的 AppKey。留空即清除。",
                flow = dingTalk.configFlow.map { it.clientId },
                setter = { dingTalk.setClientId(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.dingtalk.client_secret",
                displayName = "钉钉 AppSecret",
                description = "与 AppKey 成对使用的 AppSecret（只显示一次）。留空即清除。",
                flow = dingTalk.configFlow.map { it.clientSecret },
                setter = { dingTalk.setClientSecret(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
        register(
            DataStoreStrField(
                path = "im.dingtalk.robot_code",
                displayName = "机器人编码",
                description = "控制台里的机器人编码，通常与 AppKey 相同；留空按 AppKey 处理。",
                flow = dingTalk.configFlow.map { it.robotCode },
                setter = { dingTalk.setRobotCode(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.dingtalk.proxy",
                displayName = "钉钉代理",
                description = "可选代理；留空则跟随全局代理设置。",
                flow = dingTalk.configFlow.map { it.proxy },
                setter = { dingTalk.setProxy(it) },
            ),
        )
    }

    /** 企业微信：智能机器人长连接，只需 BotID + Secret。 */
    private fun registerWeCom(registry: ConfigRegistry) = with(registry) {
        register(
            DataStoreBoolField(
                path = "im.wecom.enabled",
                displayName = "启用企业微信机器人",
                description = "开启后以前台服务维持企业微信长连接，把收到的消息交给 AI 处理并回复。默认关闭。",
                flow = weCom.configFlow.map { it.enabled },
                setter = { weCom.setEnabled(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.wecom.bot_id",
                displayName = "企业微信 BotID",
                description = "管理后台「智能机器人」里的机器人 ID。留空即清除。",
                flow = weCom.configFlow.map { it.botId },
                setter = { weCom.setBotId(it) },
            ),
        )
        register(
            DataStoreStrField(
                path = "im.wecom.secret",
                displayName = "企业微信 Secret",
                description = "长连接专用密钥，与回调模式的 Token/EncodingAESKey 不是一回事。留空即清除。",
                flow = weCom.configFlow.map { it.secret },
                setter = { weCom.setSecret(it) },
                risk = ConfigRisk.SENSITIVE,
            ),
        )
        register(
            DataStoreStrField(
                path = "im.wecom.proxy",
                displayName = "企业微信代理",
                description = "可选代理；留空则跟随全局代理设置。",
                flow = weCom.configFlow.map { it.proxy },
                setter = { weCom.setProxy(it) },
            ),
        )
    }
}
