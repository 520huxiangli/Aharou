package com.aharou.feature.im.domain

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImChannelDiagnosticTest {

    private val secret = "8888888888:AAtoken-value"

    /** 与渠道实现同形的脱敏：把 token 换成 ***。 */
    private val redact: (String) -> String = { it.replace(secret, "***") }

    private fun of(
        error: Throwable,
        channelId: String = "telegram",
        stage: ImStage = ImStage.SEND,
    ): ImChannelDiagnostic = ImChannelDiagnostic.of(channelId, stage, error, redact)

    @Test
    fun socketTimeout_mapsToTimeout() {
        val d = of(SocketTimeoutException("read timed out"))

        assertEquals("读超时应归为 TIMEOUT，实际是 ${d.kind}", ImFailureKind.TIMEOUT, d.kind)
    }

    @Test
    fun connectRefused_mapsToNetwork() {
        val d = of(ConnectException("Connection refused"))

        assertEquals("连接被拒应归为 NETWORK，实际是 ${d.kind}", ImFailureKind.NETWORK, d.kind)
    }

    @Test
    fun unknownHost_mapsToNetwork() {
        val d = of(UnknownHostException("api.example.invalid"))

        assertEquals("域名解析失败应归为 NETWORK，实际是 ${d.kind}", ImFailureKind.NETWORK, d.kind)
    }

    @Test
    fun otherException_mapsToUnknown() {
        val d = of(IllegalStateException("unexpected state"))

        assertEquals("未归类的异常应归为 UNKNOWN，实际是 ${d.kind}", ImFailureKind.UNKNOWN, d.kind)
    }

    @Test
    fun ioExceptionWithoutKnownType_mapsToUnknown() {
        val d = of(IOException("unexpected end of stream"))

        assertEquals("普通 IO 异常没有专门归类，应为 UNKNOWN，实际是 ${d.kind}", ImFailureKind.UNKNOWN, d.kind)
    }

    @Test
    fun stage_isKept() {
        val d = of(IOException("boom"), stage = ImStage.CONNECT)

        assertEquals("诊断应保留传入的阶段 CONNECT，实际是 ${d.stage}", ImStage.CONNECT, d.stage)
    }

    @Test
    fun reason_containsClassNameAndRedactedMessage() {
        val d = of(RuntimeException("auth failed: $secret"))

        assertTrue("reason 应包含异常类名 RuntimeException，实际是 ${d.reason}", d.reason.contains("RuntimeException"))
        assertTrue("reason 里应出现脱敏后的 ***，实际是 ${d.reason}", d.reason.contains("***"))
        assertFalse("reason 不应残留原始 token，实际是 ${d.reason}", d.reason.contains(secret))
    }

    @Test
    fun reason_withoutMessage_isClassNameOnly() {
        val missing = of(SocketTimeoutException())
        val blank = of(SocketTimeoutException("   "))

        assertEquals("没有 message 时 reason 只留类名，实际是 ${missing.reason}", "SocketTimeoutException", missing.reason)
        assertEquals("message 全空白时同样只留类名，实际是 ${blank.reason}", "SocketTimeoutException", blank.reason)
    }

    @Test
    fun reason_truncatesMessageTo120Chars() {
        val d = of(IOException("x".repeat(200)))

        val detail = d.reason.substringAfter(": ")
        assertEquals("reason 的正文应截断到 120 字符，实际长度 ${detail.length}", 120, detail.length)
    }

    @Test
    fun referenceId_hasChannelTagAndHexTail() {
        val d = of(IOException("boom"), channelId = "telegram")

        assertTrue("参考号应以 IM-TELEGRAM- 开头且尾号是 4 位十六进制，实际是 ${d.referenceId}",
            Regex("^IM-TELEGRAM-[0-9A-F]{4}$").matches(d.referenceId))
    }

    @Test
    fun referenceId_sanitizesChannelId() {
        val napcat = of(IOException("boom"), channelId = "qq_napcat")
        val blank = of(IOException("boom"), channelId = "---")

        assertTrue("渠道名应去掉下划线并转大写，实际是 ${napcat.referenceId}",
            napcat.referenceId.startsWith("IM-QQNAPCAT-"))
        assertTrue("渠道名没有可用字符时前缀退回 IM，实际是 ${blank.referenceId}",
            blank.referenceId.startsWith("IM-IM-"))
    }

    @Test
    fun referenceId_differsBetweenCalls() {
        val first = of(IOException("boom"), channelId = "feishu")
        val second = of(IOException("boom"), channelId = "feishu")

        assertNotEquals("同一渠道连续两次诊断的参考号不应相同，实际两次都是 ${first.referenceId}",
            first.referenceId, second.referenceId)
    }
}
