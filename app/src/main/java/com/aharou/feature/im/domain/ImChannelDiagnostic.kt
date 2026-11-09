package com.aharou.feature.im.domain

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** 出错发生在哪一步。用户看到的「卡在哪」就是这个，比错误码好懂。 */
enum class ImStage {
    /** 申请登录二维码。 */
    QR_BEGIN,

    /** 等待扫码确认。 */
    QR_POLL,

    /** 读凭证。 */
    CREDENTIAL,

    /** 建立连接。 */
    CONNECT,

    /** 收消息。 */
    POLL,

    /** 发消息。 */
    SEND,

    /** 收发附件。 */
    ATTACHMENT,

    UNKNOWN,
}

/** 失败的类别，只说「哪一类」，具体原因交给 [ImChannelDiagnostic.reason]。 */
enum class ImFailureKind {
    NETWORK,
    TIMEOUT,
    HTTP,
    CREDENTIAL,
    PLATFORM,
    UNKNOWN,
}

/**
 * 一次失败的诊断。
 *
 * 四个字段各管一件事：[kind] 说哪一类、[stage] 说哪一步、[reason] 说底层真原因、
 * [referenceId] 给日志和用户一个共同锚点。**给用户的建议文案不放这里**——那要按语种出，
 * 由界面按 [stage] 映射成资源，领域层只交代事实。
 *
 * 不解析异常文本猜结论：HTTP 403 不翻译成「登录失效」，超时不猜测「对方拒绝」。
 */
data class ImChannelDiagnostic(
    val kind: ImFailureKind,
    val stage: ImStage,
    val reason: String,
    val referenceId: String,
) {
    companion object {

        /** 进程内自增，给参考号一个不会重复的尾数；只用于排障对账，不具备安全含义。 */
        private var sequence: Int = 0

        /**
         * 把异常归一成诊断。
         *
         * [redact] 由渠道提供，负责抹掉 token 一类的敏感串——诊断要能贴到聊天里求助，
         * 所以出这一层之前必须已经脱敏。
         */
        fun of(
            channelId: String,
            stage: ImStage,
            error: Throwable,
            redact: (String) -> String,
        ): ImChannelDiagnostic {
            val kind = when (error) {
                is SocketTimeoutException -> ImFailureKind.TIMEOUT
                is UnknownHostException, is ConnectException -> ImFailureKind.NETWORK
                else -> ImFailureKind.UNKNOWN
            }
            val detail = error.message?.takeIf { it.isNotBlank() }?.let { redact(it).take(120) }
            val reason = if (detail == null) error.javaClass.simpleName else "${error.javaClass.simpleName}: $detail"
            return ImChannelDiagnostic(kind, stage, reason, referenceId(channelId))
        }

        private fun referenceId(channelId: String): String {
            val tag = channelId.uppercase().filter { it.isLetterOrDigit() }.take(10).ifEmpty { "IM" }
            val tail = synchronized(this) {
                sequence = (sequence + 1) and 0xFFFF
                sequence
            }
            return "IM-$tag-${tail.toString(16).uppercase().padStart(4, '0')}"
        }
    }
}
