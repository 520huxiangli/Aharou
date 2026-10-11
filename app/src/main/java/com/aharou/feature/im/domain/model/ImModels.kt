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
    val replyToSenderId: String?
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

/** 机器人的自身身份（getMe 结果）。 */
data class ImBotIdentity(val id: Long, val username: String)

/** 已配对的聊天/人，供设置界面展示与移除。 */
data class PairedChat(
    val senderId: String,
    val senderName: String,
    val chatId: String,
    val approvedAt: Long
)

/** 一条待审批的配对请求（6 位码）。 */
data class PendingPairing(
    val code: String,
    val senderId: String,
    val senderName: String,
    val chatId: String,
    val createdAt: Long,
    val expiresAt: Long
)

/** 连接状态，供前台服务与设置界面观察。 */
sealed interface TelegramState {
    /** 未运行（开关关闭或服务未启动）。 */
    data object Stopped : TelegramState

    /** 已启动、正在建连或重试。 */
    data object Connecting : TelegramState

    /** getMe 成功，长轮询进行中。 */
    data class Connected(val username: String) : TelegramState

    /** 连接失败（token 错误、基址不可达等），message 已脱敏。 */
    data class Error(val message: String) : TelegramState
}
