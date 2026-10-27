package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长内容分块与行封顶（[boundedTextChunks] / [capLineLengths] / [formatOutputSize]）。
 *
 * 这几条是「超长工具输出不把界面卡死」的底座：分块只记边界不复制文本，所以要保证区间
 * 首尾相接、不重叠、拼回来等于原文；单行必须被硬切，否则一条几十万字符的行会把 Text 测量拖死。
 */
class OutputChunkingTest {

    @Test
    fun empty_text_has_no_chunks() {
        assertTrue(boundedTextChunks("", 100, 10).isEmpty())
    }

    @Test
    fun short_text_is_one_chunk_covering_everything() {
        val text = "hello\nworld"
        val chunks = boundedTextChunks(text, 4000, 40)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks.single().first)
        assertEquals(text.length - 1, chunks.single().last)
    }

    @Test
    fun long_single_line_is_hard_cut_within_limit() {
        val text = "x".repeat(10_000)
        val chunks = boundedTextChunks(text, 4000, 40)
        assertTrue("至少要切成多块", chunks.size > 1)
        chunks.forEach { assertTrue("每块不超过上限", it.last - it.first + 1 <= 4000) }
    }

    @Test
    fun chunks_are_contiguous_and_cover_full_text() {
        val text = buildString {
            repeat(500) { appendLine("line $it 内容") }
        }
        val chunks = boundedTextChunks(text, 300, 20)
        assertEquals(0, chunks.first().first)
        assertEquals(text.length - 1, chunks.last().last)
        chunks.zipWithNext().forEach { (a, b) ->
            assertEquals("块之间必须紧邻、不留缝", a.last + 1, b.first)
        }
        val rebuilt = chunks.joinToString("") { text.substring(it.first, it.last + 1) }
        assertEquals(text, rebuilt)
    }

    @Test
    fun surrogate_pairs_are_not_split() {
        // emoji 是 UTF-16 代理对；在代理对中间切开会得到乱码
        val text = "😀".repeat(3_000)
        val chunks = boundedTextChunks(text, 1_001, 40)
        chunks.forEach { range ->
            val start = text[range.first]
            val end = text[range.last]
            assertTrue("块首不能是低代理位", !start.isLowSurrogate())
            assertTrue("块尾不能是高代理位", !end.isHighSurrogate())
        }
        assertEquals(text, chunks.joinToString("") { text.substring(it.first, it.last + 1) })
    }

    @Test
    fun cap_line_lengths_keeps_short_text_untouched() {
        val text = "短行\n也短"
        assertEquals(text, capLineLengths(text, 100))
    }

    @Test
    fun cap_line_lengths_truncates_only_long_lines() {
        val longLine = "a".repeat(50)
        val text = "short\n$longLine\nshort"
        val capped = capLineLengths(text, 20)
        capped.lineSequence().forEach { assertTrue("每行不超过上限", it.length <= 21) }
        assertTrue("短行要保留", capped.contains("short"))
    }

    @Test
    fun format_output_size_scales_by_magnitude() {
        assertEquals("0 B", formatOutputSize(0))
        assertEquals("999 B", formatOutputSize(999))
        assertEquals("1 KB", formatOutputSize(1_200))
        assertEquals("1.2 MB", formatOutputSize(1_200_000))
    }
}
