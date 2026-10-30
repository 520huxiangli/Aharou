package com.aharou.feature.agent.domain.provider

import com.aharou.feature.settings.domain.model.DEFAULT_KEY_SWITCH_STATUS_CODES
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * 多 Key 切换的失败判定（[Throwable.isKeySwitchFailure]）：默认码表、可配置覆盖与 cause 链追溯。
 */
class KeyFailureClassifierTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun default_codes_trigger_switch() {
        DEFAULT_KEY_SWITCH_STATUS_CODES.forEach { code ->
            assertTrue("HTTP $code 应触发切换", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun transient_codes_do_not_trigger_switch() {
        listOf(408, 500, 502, 503, 504).forEach { code ->
            assertFalse("HTTP $code 不应触发切换", httpError(code).isKeySwitchFailure())
        }
    }

    @Test
    fun custom_codes_override_default() {
        val custom = setOf(400, 418)
        assertTrue(httpError(400).isKeySwitchFailure(custom))
        assertFalse("自定义码表未含 401 时不应切换", httpError(401).isKeySwitchFailure(custom))
    }

    @Test
    fun stream_codes_and_message_fallback() {
        assertTrue(StreamApiException("insufficient_quota", "m").isKeySwitchFailure())
        assertTrue(StreamApiException("rate_limit_exceeded", "m").isKeySwitchFailure())
        assertTrue(IllegalStateException("HTTP 401 from upstream").isKeySwitchFailure())
        assertFalse(IllegalStateException("HTTP 500 Internal Server Error").isKeySwitchFailure())
    }

    @Test
    fun cause_chain_is_followed() {
        val wrapped = IllegalStateException("wrapped", httpError(403))
        assertTrue(wrapped.isKeySwitchFailure())
    }
}
