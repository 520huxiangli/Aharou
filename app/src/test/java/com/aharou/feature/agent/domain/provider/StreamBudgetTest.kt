package com.aharou.feature.agent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class StreamBudgetTest {

    @Test
    fun underLimit_doesNotThrow() {
        val budget = StreamBudget(maxChars = 10)

        budget.add("12345")
        budget.add("67890")
    }

    @Test
    fun emptyDelta_isIgnored() {
        val budget = StreamBudget(maxChars = 0)

        budget.add("")
    }

    @Test
    fun exceedingLimit_throwsNonRetryableStreamApiException() {
        val budget = StreamBudget(maxChars = 10)

        val e = assertThrows(StreamApiException::class.java) { budget.add("12345678901") }

        assertEquals("response_too_large", e.code)
        assertFalse(isRetriableNetworkError(e))
    }

    @Test
    fun budgetIsAccumulatedAcrossCalls() {
        val budget = StreamBudget(maxChars = 10)
        budget.add("12345")

        assertThrows(StreamApiException::class.java) { budget.add("123456") }
    }
}
