package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTokenCountTest {

    @Test
    fun zero() {
        assertEquals("0", formatTokenCount(0))
    }

    @Test
    fun smallNumber() {
        assertEquals("42", formatTokenCount(42))
        assertEquals("500", formatTokenCount(500))
        assertEquals("999", formatTokenCount(999))
    }

    @Test
    fun exactlyOneThousand() {
        assertEquals("1.0k", formatTokenCount(1_000))
    }

    @Test
    fun thousands() {
        assertEquals("1.5k", formatTokenCount(1_500))
        assertEquals("9.9k", formatTokenCount(9_900))
        assertEquals("12.3k", formatTokenCount(12_300))
    }

    @Test
    fun exactlyOneMillion() {
        assertEquals("1.0M", formatTokenCount(1_000_000))
    }

    @Test
    fun millions() {
        assertEquals("1.5M", formatTokenCount(1_500_000))
        assertEquals("2.3M", formatTokenCount(2_300_000))
    }

    @Test
    fun boundary_999999() {
        assertEquals("1000.0k", formatTokenCount(999_999))
    }
}
