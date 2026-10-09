package com.aharou.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 20_000
    const val CHARS_PER_TOKEN = 4

    /**
     * 保留最近上下文的上限：写死 20K 对百万级窗口只占 2%，压缩时几乎全部内容都要挤进摘要请求。
     * 窗口足够大时放宽到窗口的十分之一，小窗口仍是 20K（行为不变）。
     */
    fun preserveRecentTokens(usableTokens: Int): Int =
        (usableTokens / 4).coerceIn(
            MIN_PRESERVE_RECENT_TOKENS,
            maxOf(MAX_PRESERVE_RECENT_TOKENS, usableTokens / 10)
        )

    fun estimateTokens(chars: Int): Int =
        chars / CHARS_PER_TOKEN + if (chars % CHARS_PER_TOKEN == 0) 0 else 1

    fun estimateTextTokens(text: String): Int {
        var ascii = 0
        var other = 0
        text.forEach { if (it.code < 128) ascii++ else other++ }
        return estimateTokens(ascii) + other
    }

    fun outputReserveTokens(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val reserve = (context / 10).coerceIn(1_024, 8_192).coerceAtMost(context / 4)
        return metadata.outputTokens?.takeIf { it > 0 }?.coerceAtMost(reserve) ?: reserve
    }

    fun effectiveInputBudget(metadata: ModelMetadata): Int {
        val context = metadata.contextTokens.takeIf { it > 0 } ?: DEFAULT_CONTEXT_TOKENS
        val shared = (context - outputReserveTokens(metadata)).coerceAtLeast(1)
        return metadata.inputTokens?.takeIf { it > 0 }?.coerceAtMost(shared) ?: shared
    }
}
