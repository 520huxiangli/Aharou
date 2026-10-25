package com.aharou.core.util

import android.content.Context
import com.aharou.R
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class ErrorUtilsTest {

    private val context = mockk<Context>(relaxed = true)

    init {
        // 归类文案来自 strings，这里只回固定标记，用来判断选了哪一类。
        every { context.getString(any()) } answers {
            when (firstArg<Int>()) {
                R.string.retry_error_dns_failed -> "DNS_CATEGORY"
                R.string.retry_error_connection_refused -> "REFUSED_CATEGORY"
                R.string.retry_error_timeout -> "TIMEOUT_CATEGORY"
                else -> "UNKNOWN_CATEGORY"
            }
        }
    }

    @Test
    fun simpleExceptionWithMessage() {
        val e = RuntimeException("something broke")
        assertEquals("something broke", e.toUserMessage(context))
    }

    @Test
    fun exceptionWithNullMessage_fallsBackToClassName() {
        val e = RuntimeException()
        assertEquals("RuntimeException", e.toUserMessage(context))
    }

    @Test
    fun exceptionChain_joinsWithDotSeparator() {
        val root = RuntimeException("root cause")
        val mid = RuntimeException("mid level", root)
        val top = RuntimeException("top error", mid)
        assertEquals("top error · mid level · root cause", top.toUserMessage(context))
    }

    @Test
    fun exceptionChain_deduplicatesRepeatedMessages() {
        val root = RuntimeException("same error")
        val mid = RuntimeException("same error", root)
        val top = RuntimeException("same error", mid)
        assertEquals("same error", top.toUserMessage(context))
    }

    @Test
    fun exceptionChain_limitsDepthToFour() {
        val root = RuntimeException("level 5")
        val l4 = RuntimeException("level 4", root)
        val l3 = RuntimeException("level 3", l4)
        val l2 = RuntimeException("level 2", l3)
        val l1 = RuntimeException("level 1", l2)
        // depth < 4 表示遍历 l1 -> l2 -> l3 -> l4，l5 不会被取到
        assertEquals("level 1 · level 2 · level 3 · level 4", l1.toUserMessage(context))
    }

    @Test
    fun unknownHost_isCategorizedAsDns() {
        val e = UnknownHostException("api.example.com")
        assertEquals(R.string.retry_error_dns_failed, e.networkCategoryRes())
        assertEquals("DNS_CATEGORY\napi.example.com", e.toUserMessage(context))
    }

    @Test
    fun connectException_isCategorizedAsRefused() {
        val e = ConnectException("Connection refused")
        assertEquals(R.string.retry_error_connection_refused, e.networkCategoryRes())
        assertEquals("REFUSED_CATEGORY\nConnection refused", e.toUserMessage(context))
    }

    @Test
    fun socketTimeout_isCategorizedAsTimeout() {
        val e = SocketTimeoutException("timeout")
        assertEquals(R.string.retry_error_timeout, e.networkCategoryRes())
        assertEquals("TIMEOUT_CATEGORY\ntimeout", e.toUserMessage(context))
    }

    @Test
    fun interruptedIo_isCategorizedAsTimeout() {
        val e = InterruptedIOException("interrupted")
        assertEquals(R.string.retry_error_timeout, e.networkCategoryRes())
    }

    @Test
    fun wrappedNetworkError_usesCauseChain() {
        // OkHttp 常把根因包在 cause 里，归类不能只看最外层
        val e = RuntimeException("request failed", UnknownHostException("api.example.com"))
        assertEquals(R.string.retry_error_dns_failed, e.networkCategoryRes())
    }

    @Test
    fun otherErrors_haveNoCategory() {
        val e = javax.net.ssl.SSLException("handshake failed")
        assertNull(e.networkCategoryRes())
        assertEquals("handshake failed", e.toUserMessage(context))
    }
}
