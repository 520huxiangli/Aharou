package com.aharou.feature.im.domain

import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.domain.model.ImInboundMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatMessagePolicyTest {
    private val policy = NapCatMessagePolicy()
    private val config = NapCatConfig(accountId = "10001", respondGroups = true)

    private fun message(group: Boolean = false, sender: String = "20001") = ImInboundMessage(
        updateId = 1L,
        chatId = if (group) "account:10001:group:30001" else "account:10001:private:$sender",
        senderId = sender,
        senderName = "sender",
        chatTitle = "chat",
        text = "question",
        isGroup = group,
        messageId = "1",
        replyToSenderId = null
    )

    @Test
    fun `群聊明确提及本人才接收而提及别人不接收`() {
        val other = message(group = true).copy(text = "@20002 question")
        val self = other.copy(mentionsBot = true)

        assertFalse(policy.directed(config, other))
        assertFalse(policy.accepts(config, other))
        assertTrue(policy.directed(config, self))
        assertTrue(policy.accepts(config, self))
    }

    @Test
    fun `群聊引用本人可接收而引用别人不可接收`() {
        val self = message(group = true).copy(replyToSenderId = "10001")
        val other = self.copy(replyToSenderId = "20002")

        assertTrue(policy.directed(config, self))
        assertTrue(policy.accepts(config, self))
        assertFalse(policy.directed(config, other))
        assertFalse(policy.accepts(config, other))
    }

    @Test
    fun `取消必须提及后群聊普通消息可接收`() {
        val incoming = message(group = true)

        assertFalse(policy.accepts(config, incoming))
        assertTrue(policy.directed(config.copy(requireMention = false), incoming))
        assertTrue(policy.accepts(config.copy(requireMention = false), incoming))
    }

    @Test
    fun `私聊无需提及且不受群允许集合限制`() {
        val cfg = config.copy(allowedUsers = setOf("20001"), allowedGroups = setOf("30002"))
        val incoming = message()

        assertTrue(policy.directed(cfg, incoming))
        assertTrue(policy.accepts(cfg, incoming))
        assertTrue(policy.explicitlyAuthorized(cfg, incoming))
        assertFalse(policy.accepts(cfg, message(sender = "20002")))
        assertFalse(policy.explicitlyAuthorized(cfg, message(sender = "20002")))
    }

    @Test
    fun `群聊按群允许集合判定而不受私聊允许用户限制`() {
        val cfg = config.copy(allowedUsers = setOf("20002"), allowedGroups = setOf("30001"))
        val incoming = message(group = true).copy(mentionsBot = true)
        val otherGroup = incoming.copy(chatId = "account:10001:group:30002")

        assertTrue(policy.accepts(cfg, incoming))
        assertTrue(policy.explicitlyAuthorized(cfg, incoming))
        assertFalse(policy.accepts(cfg, otherGroup))
        assertFalse(policy.explicitlyAuthorized(cfg, otherGroup))
    }

    @Test
    fun `允许集合为空可接收但不视为显式授权`() {
        val privateMessage = message()
        val groupMessage = message(group = true).copy(mentionsBot = true)

        assertTrue(policy.accepts(config, privateMessage))
        assertTrue(policy.accepts(config, groupMessage))
        assertFalse(policy.explicitlyAuthorized(config, privateMessage))
        assertFalse(policy.explicitlyAuthorized(config, groupMessage))
    }

    @Test
    fun `关闭私聊不影响群聊且关闭群聊不影响私聊`() {
        val privateMessage = message()
        val groupMessage = message(group = true).copy(mentionsBot = true)
        val privateOff = config.copy(respondPrivate = false)
        val groupsOff = config.copy(respondGroups = false)

        assertFalse(policy.accepts(privateOff, privateMessage))
        assertTrue(policy.accepts(privateOff, groupMessage))
        assertTrue(policy.accepts(groupsOff, privateMessage))
        assertFalse(policy.accepts(groupsOff, groupMessage))
    }

    @Test
    fun `允许集合不能绕过群聊必须提及限制`() {
        val cfg = config.copy(allowedGroups = setOf("30001"))
        val incoming = message(group = true)

        assertTrue(policy.explicitlyAuthorized(cfg, incoming))
        assertFalse(policy.accepts(cfg, incoming))
    }

    @Test
    fun `自消息默认拒绝开启后私聊群聊均可接收`() {
        val privateMessage = message(sender = "10001")
        val groupMessage = message(group = true, sender = "10001").copy(mentionsBot = true)
        val cfg = config.copy(respondSelf = true)

        assertFalse(policy.accepts(config, privateMessage))
        assertFalse(policy.accepts(config, groupMessage))
        assertTrue(policy.accepts(cfg, privateMessage))
        assertTrue(policy.accepts(cfg, groupMessage))
    }

    @Test
    fun `开启自消息仍须满足允许集合和提及要求`() {
        val cfg = config.copy(respondSelf = true, allowedUsers = setOf("20001"))

        assertFalse(policy.accepts(cfg, message(sender = "10001")))
        assertFalse(policy.accepts(cfg, message(group = true, sender = "10001")))
    }

    @Test
    fun `聊天账号前缀必须完整匹配当前账号`() {
        val incoming = message()
        val otherAccount = config.copy(accountId = "10002")

        assertTrue(policy.accepts(config, incoming))
        assertFalse(policy.accepts(otherAccount, incoming))
        assertTrue(policy.accepts(otherAccount, incoming.copy(chatId = "account:10002:private:20001")))
        assertFalse(policy.accepts(config, incoming.copy(chatId = "account:100010:private:20001")))
        assertFalse(policy.accepts(config, incoming.copy(chatId = "20001")))
    }

    @Test
    fun `所有者命名空间按渠道和账号隔离`() {
        assertEquals("qq_napcat:10001", policy.ownerNamespace("qq_napcat", config))
        assertEquals("qq_napcat:10002", policy.ownerNamespace("qq_napcat", config.copy(accountId = "10002")))
        assertEquals("other:10001", policy.ownerNamespace("other", config))
    }

    @Test
    fun `会话默认值完整沿用配置的工作区提供商和模型`() {
        val cfg = config.copy(workspacePath = "/workspace/project", providerId = "provider-a", model = "model-a")

        assertEquals(ImSessionDefaults("/workspace/project", "provider-a", "model-a"), policy.sessionDefaults(cfg))
        assertEquals(ImSessionDefaults(), policy.sessionDefaults(NapCatConfig()))
    }
}
