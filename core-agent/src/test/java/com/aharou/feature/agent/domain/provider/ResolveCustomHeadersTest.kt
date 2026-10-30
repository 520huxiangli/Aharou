package com.aharou.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolveCustomHeadersTest {

    @Test
    fun placeholders_replacedWithSessionIdAndApiKey() {
        val resolved = resolveCustomHeaders(
            mapOf("X-Session" to "{{SESSION_ID}}", "X-Auth" to "Bearer {{API_KEY}}"),
            sessionId = "sess-123",
            apiKey = "sk-abc"
        )

        assertEquals(
            mapOf("X-Session" to "sess-123", "X-Auth" to "Bearer sk-abc"),
            resolved
        )
    }

    @Test
    fun sessionPlaceholder_becomesEmptyWhenNoSession() {
        val resolved = resolveCustomHeaders(
            mapOf("X-Session" to "{{SESSION_ID}}"),
            sessionId = null,
            apiKey = "sk-abc"
        )

        assertEquals(mapOf("X-Session" to ""), resolved)
    }

    @Test
    fun headersWithoutPlaceholders_passThroughUnchanged() {
        val resolved = resolveCustomHeaders(
            mapOf("User-Agent" to "MyApp/1.0", "X-Static" to "v"),
            sessionId = "sess-1",
            apiKey = "sk-1"
        )

        assertEquals(mapOf("User-Agent" to "MyApp/1.0", "X-Static" to "v"), resolved)
    }

    @Test
    fun blankHeaderNames_droppedDefensively() {
        val resolved = resolveCustomHeaders(
            mapOf(" " to "x", "X-Keep" to "y"),
            sessionId = "sess-1",
            apiKey = "sk-1"
        )

        assertEquals(mapOf("X-Keep" to "y"), resolved)
    }

    @Test
    fun emptyCustomHeaders_returnsEmpty() {
        val resolved = resolveCustomHeaders(emptyMap(), sessionId = "s", apiKey = "k")

        assertEquals(emptyMap<String, String>(), resolved)
    }
}
