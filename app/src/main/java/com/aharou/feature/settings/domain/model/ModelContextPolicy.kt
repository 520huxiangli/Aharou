package com.aharou.feature.settings.domain.model

object ModelContextPolicy {
    const val DEFAULT_CONTEXT_TOKENS = 128_000
    const val MIN_PRESERVE_RECENT_TOKENS = 2_000
    const val MAX_PRESERVE_RECENT_TOKENS = 20_000
    const val CHARS_PER_TOKEN = 4

    fun preserveRecentTokens(usableTokens: Int): Int =
        (usableTokens / 4).coerceIn(MIN_PRESERVE_RECENT_TOKENS, MAX_PRESERVE_RECENT_TOKENS)

    fun estimateTokens(chars: Int): Int =
        (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
}

