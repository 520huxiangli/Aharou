package com.aharou.core.util

import java.io.Reader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedLineReaderTest {

    private fun readAll(text: String, maxChars: Int = MAX_STREAM_LINE_CHARS): List<StreamLine> {
        BoundedLineReader(StringReader(text), maxChars).use { reader ->
            val out = mutableListOf<StreamLine>()
            while (true) {
                out.add(reader.readLine() ?: break)
            }
            return out
        }
    }

    @Test
    fun splitsOnLfCrLfAndCr() {
        val lines = readAll("a\nb\r\nc\rd")

        assertEquals(listOf("a", "b", "c", "d"), lines.map { it.text })
        assertFalse(lines.any { it.truncated })
    }

    @Test
    fun trailingNewlineDoesNotEmitExtraLine() {
        assertEquals(listOf("a"), readAll("a\n").map { it.text })
    }

    @Test
    fun emptyInputYieldsNoLines() {
        assertTrue(readAll("").isEmpty())
    }

    @Test
    fun lineBeyondMaxCharsIsTruncated() {
        val lines = readAll("x".repeat(100), maxChars = 10)

        assertEquals(1, lines.size)
        assertEquals(10, lines[0].text.length)
        assertTrue(lines[0].truncated)
    }

    @Test
    fun boundedLines_returnsAllLinesInOrder() {
        val text = (1..1000).joinToString("\n") { "line$it" }
        val lines = boundedLines { text.reader() }.toList()

        assertEquals(1000, lines.size)
        assertEquals("line1", lines.first())
        assertEquals("line1000", lines.last())
    }

    @Test
    fun boundedLines_readsOnlyWhatIsConsumed() {
        val reader = CountingReader("line\n".repeat(50_000))

        assertEquals("line", boundedLines { reader }.first())
        // 只取第一行就不该继续读：一次 read 只返回 1 个字符，读完 "line\n" 仅需 5 次左右
        assertTrue("reads=${reader.reads}", reader.reads <= 16)
    }

    /** 每次 [read] 只回一个字符，便于观察调用方到底消耗了多少输入。 */
    private class CountingReader(private val text: String) : Reader() {
        private var pos = 0
        var reads = 0
            private set

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            reads++
            if (pos >= text.length) return -1
            cbuf[off] = text[pos++]
            return 1
        }

        override fun close() = Unit
    }
}
