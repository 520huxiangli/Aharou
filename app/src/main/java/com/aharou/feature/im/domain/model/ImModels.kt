package com.aharou.feature.im.domain.model

/** 解析后的一个 Telegram 更新：要么是聊天消息，要么是按钮回调。 */
sealed interface ImUpdate {
    val updateId: Long

    data class Message(val msg: ImInboundMessage) : ImUpdate {
        override val updateId: Long get() = msg.updateId
    }

    data class Callback(val callback: ImCallbackQuery) : ImUpdate {
        override val updateId: Long get() = callback.updateId
    }
}

/** 一条收到的聊天消息（已归一化为内部模型，不携带原始 Telegram JSON）。 */
data class ImInboundMessage(
    val updateId: Long,
    /** 会话所在聊天 id；私聊即对方用户 id，群聊为负数。 */
    val chatId: String,
    /** 发送者用户 id（白名单按它判定）。 */
    val senderId: String,
    val senderName: String,
    /** 群标题或私聊对方名，用作会话标题。 */
    val chatTitle: String,
    val text: String,
    val isGroup: Boolean,
    val messageId: String,
    /** 若本条是对某条消息的回复，携带被回复消息的发送者 id（用于判断是否 @ 机器人）。 */
    val replyToSenderId: String?,
    /**
     * 本条是否 @ 了机器人（群聊才需要）。平台能直接判定就在各自解析里填，
     * 文本里出现 @ 只当兜底。
     */
    val mentionsBot: Boolean = false,

    /** 随消息带来的图片/文件。各平台形态不一，统一成 [ImAttachment]。 */
    val attachments: List<ImAttachment> = emptyList(),

    /**
     * 平台要求回复时回带的上下文令牌（微信 iLink 的 context_token）。
     * 其余平台为 null，不影响原有行为。
     */
    val contextToken: String? = null
)

/** 一次内联按钮点击（审批卡片）。 */
data class ImCallbackQuery(
    val updateId: Long,
    /** 回调 id，用于 answerCallbackQuery 消掉客户端的转圈。 */
    val id: String,
    val chatId: String,
    /** 点击者用户 id（须为已配对者才认）。 */
    val senderId: String,
    /** 承载按钮的那条消息 id（用于点后去掉按钮）。 */
    val messageId: String,
    /** 按钮携带的数据，形如 `a:y:<审批id>`。 */
    val data: String
)

/**
 * 对方发来的一份附件。
 *
 * 下载两种方式二选一：能直接拿直链的（微信 CDN、Telegram getFile）走 [url]；
 * 需要平台专用接口才能下的（飞书 message_resource、QQ 附件）用 [fileId]，
 * 由 [com.aharou.feature.im.domain.ImChannel.downloadAttachment] 现场下载。
 */
data class ImAttachment(
    val kind: Kind,
    /** 平台侧标识：飞书 file_key、QQ 附件 url、Telegram file_id 等。 */
    val fileId: String = "",
    /** 可直接 GET 的直链；为空时由渠道实现用 fileId 自己下。 */
    val url: String? = null,
    val fileName: String = "",
    val mimeType: String = "",
    val sizeBytes: Long = 0L
) {
    enum class Kind { IMAGE, FILE, AUDIO, VIDEO }
}

/** 机器人的自身身份（getMe 结果）。 */
data class ImBotIdentity(val id: Long, val username: String)

