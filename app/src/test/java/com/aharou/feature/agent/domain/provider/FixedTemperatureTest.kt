package com.aharou.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 服务端固定采样温度的模型 → 请求该带哪个值（[fixedTemperature]）。
 */
class FixedTemperatureTest {

    @Test
    fun kimi_k2_thinking_models_use_one() {
        assertEquals(1.0f, fixedTemperature("kimi-k2-thinking"))
        assertEquals(1.0f, fixedTemperature("kimi-k2.5"))
        assertEquals(1.0f, fixedTemperature("kimi-k2.6"))
        assertEquals(1.0f, fixedTemperature("moonshotai/kimi-k2-5-turbo"))
    }

    @Test
    fun kimi_k2_non_thinking_models_use_zero_point_six() {
        assertEquals(0.6f, fixedTemperature("kimi-k2-0905-preview"))
        assertEquals(0.6f, fixedTemperature("kimi-k2-turbo-preview"))
    }

    @Test
    fun glm_and_minimax_use_one() {
        assertEquals(1.0f, fixedTemperature("glm-4.6"))
        assertEquals(1.0f, fixedTemperature("glm-4.7-flash"))
        assertEquals(1.0f, fixedTemperature("MiniMax-M2.5"))
    }

    @Test
    fun only_gemini_generations_with_official_defaults_use_one() {
        assertEquals(1.0f, fixedTemperature("gemini-2.5-pro"))
        assertEquals(1.0f, fixedTemperature("gemini-3-flash-preview"))
        assertEquals(1.0f, fixedTemperature("gemini-3.1-pro-preview"))
        assertNull(fixedTemperature("gemini-3.5-flash-lite"))
        assertNull(fixedTemperature("gemini-2.0-flash"))
    }

    /** 表外模型不带 temperature，交由服务端默认——kimi-k3 / gpt-5 系带任何值都会 400。 */
    @Test
    fun models_outside_the_table_send_nothing() {
        assertNull(fixedTemperature("kimi-k3"))
        assertNull(fixedTemperature("gpt-5.2"))
        assertNull(fixedTemperature("claude-opus-4-5"))
        assertNull(fixedTemperature("deepseek-chat"))
        assertNull(fixedTemperature("glm-5.3"))
    }
}
