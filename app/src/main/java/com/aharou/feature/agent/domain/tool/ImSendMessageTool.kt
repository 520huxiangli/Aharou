package com.aharou.feature.agent.domain.tool

import com.aharou.feature.im.data.repository.DingTalkSettingsRepository
import com.aharou.feature.im.data.repository.FeishuSettingsRepository
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.im.data.repository.QqSettingsRepository
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.data.repository.WeComSettingsRepository
import com.aharou.feature.im.data.repository.WeixinSettingsRepository
import com.aharou.feature.im.domain.DingTalkChannelClient
import com.aharou.feature.im.domain.FeishuChannelClient
import com.aharou.feature.im.domain.ImChannel
import com.aharou.feature.im.domain.ImChannelConfig
import com.aharou.feature.im.domain.ImDeliveryTarget
import com.aharou.feature.im.domain.ImDeliveryTargetStore
import com.aharou.feature.im.domain.ImSettingsSource
import com.aharou.feature.im.domain.NapCatChannelClient
import com.aharou.feature.im.domain.QqChannelClient
import com.aharou.feature.im.domain.TelegramBotClient
import com.aharou.feature.im.domain.WeComChannelClient
import com.aharou.feature.im.domain.WeixinIlinkClient
import com.aharou.feature.im.domain.model.ImAttachment
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 主动往已配置的 IM 会话发消息，供定时任务结果、后台任务完成通知这类「没有正在进行的对话」的场景用。
 *
 * 与 [SendImFileTool] 的分工：那个工具只回发给「当前这一轮对话的来源聊天」，靠会话映射定位；
 * 本工具的目标由参数显式给出（稳定别名，或 `渠道:chatId` 直填），因此不依赖任何会话上下文。
 *
 * 为什么不经过 [com.aharou.feature.im.domain.ImBotBridge]：桥的发送入口只服务「回复正在说话的人」，
 * 且要求桥正在长轮询；主动投递的目标场景恰恰是服务在重连、或根本没有会话的时候。
 * 这里直接走各渠道客户端，配置从对应的设置仓现读，连得上就能发。
 *
 * 渠道客户端是 `@Singleton` 的叶子依赖，直接注入不会形成 Dagger 依赖环（[SendImFileTool] 那边要
 * `Provider<ImBotBridge>` 是因为桥反向依赖 agent 的工具链）。
 */
@Singleton
class ImSendMessageTool @Inject constructor(
    private val store: ImDeliveryTargetStore,
    telegram: TelegramBotClient,
    weixin: WeixinIlinkClient,
    feishu: FeishuChannelClient,
    qq: QqChannelClient,
    napCat: NapCatChannelClient,
    dingTalk: DingTalkChannelClient,
    weCom: WeComChannelClient,
    telegramSettings: TelegramSettingsRepository,
    weixinSettings: WeixinSettingsRepository,
    feishuSettings: FeishuSettingsRepository,
    qqSettings: QqSettingsRepository,
    napCatSettings: NapCatSettingsRepository,
    dingTalkSettings: DingTalkSettingsRepository,
    weComSettings: WeComSettingsRepository,
) : AgentTool() {

    private val channels: Map<String, ImChannel> = mapOf(
        CHANNEL_TELEGRAM to telegram,
        CHANNEL_WEIXIN to weixin,
        CHANNEL_FEISHU to feishu,
        CHANNEL_QQ to qq,
        CHANNEL_NAPCAT to napCat,
        CHANNEL_DINGTALK to dingTalk,
        CHANNEL_WECOM to weCom,
    )

    private val settings: Map<String, ImSettingsSource> = mapOf(
        CHANNEL_TELEGRAM to telegramSettings,
        CHANNEL_WEIXIN to weixinSettings,
        CHANNEL_FEISHU to feishuSettings,
        CHANNEL_QQ to qqSettings,
        CHANNEL_NAPCAT to napCatSettings,
        CHANNEL_DINGTALK to dingTalkSettings,
        CHANNEL_WECOM to weComSettings,
    )

    override val name = "send_im_message"

    override val description =
        "主动往已配置的 IM 会话发一条消息（可选带一个文件），用于定时任务结果、后台任务完成通知这类" +
            "没有正在进行的对话的场景。target 填别名（如 tgt-1a2b3c4d5e6f7a8b）或「渠道:chatId」直填，渠道取 " +
            "telegram / weixin / feishu / qq / qq_napcat / dingtalk / wecom；chatId 本身可能带冒号（NapCat 群是 account:<账号>:<群号>），" +
            "按第一个冒号切开即可。首次用「渠道:chatId」发送会自动登记别名，之后固定用别名。返回里 success 表示已送出，" +
            "error 表示确定没发出去（可换目标或修配置后再试），partial/unknown 表示可能已送达——" +
            "**结果未知时绝对不要自动重发**，先确认对方是否收到。" +
            "微信（weixin）不能随时推送：iLink 的发送必须回带最近一条用户消息带来的 context_token，" +
            "用户没先说话、或 App 重启后还没收到过新消息时，服务端会静默丢弃（HTTP 200 但没回执）；" +
            "所以微信只适合「用户刚说过话」的当下投递，长期定时推送请优先用 Telegram / 飞书 / QQ。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "target" to ToolParameter(
            name = "target",
            type = ParameterType.STRING,
            description = "投递目标：别名（tgt-…）或「渠道:chatId」，如 telegram:123456789、feishu:oc_xxx、" +
                "qq_napcat:account:10001:group:20002",
            required = true
        ),
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "要发送的正文；带 filePath 时它同时作为文件的说明文字",
            required = true
        ),
        "filePath" to ToolParameter(
            name = "filePath",
            type = ParameterType.STRING,
            description = "可选：要一起发送的本地文件绝对路径（须在当前工作区或应用私有目录内）。" +
                "渠道不支持发文件时只发正文，并在结果里说明文件没发",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val rawTarget = args["target"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val text = args["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
        val filePath = args["filePath"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (rawTarget.isEmpty()) return ToolResult.Error(noTargetHint(), "NO_TARGET")
        if (text.isEmpty()) return ToolResult.Error("缺少 text 参数", "INVALID_ARGS")

        val resolved = resolveRoute(rawTarget)
        val (channelId, chatId) = when (resolved) {
            is TargetLookup.Existing -> resolved.target.channelId to resolved.target.chatId
            is TargetLookup.Direct -> resolved.channelId to resolved.chatId
            is TargetLookup.Invalid -> return ToolResult.Error(resolved.message, "NO_TARGET")
        }

        val channel = channels[channelId]
            ?: return ToolResult.Error("不支持的渠道「$channelId」（可能是登记记录里的渠道已经不存在）", "NO_TARGET")
        val settingsSource = settings[channelId]
            ?: return ToolResult.Error("渠道「$channelId」没有配置来源", "NO_TARGET")

        // 配置门槛先看一遍：凭证不齐时直接说清楚，不要等平台回一个难懂的鉴权错误
        val cfg = settingsSource.currentConfig()
        if (!cfg.enabled) {
            return ToolResult.Error(
                "$channelId 渠道没有启用，发不出去：先到「设置 → 机器人接入」里打开并填好凭证",
                "CHANNEL_DISABLED"
            )
        }
        if (!cfg.ready) {
            return ToolResult.Error(
                "$channelId 渠道凭证不完整（缺 token / appId 之类），先补全凭证再发",
                "CHANNEL_NOT_READY"
            )
        }

        // 直填路由顺手登记成别名，但要等配置门槛过了再写：
        // 否则一个永远发不出去的聊天也会进登记表，往后每次「已登记目标」提示里都是脏数据
        val target = when (resolved) {
            is TargetLookup.Existing -> resolved.target
            is TargetLookup.Direct -> store.register(channelId, chatId)
            is TargetLookup.Invalid -> return ToolResult.Error(resolved.message, "NO_TARGET")
        }

        val file = if (filePath.isNotEmpty()) {
            val candidate = File(filePath)
            if (!candidate.exists() || !candidate.isFile) {
                return ToolResult.Error("文件不存在：$filePath", "NOT_FOUND")
            }
            candidate
        } else {
            null
        }

        return if (file == null) {
            deliverText(target, channel, cfg, text)
        } else if (!channel.capabilities.sendMedia) {
            // 渠道发不了文件，但正文本身可以单独发一条：不因为附件失败把整次投递作废
            when (val sent = sendText(target, channel, cfg, text)) {
                is Delivery.Sent -> ToolResult.Partial(
                    payload(target, STATUS_TEXT_ONLY, sent.messageId),
                    "正文已发送；${target.channelId} 渠道不支持发文件，${file.name} 没有发出"
                )
                is Delivery.Failed -> ToolResult.Error(failed(target, sent.reason), "SEND_FAILED")
                is Delivery.Unknown -> unknown(target, sent.reason)
            }
        } else {
            deliverFile(target, channel, cfg, file, text)
        }
    }

    private fun failed(target: ImDeliveryTarget, reason: String): String =
        "向 ${target.channelId}:${target.chatId} 发送失败：$reason"

    private suspend fun deliverText(
        target: ImDeliveryTarget,
        channel: ImChannel,
        cfg: ImChannelConfig,
        text: String
    ): ToolResult = when (val sent = sendText(target, channel, cfg, text)) {
        is Delivery.Sent -> ToolResult.Success(payload(target, STATUS_SENT, sent.messageId))
        is Delivery.Failed -> ToolResult.Error(failed(target, sent.reason), "SEND_FAILED")
        is Delivery.Unknown -> unknown(target, sent.reason)
    }

    /**
     * 带文件投递：正文作说明文字随文件一起发。
     *
     * 文件确定没发出去时再把正文单独发一次——文件与正文是两条独立消息，不构成重复投递；
     * 结果未知时不做任何补发，否则对方可能收到「一条带说明的文件」加「一条重复正文」。
     */
    private suspend fun deliverFile(
        target: ImDeliveryTarget,
        channel: ImChannel,
        cfg: ImChannelConfig,
        file: File,
        text: String
    ): ToolResult = when (
        val sent = attemptSend(channel, cfg) {
            channel.sendMedia(cfg, target.chatId, file, kindOf(file), text)
        }
    ) {
        is Delivery.Sent -> ToolResult.Success(payload(target, STATUS_SENT, sent.messageId))
        is Delivery.Failed -> when (val fallback = sendText(target, channel, cfg, text)) {
            is Delivery.Sent -> ToolResult.Partial(
                payload(target, STATUS_TEXT_ONLY, fallback.messageId),
                "正文已发送；文件 ${file.name} 没发出去：${sent.reason}"
            )
            is Delivery.Failed -> ToolResult.Error(
                "文件和正文都没发出去（${target.channelId}:${target.chatId}）：${sent.reason}；正文：${fallback.reason}",
                "SEND_FAILED"
            )
            is Delivery.Unknown -> ToolResult.Partial(
                payload(target, STATUS_UNKNOWN),
                "文件没发出去（${sent.reason}）；正文结果未知，可能已送达，不要自动重发"
            )
        }
        is Delivery.Unknown -> ToolResult.Partial(
            payload(target, STATUS_UNKNOWN),
            "文件与说明文字可能已经送达（${sent.reason}），结果未知，不要自动重发"
        )
    }

    private suspend fun sendText(
        target: ImDeliveryTarget,
        channel: ImChannel,
        cfg: ImChannelConfig,
        text: String
    ): Delivery {
        val sent = attemptSend(channel, cfg) { channel.send(cfg, target.chatId, text) }
        // 微信的发送接口对「收下」与「静默丢弃」都回 HTTP 200，只有回带了 client_id 才算真有回执；
        // 空 id 一律按未知处理，绝不能当成功让上层继续往下走。
        return if (sent is Delivery.Sent && sent.messageId.isBlank()) {
            if (target.channelId == CHANNEL_WEIXIN) {
                Delivery.Unknown("微信没有返回消息 id，多半是缺 context_token")
            } else {
                sent
            }
        } else {
            sent
        }
    }

    /**
     * 统一发一次并归一成三态。
     *
     * 三态而不是成败两态，只回答「还能不能再发一次」：连不上、平台明确拒收说明正文没到；
     * 超时或连接中途断开时平台可能已经收下了，再发对方会收到两条。
     */
    private suspend fun attemptSend(
        channel: ImChannel,
        cfg: ImChannelConfig,
        block: suspend () -> String?
    ): Delivery = try {
        val messageId = withTimeout(SEND_TIMEOUT_MS) { block() }
        if (messageId == null) Delivery.Failed("平台没有接受这条消息") else Delivery.Sent(messageId)
    } catch (e: TimeoutCancellationException) {
        Delivery.Unknown("超过 ${SEND_TIMEOUT_MS / 1000} 秒没有回执")
    } catch (e: CancellationException) {
        throw e
    } catch (e: SocketTimeoutException) {
        Delivery.Unknown(reasonOf(channel, cfg, e))
    } catch (e: ConnectException) {
        Delivery.Failed(reasonOf(channel, cfg, e))
    } catch (e: UnknownHostException) {
        Delivery.Failed(reasonOf(channel, cfg, e))
    } catch (e: IOException) {
        Delivery.Unknown(reasonOf(channel, cfg, e))
    } catch (e: Exception) {
        Delivery.Failed(reasonOf(channel, cfg, e))
    }

    /** 错误文本必须过渠道的脱敏实现，否则 token 会顺着工具结果进模型上下文。 */
    private fun reasonOf(channel: ImChannel, cfg: ImChannelConfig, e: Throwable): String =
        channel.redact(e.message ?: e::class.java.simpleName, cfg).ifBlank { e::class.java.simpleName }

    private fun unknown(target: ImDeliveryTarget, reason: String): ToolResult = ToolResult.Partial(
        payload(target, STATUS_UNKNOWN),
        "结果未知（$reason）：消息可能已经送达，不要自动重发；先确认对方是否收到，或者让用户决定"
    )

    private fun payload(
        target: ImDeliveryTarget,
        status: String,
        messageId: String = ""
    ) = buildJsonObject {
        put("status", status)
        put("alias", target.alias)
        put("channel", target.channelId)
        put("chatId", target.chatId)
        if (target.displayName.isNotBlank()) put("displayName", target.displayName)
        if (messageId.isNotBlank()) put("messageId", messageId)
        put(
            "message",
            when (status) {
                STATUS_SENT -> "已发送到 ${target.channelId}:${target.chatId}（别名 ${target.alias}）"
                STATUS_TEXT_ONLY -> "正文已发送到 ${target.channelId}:${target.chatId}（别名 ${target.alias}），文件未发出"
                else -> "投递到 ${target.channelId}:${target.chatId}（别名 ${target.alias}）的结果未知"
            }
        )
    }

    private suspend fun resolveRoute(raw: String): TargetLookup {
        store.find(raw)?.let { return TargetLookup.Existing(it) }

        val separator = raw.indexOf(':')
        if (separator <= 0) return TargetLookup.Invalid(unknownAliasHint())
        val channelId = normalizeChannelId(raw.substring(0, separator))
        val chatId = raw.substring(separator + 1).trim()
        if (channelId !in channels) {
            return TargetLookup.Invalid(
                "未知渠道「$channelId」，可用：${channels.keys.joinToString(" / ")}"
            )
        }
        if (chatId.isEmpty()) {
            return TargetLookup.Invalid("target 里「$channelId:」后面的 chatId 为空")
        }
        return TargetLookup.Direct(channelId, chatId)
    }

    private fun normalizeChannelId(raw: String): String {
        val id = raw.trim().lowercase()
        return CHANNEL_ALIASES[id] ?: id
    }

    private suspend fun unknownAliasHint(): String {
        val known = store.targets()
        val list = if (known.isEmpty()) {
            "（还没有登记过任何目标）"
        } else {
            known.joinToString("、") { "${it.alias} → ${it.channelId}:${it.chatId}" }
        }
        return "没有可用的投递目标：别名不存在或格式不对。" +
            "先让用户在 IM 里跟机器人说一句话（这样才知道 chatId 是什么），" +
            "或者直接用「渠道:chatId」指定（如 telegram:123456789），首次发送会自动登记别名。" +
            "已登记：$list"
    }

    private fun noTargetHint(): String =
        "缺少 target 参数：填别名（tgt-…）或「渠道:chatId」。没有可用目标时，" +
            "先让用户在 IM 里跟机器人说一句话拿到 chatId，再用「渠道:chatId」发送并登记别名。"

    private fun kindOf(file: File): ImAttachment.Kind = when (file.extension.lowercase()) {
        "png", "jpg", "jpeg", "webp", "gif", "bmp" -> ImAttachment.Kind.IMAGE
        "mp3", "wav", "ogg", "m4a", "amr", "silk" -> ImAttachment.Kind.AUDIO
        "mp4", "mov", "mkv", "webm" -> ImAttachment.Kind.VIDEO
        else -> ImAttachment.Kind.FILE
    }

    private sealed interface Delivery {
        data class Sent(val messageId: String) : Delivery
        data class Failed(val reason: String) : Delivery
        data class Unknown(val reason: String) : Delivery
    }

    private sealed interface TargetLookup {
        /** 已登记的别名命中。 */
        data class Existing(val target: ImDeliveryTarget) : TargetLookup
        /** 直填的「渠道:chatId」，还没登记。 */
        data class Direct(val channelId: String, val chatId: String) : TargetLookup
        data class Invalid(val message: String) : TargetLookup
    }

    private companion object {
        const val CHANNEL_TELEGRAM = "telegram"
        const val CHANNEL_WEIXIN = "weixin"
        const val CHANNEL_FEISHU = "feishu"
        const val CHANNEL_QQ = "qq"
        const val CHANNEL_NAPCAT = "qq_napcat"
        const val CHANNEL_DINGTALK = "dingtalk"
        const val CHANNEL_WECOM = "wecom"

        /** 模型常把个人 QQ 渠道写成工具名 `napcat`，认下来省一轮纠错。 */
        val CHANNEL_ALIASES = mapOf("napcat" to CHANNEL_NAPCAT)

        const val STATUS_SENT = "sent"
        const val STATUS_TEXT_ONLY = "text_sent_file_skipped"
        const val STATUS_UNKNOWN = "unknown"

        /** 一次发送的等待上限：超过就当「可能已送达」，不让定时任务卡在平台超时上。 */
        const val SEND_TIMEOUT_MS = 20_000L
    }
}
