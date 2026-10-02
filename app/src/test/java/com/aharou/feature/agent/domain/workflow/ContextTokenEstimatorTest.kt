package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.aharou.feature.settings.domain.model.ModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextTokenEstimatorTest {
    @Test
    fun newlyAppendedToolOutputCountsAgainstPreviousUsage() {
        val before = listOf(AgentMessage.UserMessage(content = "request"))
        val after = before + AgentMessage.ToolResultMessage(id = "call", toolName = "readFile", result = "汉".repeat(25_000))
        val baseline = ContextTokenEstimator.estimate("system", before, emptyList())
        val current = ContextTokenEstimator.estimate("system", after, emptyList())
        assertTrue(ContextTokenEstimator.calibrated(current, baseline, 110_000) >= 135_000)
    }

    @Test
    fun inputLimitOverridesLargerSharedContext() {
        assertEquals(272_000, ModelContextPolicy.effectiveInputBudget(
            ModelMetadata(id = "model", contextTokens = 400_000, inputTokens = 272_000, outputTokens = 128_000)))
    }

    @Test
    fun contextReservesOutputSpace() {
        val metadata = ModelMetadata(id = "model", contextTokens = 128_000)
        assertEquals(8_192, ModelContextPolicy.outputReserveTokens(metadata))
        assertEquals(119_808, ModelContextPolicy.effectiveInputBudget(metadata))
    }

    @Test
    fun chineseIsNotCountedAsFourCharactersPerToken() {
        assertEquals(100, ModelContextPolicy.estimateTextTokens("汉".repeat(100)))
        assertEquals(25, ModelContextPolicy.estimateTextTokens("a".repeat(100)))
        assertEquals(536_870_912, ModelContextPolicy.estimateTokens(Int.MAX_VALUE))
    }
}
