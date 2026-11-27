package com.aharou.feature.agent.domain.tool.container

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `terminal` 工具的输出去重游标：增量语义与边界（截断、清屏、标签复用）。
 */
class TerminalOutputCursorTest {

    private val cursor = TerminalOutputCursor()

    @Test
    fun firstRead_returnsWholeOutput() {
        assertEquals("first output", cursor.advance("term-1", "first output"))
    }

    @Test
    fun secondRead_returnsOnlyAppendedPart() {
        cursor.advance("term-1", "abc")
        assertEquals("XYZ", cursor.advance("term-1", "abcXYZ"))
    }

    @Test
    fun unchangedOutput_returnsEmpty() {
        cursor.advance("term-1", "same")
        assertEquals("", cursor.advance("term-1", "same"))
    }

    @Test
    fun truncatedTranscript_returnsOnlyNewSuffix() {
        // 超过屏幕缓冲行数后最旧的行被丢弃，previous 不再是 current 的前缀。
        cursor.advance("term-1", "line1\nline2\nline3")
        assertEquals("\nline4", cursor.advance("term-1", "line2\nline3\nline4"))
    }

    @Test
    fun clearedScreen_returnsWholeNewOutput() {
        cursor.advance("term-1", "old output")
        assertEquals("fresh", cursor.advance("term-1", "fresh"))
    }

    @Test
    fun mark_advancesCursorWithoutReturning() {
        cursor.mark("term-1", "initial")
        assertEquals("delta", cursor.advance("term-1", "initialdelta"))
    }

    @Test
    fun tabsAreIndependent() {
        cursor.advance("term-1", "a")
        assertEquals("b", cursor.advance("term-2", "b"))
    }

    @Test
    fun clear_makesNextReadFullAgain() {
        cursor.advance("term-1", "abc")
        cursor.clear("term-1")
        assertEquals("abcXYZ", cursor.advance("term-1", "abcXYZ"))
    }

    @Test
    fun retainOnly_dropsVanishedTabs() {
        cursor.advance("term-1", "a")
        cursor.advance("term-2", "b")
        cursor.retainOnly(setOf("term-2"))
        assertEquals("a2", cursor.advance("term-1", "a2"))
    }

    @Test
    fun outputDelta_usesLongestOverlap() {
        assertEquals("c", outputDelta("ababa", "abac"))
    }

    @Test
    fun outputDelta_emptyCurrent_returnsEmpty() {
        assertEquals("", outputDelta("previous output", ""))
    }
}
