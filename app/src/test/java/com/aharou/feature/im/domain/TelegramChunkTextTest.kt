package com.aharou.feature.im.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长回复的分片：Telegram 单条上限 4096，飞书也复用这套。
 *
 * 分片写错的表现是消息被平台整条拒收（400）或最后半句丢失，用户只看到「回复失败」，
 * 所以把边界条件逐个钉住：正好等于上限、只有换行、换行贴在切口上、全是长单词。
 */
class TelegramChunkTextTest {

    private fun chunk(text: String, limit: Int = 10) = TelegramBotClient.chunkText(text, limit)

    @Test
    fun blankInput_yieldsNoChunk() {
        assertEquals(emptyList<String>(), chunk(""))
        assertEquals(emptyList<String>(), chunk("   \n  "))
    }

    @Test
    fun shortText_staysSingleChunk() {
        assertEquals(listOf("hello"), chunk("hello"))
    }

    /** 边界：长度正好等于上限时不再切分，切了会多出一条几乎空的消息。 */
    @Test
    fun textOfExactLimit_isNotSplit() {
        assertEquals(listOf("0123456789"), chunk("0123456789"))
    }

    @Test
    fun longText_splitsAtNewline() {
        assertEquals(listOf("12345", "67890"), chunk("12345\n67890"))
    }

    @Test
    fun longTextWithoutNewline_hardCutsAtLimit() {
        assertEquals(listOf("0123456789", "AB"), chunk("0123456789AB"))
    }

    /** 换行落在切分点附近（早于半个上限）时宁可硬切，避免每片只有一两个字。 */
    @Test
    fun newlineTooEarly_isIgnored() {
        val chunks = chunk("1\n" + "x".repeat(20))

        assertEquals(3, chunks.size)
        assertEquals("1\nxxxxxxxx", chunks[0])
    }

    /** 切口正好压在换行上时，下一片以换行开头，要继续吃掉，别让每个分片都顶着一个空行。 */
    @Test
    fun newlineAtCutEdge_isTrimmedFromNextChunk() {
        val chunks = chunk("abcdefghij\nklmno")

        assertEquals(listOf("abcdefghij", "klmno"), chunks)
    }

    @Test
    fun everyChunkStaysWithinLimit() {
        val text = buildString {
            repeat(200) { append("行 $it 的内容，") }
        }

        val chunks = chunk(text, limit = 50)

        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 50 })
    }
}
