package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImUpdate
import io.mockk.mockk
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SECRET = "tok_abc123"

/**
 * 三态归类的验证。
 *
 * `ImBotBridge.sendSafely` 是 private，公开入口只把结果写日志、不返回，所以这里用反射直接调它：
 * 拿到的是生产代码里的那一份映射，比在测试里重抄一遍判定条件更可信。
 * 它的签名是 (ImChannelConfig, String, String, Continuation) —— 最后一个参数是协程续体。
 */
class ImSendResultTest {

    private class Cfg : ImChannelConfig {
        override val enabled: Boolean = true
        override val ready: Boolean = true
        override val proxy: String = ""
    }

    private class FakeChannel(
        private val reply: String = "",
        private val error: Throwable? = null,
    ) : ImChannel {
        override val id: String = "fake"

        override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity = ImBotIdentity(0L, "fake")

        override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> = emptyList()

        override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
            error?.let { throw it }
            return reply
        }

        override fun redact(text: String, cfg: ImChannelConfig): String = text.replace(SECRET, "***")
    }

    private val cfg = Cfg()

    private val settings = mockk<ImSettingsSource>(relaxed = true)
    private val runner = mockk<AgentTurnRunner>(relaxed = true)
    private val sessions = mockk<SessionUseCase>(relaxed = true)
    private val permissions = mockk<ToolPermissionManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val owners = mockk<ImOwnerStore>(relaxed = true)

    private fun bridge(channel: ImChannel): ImBotBridge =
        ImBotBridge("telegram", channel, settings, runner, sessions, permissions, context, owners)

    // 按名字找而不是按签名找：签名里带着协程续体，写死参数类型容易与编译器生成的方法对不上。
    private val sendSafely: Method = ImBotBridge::class.java.declaredMethods
        .first { it.name == "sendSafely" }
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun send(channel: ImChannel): ImSendResult = runBlocking {
        suspendCoroutineUninterceptedOrReturn<ImSendResult> { cont ->
            val raw = try {
                sendSafely.invoke(bridge(channel), cfg, "chat-1", "hello", cont)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (raw === COROUTINE_SUSPENDED) COROUTINE_SUSPENDED else raw as ImSendResult
        }
    }

    @Test
    fun successfulSend_returnsSentWithMessageId() {
        val result = send(FakeChannel(reply = "msg-42"))

        assertEquals("发送成功应得到 Sent(\"msg-42\")，实际是 $result", ImSendResult.Sent("msg-42"), result)
    }

    @Test
    fun successfulSendWithoutId_returnsSentWithEmptyId() {
        val result = send(FakeChannel(reply = ""))

        assertEquals("平台没给消息 id 时应得到 Sent(\"\")，实际是 $result", ImSendResult.Sent(""), result)
    }

    @Test
    fun socketTimeout_returnsUnknown() {
        val result = send(FakeChannel(error = SocketTimeoutException("read timed out")))
        val unknown = result as? ImSendResult.Unknown

        assertTrue("读超时可能已经送达，应归为 Unknown，实际是 $result", unknown != null)
        assertEquals("Unknown 应带上脱敏后的原始原因，实际是 ${unknown?.reason}", "read timed out", unknown?.reason)
    }

    @Test
    fun connectRefused_returnsFailed() {
        val result = send(FakeChannel(error = ConnectException("Connection refused")))
        val failed = result as? ImSendResult.Failed

        assertTrue("连接被拒说明没发出去，应归为 Failed，实际是 $result", failed != null)
        assertEquals("Failed 应带上脱敏后的原始原因，实际是 ${failed?.reason}", "Connection refused", failed?.reason)
    }

    @Test
    fun unknownHost_returnsFailed() {
        val result = send(FakeChannel(error = UnknownHostException("api.example.invalid")))
        val failed = result as? ImSendResult.Failed

        assertTrue("域名解析失败说明没发出去，应归为 Failed，实际是 $result", failed != null)
        assertEquals("Failed 应带上脱敏后的原始原因，实际是 ${failed?.reason}",
            "api.example.invalid", failed?.reason)
    }

    @Test
    fun otherIoException_returnsUnknown() {
        val result = send(FakeChannel(error = IOException("unexpected end of stream")))

        assertTrue("中途断连可能已发出半条，应归为 Unknown，实际是 $result", result is ImSendResult.Unknown)
    }

    @Test
    fun nonIoException_returnsFailed() {
        val result = send(FakeChannel(error = IllegalStateException("boom")))
        val failed = result as? ImSendResult.Failed

        assertTrue("非 IO 异常（平台明确拒收）应归为 Failed，实际是 $result", failed != null)
        assertEquals("Failed 应带上脱敏后的原始原因，实际是 ${failed?.reason}", "boom", failed?.reason)
    }

    @Test
    fun failureReason_isRedacted() {
        val result = send(FakeChannel(error = IOException("token=$SECRET rejected")))
        val reason = (result as ImSendResult.Unknown).reason

        assertTrue("失败原因应经过渠道脱敏，实际是 $reason", reason.contains("***"))
        assertFalse("原始 token 不应出现在失败原因里，实际是 $reason", reason.contains(SECRET))
    }

    @Test
    fun failureWithoutMessage_keepsEmptyReason() {
        val result = send(FakeChannel(error = ConnectException()))

        assertEquals("异常没有 message 时 reason 应为空串，实际是 $result", ImSendResult.Failed(""), result)
    }

    @Test
    fun cancellationException_isRethrown() {
        val thrown = try {
            send(FakeChannel(error = CancellationException("task stopped")))
            null
        } catch (e: CancellationException) {
            e
        }

        assertTrue("CancellationException 必须原样抛出而不是被归类成三态，实际没有抛出任何异常", thrown != null)
    }
}
