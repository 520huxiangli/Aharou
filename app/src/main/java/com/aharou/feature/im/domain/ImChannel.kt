package com.aharou.feature.im.domain

import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImUpdate
import java.io.File

/**
 * 各 IM 平台配置的公共面：编排层只关心「能不能连」，不关心凭证长什么样。
 *
 * 平台各自的凭证字段留在自己的 Config 里（如微信的 `botToken` + `ilinkUserId`、
 * 飞书/QQ 的 `appId` + `appSecret`），这里只暴露通用判定与连接参数。
 */
interface ImChannelConfig {
    val enabled: Boolean

    /** 凭证齐不齐：不齐时编排层直接停下，不反复打接口。 */
    val ready: Boolean

    /** 可选代理，如 `http://127.0.0.1:7890`；空串表示走全局设置。 */
    val proxy: String
}

/**
 * 一个 IM 平台的接入通道。
 *
 * 只负责「收发原始消息 + 报告平台身份」，**不含**配对、会话映射、跑 agent 这些编排逻辑——
 * 那些由 [com.aharou.feature.im.domain.ImBotBridge] 统一处理，三个平台共用一份。
 *
 * 平台能力有差异（Telegram 能编辑消息、能带内联按钮；微信只能发新消息），
 * 因此编辑与按钮都是**可选能力**：不支持时返回 false/null，编排层自动降级。
 */
interface ImChannel {

    /** 稳定标识，用于会话 id 前缀与配置分区：`telegram` / `weixin` / `feishu` / `qq`。 */
    val id: String

    /** 平台身份；凭证不可用时抛异常。 */
    suspend fun identity(cfg: ImChannelConfig): ImBotIdentity

    /**
     * 长轮询/拉取新消息。
     *
     * [cursor] 是上次处理到的位置（Telegram 是 update_id，微信是服务端游标转换成的数字），
     * 首次传 0。实现方应尽量让本轮阻塞 [timeoutSeconds] 秒再返回，避免空转。
     */
    suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate>

    /** 发一条文本，返回平台侧消息 id（拿不到返回空串）。超长文本由实现方自行分片。 */
    suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String

    /**
     * 原地编辑一条已发消息（流式刷新用）。平台不支持时返回 false，
     * 编排层会退化成「不再刷新，只在结束时补发最终文本」。
     */
    suspend fun edit(cfg: ImChannelConfig, chatId: String, messageId: String, text: String): Boolean = false

    /**
     * 这个平台能不能就地编辑已发送的消息。
     *
     * 不能编辑的平台（微信 iLink、QQ）必须在流式过程中**一条都不发**，只 buffered 到结束时发一次；
     * 否则会先发一条半成品、结束时 edit 失败再补一条最终稿，对方那边就是两条内容不同的消息。
     */
    val supportsEdit: Boolean get() = false

    /** 错误信息脱敏：平台实现必须抹掉 token 等敏感串。 */
    fun redact(text: String, cfg: ImChannelConfig): String

    /**
     * 下载一份对方发来的附件，返回本地文件；不支持或失败返回 null。
     *
     * 默认实现不下载——平台差异太大（微信走 CDN 直链、飞书要 file_key 换资源、
     * Telegram 要 getFile），各自在客户端里覆写。
     */
    suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? = null

    /**
     * 发一张图或一个文件。
     *
     * 默认不支持——各平台上传方式差异太大（Telegram 用 multipart、微信先换上传地址再走 CDN、
     * 飞书先传拿 image_key / file_key、QQ 走富媒体接口），各自在客户端里覆写。
     *
     * @return 平台侧消息 id；不支持或失败返回 null
     */
    suspend fun sendMedia(
        cfg: ImChannelConfig,
        chatId: String,
        file: File,
        kind: ImAttachment.Kind,
        caption: String = ""
    ): String? = null

    /**
     * 连接前的准备动作。
     *
     * Telegram 需要先删 webhook（否则 getUpdates 拿到 409）、并在首次运行时丢弃积压消息。
     * 其他平台默认什么都不做。
     */
    suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) {}

    /** 工具审批按钮的载荷；不支持按钮的平台返回 false，编排层改发纯文本说明。 */
    val supportsButtons: Boolean get() = false

    /**
     * 带按钮发一条消息。平台不支持或在 [supportsButtons] 为 false 时返回 null，
     * 编排层据此降级成纯文本发送。
     */
    suspend fun sendWithButtons(
        cfg: ImChannelConfig,
        chatId: String,
        text: String,
        buttons: List<List<Pair<String, String>>>
    ): String? = null

    /** 用户点了审批按钮后的回执（清掉客户端的转圈）；不支持则空实现。 */
    suspend fun acknowledgeCallback(cfg: ImChannelConfig, callbackId: String) {}

    /** 去掉已点击消息上的按钮；不支持则空实现。 */
    suspend fun clearMarkup(cfg: ImChannelConfig, chatId: String, messageId: String) {}
}

/** 通道连接状态，供前台服务与设置界面观察（与平台无关）。 */
sealed interface ImChannelState {
    /** 未运行（开关关闭或服务未启动）。 */
    data object Stopped : ImChannelState

    /** 已启动、正在建连或重试中。 */
    data object Connecting : ImChannelState

    /** 已连上，[identity] 为平台侧身份（如 bot 用户名）。 */
    data class Connected(val identity: String) : ImChannelState

    /** 出错后正在退避重试；[message] 已脱敏。 */
    data class Error(val message: String) : ImChannelState
}
