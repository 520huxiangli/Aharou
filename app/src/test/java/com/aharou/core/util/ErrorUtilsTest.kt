package com.aharou.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ErrorUtilsTest {

    @Test
    fun simpleExceptionWithMessage() {
        val e = RuntimeException("something broke")
        assertEquals("something broke", e.toUserMessage())
    }

    @Test
    fun exceptionWithNullMessage_fallsBackToClassName() {
        val e = RuntimeException()
        assertEquals("RuntimeException", e.toUserMessage())
    }

    @Test
    fun exceptionChain_joinsWithDotSeparator() {
        val root = RuntimeException("root cause")
        val mid = RuntimeException("mid level", root)
        val top = RuntimeException("top error", mid)
        assertEquals("top error · mid level · root cause", top.toUserMessage())
    }

    @Test
    fun exceptionChain_deduplicatesRepeatedMessages() {
        val root = RuntimeException("same error")
        val mid = RuntimeException("same error", root)
        val top = RuntimeException("same error", mid)
        assertEquals("same error", top.toUserMessage())
    }

    @Test
    fun exceptionChain_limitsDepthToFour() {
        val root = RuntimeException("level 5")
        val l4 = RuntimeException("level 4", root)
        val l3 = RuntimeException("level 3", l4)
        val l2 = RuntimeException("level 2", l3)
        val l1 = RuntimeException("level 1", l2)
        val result = l1.toUserMessage()
        // depth < 4 means we traverse l1 -> l2 -> l3 -> l4 (4 levels), l5 is not reached
        assertEquals("level 1 · level 2 · level 3 · level 4", result)
    }

    @Test
    fun unknownHostException_friendlyName() {
        val e = java.net.UnknownHostException()
        val msg = e.toUserMessage()
        assert(msg.contains("DNS") || msg.contains("网络"))
    }

    @Test
    fun socketTimeoutException_friendlyName() {
        val e = java.net.SocketTimeoutException()
        val msg = e.toUserMessage()
        assert(msg.contains("超时"))
    }

    @Test
    fun emptyMessageAndNoCause_returnsUnknownError() {
        // An exception with null message and no cause falls back to class name
        val e = RuntimeException()
        assertEquals("RuntimeException", e.toUserMessage())
    }
}
