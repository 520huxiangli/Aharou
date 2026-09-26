package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 流式文本「同一轮延续」判据：切页 / item 回收重挂载后据此决定恢复出的打字进度与
 * 思考计时能否沿用，故重点覆盖换轮（文本变短、开头改变、流已中断）等不可沿用的情形。
 */
class StreamContinuityTest {

    private fun seen(text: String): Pair<Int, Int> = text.length to streamHeadFingerprint(text)

    @Test
    fun prefixGrowth_isContinuation() {
        val (chars, head) = seen("你好，我先看一下")
        assertTrue(isStreamContinuation("你好，我先看一下项目结构", chars, head))
    }

    @Test
    fun unchangedText_isContinuation() {
        val text = "已输出的一段回复"
        val (chars, head) = seen(text)
        assertTrue(isStreamContinuation(text, chars, head))
    }

    @Test
    fun shorterText_isNotContinuation() {
        val (chars, head) = seen("上一轮已经输出了很长一段内容")
        assertFalse(isStreamContinuation("新一轮刚开始", chars, head))
    }

    @Test
    fun differentHeadSameLength_isNotContinuation() {
        val (chars, head) = seen("上一轮的开头")
        assertFalse(isStreamContinuation("另一轮的开头", chars, head))
    }

    @Test
    fun emptyText_isNotContinuation() {
        val (chars, head) = seen("流被中断前已输出的内容")
        assertFalse(isStreamContinuation("", chars, head))
    }

    @Test
    fun nothingSeenYet_isNotContinuation() {
        assertFalse(isStreamContinuation("刚开始输出", 0, 0))
        assertFalse(isStreamContinuation("", 0, 0))
    }

    @Test
    fun growthBeyondSampleWindow_isContinuation() {
        // 已见文本超过采样窗口后继续增长：只比对前 64 字符，仍判为同一轮延续
        val seenText = "x".repeat(80) + "上一段尾部"
        val (chars, head) = seen(seenText)
        assertTrue(isStreamContinuation(seenText + "继续输出的新内容", chars, head))
    }

    @Test
    fun longTextWithDifferentHead_isNotContinuation() {
        val (chars, head) = seen("a".repeat(100))
        assertFalse(isStreamContinuation("b".repeat(120), chars, head))
    }

    @Test
    fun surrogatePairs_measuredInChars() {
        // 指纹与长度都按 char 计，emoji 的代理对不会让同一轮被判成换轮
        val (chars, head) = seen("进度🚀🚀")
        assertTrue(isStreamContinuation("进度🚀🚀继续", chars, head))
    }
}
