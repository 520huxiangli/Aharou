package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 单条消息的缓存命中率展示（[formatCacheHitRate]）。
 */
class CacheHitRateTest {

    @Test
    fun ratio_of_cached_over_total_input() {
        assertEquals("80%", formatCacheHitRate(inputTokens = 1_000, cachedInputTokens = 800))
        assertEquals("50%", formatCacheHitRate(inputTokens = 2_000, cachedInputTokens = 1_000))
    }

    @Test
    fun rounds_to_whole_percent() {
        assertEquals("67%", formatCacheHitRate(inputTokens = 3, cachedInputTokens = 2))
        assertEquals("33%", formatCacheHitRate(inputTokens = 3, cachedInputTokens = 1))
    }

    @Test
    fun anthropic_style_input_without_cache_read_is_capped_at_full() {
        // Anthropic 的 input_tokens 不含 cache_read，比值会超过 1，展示封顶 100%
        assertEquals("100%", formatCacheHitRate(inputTokens = 100, cachedInputTokens = 5_000))
    }

    @Test
    fun no_cache_hit_or_no_usage_shows_nothing() {
        assertNull(formatCacheHitRate(inputTokens = 1_000, cachedInputTokens = 0))
        assertNull(formatCacheHitRate(inputTokens = 0, cachedInputTokens = 0))
        assertNull(formatCacheHitRate(inputTokens = 0, cachedInputTokens = 500))
    }
}
