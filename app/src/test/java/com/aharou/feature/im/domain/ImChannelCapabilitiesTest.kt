package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImUpdate
import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力声明与实现的一致性。
 *
 * 声明是编排层降级的唯一依据（不能编辑就不在流式过程中发半成品），所以这里两头都钉住：
 * 声明值符合预期，且 `true` 的那一项在实现类里确实被覆写了——只改声明不改实现（或反过来）都会红。
 */
class ImChannelCapabilitiesTest {

    /** 只实现必要成员的最小渠道：用来验证接口默认值。 */
    private class BareChannel : ImChannel {
        override val id: String = "bare"

        override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity = ImBotIdentity(0L, "bare")

        override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> = emptyList()

        override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String = ""

        override fun redact(text: String, cfg: ImChannelConfig): String = text
    }

    private val http = OkHttpClient()
    private val context = mockk<Context>(relaxed = true)

    private val telegram = TelegramBotClient(http)
    private val weixin = WeixinIlinkClient(http, context)
    private val feishu = FeishuChannelClient(http, context)
    private val qq = QqChannelClient(http, context)
    private val napcat = NapCatChannelClient(http, context)

    private val channels: List<ImChannel> = listOf(telegram, weixin, feishu, qq, napcat)

    private val mediaOnly = ImChannelCapabilities(sendMedia = true, downloadAttachment = true)

    /**
     * 方法是否在实现类里真正覆写过。
     *
     * 只看名字会误判：接口默认实现在实现类里会被编译器合成一个 ACC_BRIDGE 的转发方法
     * （`-Xjvm-default=disable` 的产物），名字与真覆写一模一样。所以必须排掉 bridge 与 synthetic。
     */
    private fun declares(channel: ImChannel, name: String): Boolean =
        channel.javaClass.declaredMethods.any { it.name == name && !it.isBridge && !it.isSynthetic }

    @Test
    fun channelIds_matchExpected() {
        assertEquals("五个渠道的稳定标识变了会连带影响会话前缀与参考号，实际是 ${channels.map { it.id }}",
            listOf("telegram", "weixin", "feishu", "qq", "qq_napcat"), channels.map { it.id })
    }

    @Test
    fun telegram_declaresAllCapabilities() {
        assertEquals("Telegram 四项能力都应为 true，实际是 ${telegram.capabilities}",
            ImChannelCapabilities(editMessage = true, inlineButtons = true, sendMedia = true, downloadAttachment = true),
            telegram.capabilities)
    }

    @Test
    fun weixin_onlySupportsMedia() {
        assertEquals("微信用 iLink 不能改消息也不能带按钮，实际是 ${weixin.capabilities}",
            mediaOnly, weixin.capabilities)
    }

    @Test
    fun feishu_onlySupportsMedia() {
        assertEquals("飞书当前只声明收发媒体，实际是 ${feishu.capabilities}", mediaOnly, feishu.capabilities)
    }

    @Test
    fun qq_onlySupportsMedia() {
        assertEquals("QQ 当前只声明收发媒体，实际是 ${qq.capabilities}", mediaOnly, qq.capabilities)
    }

    @Test
    fun napcat_onlySupportsMedia() {
        assertEquals("NapCat 当前只声明收发媒体，实际是 ${napcat.capabilities}", mediaOnly, napcat.capabilities)
    }

    @Test
    fun bareChannel_declaresNothing() {
        val bare = BareChannel()

        assertEquals("没声明能力时默认四项全 false，实际是 ${bare.capabilities}",
            ImChannelCapabilities(), bare.capabilities)
        assertFalse("默认不能编辑，supportsEdit 实际是 ${bare.supportsEdit}", bare.supportsEdit)
        assertFalse("默认不能带按钮，supportsButtons 实际是 ${bare.supportsButtons}", bare.supportsButtons)
    }

    @Test
    fun supportsFlags_followCapabilities() {
        (channels + BareChannel()).forEach { channel ->
            assertEquals("${channel.id}：supportsEdit=${channel.supportsEdit} 与 capabilities.editMessage=" +
                "${channel.capabilities.editMessage} 不一致", channel.capabilities.editMessage, channel.supportsEdit)
            assertEquals("${channel.id}：supportsButtons=${channel.supportsButtons} 与 capabilities.inlineButtons=" +
                "${channel.capabilities.inlineButtons} 不一致", channel.capabilities.inlineButtons, channel.supportsButtons)
        }
    }

    @Test
    fun declaredEdit_matchesCapabilities() {
        channels.forEach { channel ->
            assertEquals("${channel.id}：声明 editMessage=${channel.capabilities.editMessage}，" +
                "但实现类是否覆写 edit 是 ${declares(channel, "edit")}",
                channel.capabilities.editMessage, declares(channel, "edit"))
        }
        assertTrue("Telegram 应覆写 edit，实际没有", declares(telegram, "edit"))
    }

    @Test
    fun declaredButtons_matchesCapabilities() {
        channels.forEach { channel ->
            assertEquals("${channel.id}：声明 inlineButtons=${channel.capabilities.inlineButtons}，" +
                "但实现类是否覆写 sendWithButtons 是 ${declares(channel, "sendWithButtons")}",
                channel.capabilities.inlineButtons, declares(channel, "sendWithButtons"))
        }
    }

    @Test
    fun declaredMediaMatchesCapabilities() {
        channels.forEach { channel ->
            assertEquals("${channel.id}：声明 sendMedia=${channel.capabilities.sendMedia}，" +
                "但实现类是否覆写 sendMedia 是 ${declares(channel, "sendMedia")}",
                channel.capabilities.sendMedia, declares(channel, "sendMedia"))
            assertEquals("${channel.id}：声明 downloadAttachment=${channel.capabilities.downloadAttachment}，" +
                "但实现类是否覆写 downloadAttachment 是 ${declares(channel, "downloadAttachment")}",
                channel.capabilities.downloadAttachment, declares(channel, "downloadAttachment"))
        }
    }
}
