package com.aharou.feature.voice.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VoiceWakeWord：唤醒词常量与传给 sherpa KWS 的关键词行。
 *
 * 关键词行格式是「逐 token 拼音 + 空格 + @显示名」，token 必须与模型 tokens.txt 一一对应——
 * 少一个 token，KeywordSpotter.createStream 会直接失败。这里锁住词序与格式，改唤醒词时能立刻发现。
 */
class VoiceWakeWordTest {

    @Test
    fun displayIsTheConfiguredWakeWord() {
        assertEquals("小染小染", VoiceWakeWord.DISPLAY)
    }

    @Test
    fun keywordsLineEndsWithAtDisplayName() {
        assertTrue(VoiceWakeWord.KEYWORDS.endsWith("@${VoiceWakeWord.DISPLAY}"))
    }

    @Test
    fun keywordsUseEightPinyinTokensForXiaoRan() {
        // 小 染 小 染 → x / iǎo / r / ǎn ×2，共 8 个 token，末尾是 @显示名
        val parts = VoiceWakeWord.KEYWORDS.split(" ")
        assertEquals(9, parts.size)
        assertEquals("@${VoiceWakeWord.DISPLAY}", parts.last())
        assertEquals(listOf("x", "iǎo", "r", "ǎn", "x", "iǎo", "r", "ǎn"), parts.dropLast(1))
    }

    @Test
    fun keywordsMatchExpectedTemplate() {
        assertEquals("x iǎo r ǎn x iǎo r ǎn @小染小染", VoiceWakeWord.KEYWORDS)
    }

    @Test
    fun keywordsHaveNoStrayWhitespace() {
        assertEquals(VoiceWakeWord.KEYWORDS, VoiceWakeWord.KEYWORDS.trim())
        assertTrue("关键词行含连续空格", !VoiceWakeWord.KEYWORDS.contains("  "))
    }

    @Test
    fun thresholdIsWithinUnitInterval() {
        assertTrue(VoiceWakeWord.THRESHOLD > 0f)
        assertTrue(VoiceWakeWord.THRESHOLD <= 1f)
    }

    @Test
    fun scoreIsPositive() {
        assertTrue(VoiceWakeWord.SCORE > 0f)
    }
}
