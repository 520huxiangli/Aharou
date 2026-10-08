package com.aharou.feature.voice.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SpeechQueue 的静态切句逻辑（`companion object`）：把流式文本切成一段段可送云端合成的句子。
 *
 * 只测切句——它是纯字符串逻辑，与播放器、协程无关。SpeechQueue 实例本身依赖 MediaPlayer/TTS
 * 等 Android 运行时，纯 JVM 下不构造，也不覆盖其中断/播报流程。
 *
 * 切句不准的代价很实在：切太碎会碎念，切太大会被云端 200 字上限静默截断，所以边界都值得钉死。
 */
class SpeechQueueTest {

    @Test
    fun splitCompleteReturnsOnlyFinishedSentences() {
        // 末尾「走吧」没有句末标点，属于「还没说完」，不切出去
        assertEquals(
            listOf("你好。", "天气！"),
            SpeechQueue.splitComplete("你好。天气！走吧"),
        )
    }

    @Test
    fun splitCompleteReturnsEmptyWhenNoSentenceEnding() {
        assertEquals(emptyList<String>(), SpeechQueue.splitComplete("你好世界"))
    }

    @Test
    fun splitCompleteReturnsEmptyForBlankText() {
        assertEquals(emptyList<String>(), SpeechQueue.splitComplete(""))
        assertEquals(emptyList<String>(), SpeechQueue.splitComplete("   "))
    }

    @Test
    fun splitCompleteTreatsConsecutivePunctuationAsOneBoundary() {
        assertEquals(listOf("真的吗？！"), SpeechQueue.splitComplete("真的吗？！好"))
    }

    @Test
    fun splitCompleteDoesNotSplitDecimalNumbers() {
        // 小数点夹在数字之间不是句末，3.14 要整段留在一起
        assertEquals(listOf("价格3.14元。"), SpeechQueue.splitComplete("价格3.14元。好"))
    }

    @Test
    fun splitCompleteTrimsSurroundingWhitespace() {
        assertEquals(listOf("你好。"), SpeechQueue.splitComplete("  你好。  "))
    }

    @Test
    fun splitCompleteTreatsNewlineAsSentenceEnding() {
        assertEquals(listOf("第一行", "第二行。"), SpeechQueue.splitComplete("第一行\n第二行。"))
    }

    @Test
    fun splitCompleteHardCutsOverLongTextWithoutBreakpoints() {
        // 超过上限又没有句末标点、没有软断点：硬切到 50 字，不切会被云端截断
        val text = "a".repeat(60)
        val segments = SpeechQueue.splitComplete(text)
        assertEquals(1, segments.size)
        assertEquals(SpeechQueue.MAX_SEGMENT_LENGTH, segments[0].length)
        assertEquals("a".repeat(50), segments[0])
    }

    @Test
    fun splitCompletePrefersSoftBreakBeforeHardCut() {
        // 上限内出现「，」时退到软断点，而不是数到第 50 个字符硬切
        val text = "a".repeat(10) + "，" + "b".repeat(60)
        val segments = SpeechQueue.splitComplete(text)
        assertEquals(2, segments.size)
        assertEquals("a".repeat(10) + "，", segments[0])
        assertEquals("b".repeat(50), segments[1])
    }

    @Test
    fun maxSegmentLengthIsFifty() {
        assertEquals(50, SpeechQueue.MAX_SEGMENT_LENGTH)
    }

    @Test
    fun sentenceEndingsCoverBothWidths() {
        val endings = SpeechQueue.SENTENCE_ENDINGS
        assertEquals(10, endings.size)
        for (c in listOf('。', '！', '？', '；', '…', '\n', '!', '?', ';', ':')) {
            assertTrue("句末标点缺 '$c'", c in endings)
        }
    }

    @Test
    fun softBreaksIncludeCommaAndSpace() {
        val soft = SpeechQueue.SOFT_BREAKS
        assertEquals(5, soft.size)
        for (c in listOf('，', '、', ',', ' ', '—')) {
            assertTrue("软断点缺 '$c'", c in soft)
        }
    }
}
