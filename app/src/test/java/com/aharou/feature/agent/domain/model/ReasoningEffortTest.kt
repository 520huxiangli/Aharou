package com.aharou.feature.agent.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ReasoningEffortTest {

    @Test
    fun fromValues_parsesKnownEfforts() {
        assertEquals(
            listOf(
                ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM,
                ReasoningEffort.HIGH, ReasoningEffort.XHIGH, ReasoningEffort.MAX
            ),
            ReasoningEffort.fromValues(listOf("none", "low", "medium", "high", "xhigh", "max"))
        )
    }

    @Test
    fun fromValues_ignoresDefaultAndUnknown() {
        assertEquals(
            listOf(ReasoningEffort.NONE, ReasoningEffort.LOW),
            ReasoningEffort.fromValues(listOf("none", "default", "minimal", "low"))
        )
    }

    @Test
    fun fromValues_keepsValuesOrder() {
        assertEquals(
            listOf(ReasoningEffort.MAX, ReasoningEffort.LOW),
            ReasoningEffort.fromValues(listOf("max", "low"))
        )
    }

    @Test
    fun fromValues_filtersUnknown() {
        assertEquals(
            listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
            ReasoningEffort.fromValues(listOf("low", "ultra", "high"))
        )
    }

    @Test
    fun fromValues_empty() {
        assertEquals(emptyList<ReasoningEffort>(), ReasoningEffort.fromValues(emptyList()))
    }
}