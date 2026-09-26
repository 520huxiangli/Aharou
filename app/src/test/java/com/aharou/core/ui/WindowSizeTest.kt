package com.aharou.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowSizeTest {

    @Test
    fun phonePortrait_isCompact() {
        assertEquals(WindowWidthClass.COMPACT, windowWidthClassOf(360))
        assertEquals(WindowWidthClass.COMPACT, windowWidthClassOf(411))
    }

    @Test
    fun compactUpperBound_isExclusive() {
        assertEquals(WindowWidthClass.COMPACT, windowWidthClassOf(599))
        assertEquals(WindowWidthClass.MEDIUM, windowWidthClassOf(600))
    }

    @Test
    fun tabletPortrait_isMedium() {
        assertEquals(WindowWidthClass.MEDIUM, windowWidthClassOf(800))
    }

    @Test
    fun mediumUpperBound_isExclusive() {
        assertEquals(WindowWidthClass.MEDIUM, windowWidthClassOf(839))
        assertEquals(WindowWidthClass.EXPANDED, windowWidthClassOf(840))
    }

    @Test
    fun tabletLandscape_isExpanded() {
        assertEquals(WindowWidthClass.EXPANDED, windowWidthClassOf(1280))
    }
}
