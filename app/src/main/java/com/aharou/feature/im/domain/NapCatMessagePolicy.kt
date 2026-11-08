package com.aharou.feature.im.domain

import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.domain.model.ImInboundMessage

interface ImBridgePolicy {
    fun accepts(cfg: ImChannelConfig, message: ImInboundMessage): Boolean = true
    fun directed(cfg: ImChannelConfig, message: ImInboundMessage): Boolean =
        !message.isGroup || message.mentionsBot || message.text.contains("@") || message.replyToSenderId != null
    fun explicitlyAuthorized(cfg: ImChannelConfig, message: ImInboundMessage): Boolean = false
    fun ownerNamespace(channelId: String, cfg: ImChannelConfig): String = channelId
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

    override fun explicitlyAuthorized(cfg: ImChannelConfig, message: ImInboundMessage): Boolean {
        val c = cfg as NapCatConfig
        return if (message.isGroup) message.chatId.substringAfterLast(':') in c.allowedGroups
        else message.senderId in c.allowedUsers
    }

    override fun sessionDefaults(cfg: ImChannelConfig): ImSessionDefaults = (cfg as NapCatConfig).let {
        ImSessionDefaults(it.workspacePath, it.providerId, it.model)
    }
}
