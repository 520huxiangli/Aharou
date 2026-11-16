package com.aharou.feature.im.domain

import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.domain.model.ImInboundMessage

interface ImBridgePolicy {
    fun accepts(cfg: ImChannelConfig, message: ImInboundMessage): Boolean = true
    fun directed(cfg: ImChannelConfig, message: ImInboundMessage): Boolean =
        !message.isGroup || message.mentionsBot || message.text.contains("@") || message.replyToSenderId != null
    fun explicitlyAuthorized(cfg: ImChannelConfig, message: ImInboundMessage): Boolean = false
    fun ownerNamespace(channelId: String, cfg: ImChannelConfig): String = channelId

    /**
     * 群聊按群各自授权，私聊只认第一个；靠 chatId 形态区分是群还是私聊。
     * 官方 QQ 是 `group:<openid>`，NapCat 是 `account:<账号>:group:<群号>`。
     */
    fun isGroupChat(chatId: String): Boolean =
        chatId.startsWith("group:") || chatId.contains(":group:")

    /** 是否允许新群自动认主。配了显式群白名单的平台应返回 false，白名单外的群一律拒绝。 */
    fun permitsNewGroup(cfg: ImChannelConfig, chatId: String): Boolean = true
    fun sessionDefaults(cfg: ImChannelConfig): ImSessionDefaults = ImSessionDefaults()
    val cancelApprovalsOnReset: Boolean get() = false

    companion object { val Default: ImBridgePolicy = object : ImBridgePolicy {} }
}

data class ImSessionDefaults(val workspacePath: String = "", val providerId: String = "", val model: String = "")

class NapCatMessagePolicy : ImBridgePolicy {
    override val cancelApprovalsOnReset: Boolean = true
    override fun ownerNamespace(channelId: String, cfg: ImChannelConfig): String =
        "$channelId:${(cfg as NapCatConfig).accountId}"

    override fun directed(cfg: ImChannelConfig, message: ImInboundMessage): Boolean {
        val c = cfg as NapCatConfig
        return !message.isGroup || !c.requireMention || message.mentionsBot || message.replyToSenderId == c.accountId
    }

    override fun accepts(cfg: ImChannelConfig, message: ImInboundMessage): Boolean {
        val c = cfg as NapCatConfig
        if (!message.chatId.startsWith("account:${c.accountId}:")) return false
        if (!c.respondSelf && message.senderId == c.accountId) return false
        if (!message.isGroup) return c.respondPrivate && (c.allowedUsers.isEmpty() || message.senderId in c.allowedUsers)
        val group = message.chatId.substringAfterLast(':')
        return c.respondGroups && (c.allowedGroups.isEmpty() || group in c.allowedGroups) && directed(cfg, message)
    }

    override fun permitsNewGroup(cfg: ImChannelConfig, chatId: String): Boolean =
        (cfg as NapCatConfig).allowedGroups.isEmpty()

    override fun explicitlyAuthorized(cfg: ImChannelConfig, message: ImInboundMessage): Boolean {
        val c = cfg as NapCatConfig
        return if (message.isGroup) message.chatId.substringAfterLast(':') in c.allowedGroups
        else message.senderId in c.allowedUsers
    }

    override fun sessionDefaults(cfg: ImChannelConfig): ImSessionDefaults = (cfg as NapCatConfig).let {
        ImSessionDefaults(it.workspacePath, it.providerId, it.model)
    }
}
