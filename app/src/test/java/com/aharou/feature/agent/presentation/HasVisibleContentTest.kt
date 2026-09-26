package com.aharou.feature.agent.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HasVisibleContentTest {

    @Test
    fun emptyString_hasNoVisibleContent() {
        assertFalse("".hasVisibleContent())
    }

    @Test
    fun whitespaceOnly_hasNoVisibleContent() {
        assertFalse(" ".hasVisibleContent())
        assertFalse("\t\n\r".hasVisibleContent())
        assertFalse("   ".hasVisibleContent())
    }

    @Test
    fun normalText_hasVisibleContent() {
        assertTrue("hello".hasVisibleContent())
        assertTrue("hello world".hasVisibleContent())
        assertTrue("a".hasVisibleContent())
    }

    @Test
    fun zeroWidthChars_haveNoVisibleContent() {
        // U+200B zero-width space, U+200C ZWNJ, U+200D ZWJ
        assertFalse("\u200B".hasVisibleContent())
        assertFalse("\u200C".hasVisibleContent())
        assertFalse("\u200D".hasVisibleContent())
        assertFalse("\u200B\u200C\u200D".hasVisibleContent())
    }

    @Test
    fun hangulFiller_hasNoVisibleContent() {
        // U+3164 Hangul filler — the original bug that caused empty bubbles
        assertFalse("\u3164".hasVisibleContent())
        assertFalse("\u115F".hasVisibleContent())
        assertFalse("\u1160".hasVisibleContent())
        assertFalse("\uFFA0".hasVisibleContent())
    }

    @Test
    fun brailleBlank_hasNoVisibleContent() {
        // U+2800 Braille pattern blank
        assertFalse("\u2800".hasVisibleContent())
    }

    @Test
    fun bom_hasNoVisibleContent() {
        // U+FEFF BOM
        assertFalse("\uFEFF".hasVisibleContent())
    }

    @Test
    fun mixedVisibleAndInvisible_hasVisibleContent() {
        // Even one visible char among invisible ones is enough
        assertTrue("a\u200B".hasVisibleContent())
        assertTrue("\u3164hello\u3164".hasVisibleContent())
        assertTrue("\uFEFFx".hasVisibleContent())
    }

    @Test
    fun onlyInvisible_hasNoVisibleContent() {
        assertFalse("\u200B\u3164\u2800\uFEFF".hasVisibleContent())
    }

    @Test
    fun controlChars_haveNoVisibleContent() {
        assertFalse("\u0000".hasVisibleContent())
        assertFalse("\u001F".hasVisibleContent())
    }

    @Test
    fun textWithWhitespaceAndVisible_hasVisibleContent() {
        assertTrue("  hello  ".hasVisibleContent())
        assertTrue("\n\ttest\n".hasVisibleContent())
    }
}
