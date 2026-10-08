package com.aharou.feature.agent.domain.workflow

import org.junit.Assert.assertEquals
import org.junit.Test

class SamplingLoopStartTest {

    @Test
    fun plainTextIsNotFlagged() {
        val text = "这是一段正常的中文回复，讨论天气与出行安排，句子之间没有周期性结构，" +
            "内容自然流畅，也不存在成段的自我重复。"
        assertEquals(-1, samplingLoopStart(text))
    }

    @Test
    fun shortUnitAtThresholdIsCutToFirstOccurrence() {
        // 短单元（≤ 16 字符）的门槛是连续重复 20 次
        val text = "AB".repeat(20)
        assertEquals(2, samplingLoopStart(text))
        assertEquals("AB", text.take(samplingLoopStart(text)))
    }

    @Test
    fun shortUnitOneRepeatShortOfThresholdIsNotFlagged() {
        assertEquals(-1, samplingLoopStart("AB".repeat(19)))
    }

    @Test
    fun longUnitIsFlaggedByRepeatedVolume() {
        val unit = "0123456789abcdefghijklmnopqrstuv"
        // 长单元不看次数，看重复总量是否够 1024 字符
        val text = unit.repeat(1024 / unit.length)
        assertEquals(unit.length, samplingLoopStart(text))
    }

    @Test
    fun longUnitBelowVolumeThresholdIsNotFlagged() {
        val unit = "0123456789abcdefghijklmnopqrstuv"
        assertEquals(-1, samplingLoopStart(unit.repeat(2)))
    }

    @Test
    fun longestUnitCandidateIsFlagged() {
        val unit = buildString { repeat(64) { append(it.toString().padStart(8, '0')) } }
        assertEquals(512, unit.length)
        assertEquals(512, samplingLoopStart(unit.repeat(3)))
    }

    @Test
    fun runsOfOneCharacterAreNotFlagged() {
        // `----` / `====` 这类分隔线满足任意周期的重复条件，必须靠全同字符排除掉
        assertEquals(-1, samplingLoopStart("-".repeat(100)))
        assertEquals(-1, samplingLoopStart("=".repeat(100)))
    }

    @Test
    fun cutBacktracksToFirstOccurrence() {
        val text = "HEAD" + "AB".repeat(30)
        assertEquals(6, samplingLoopStart(text))
        assertEquals("HEADAB", text.take(samplingLoopStart(text)))
    }

    @Test
    fun structuredOutputIsNotFlagged() {
        val table = buildString { repeat(40) { append("| 项目$it | 值$it |\n") } }
        assertEquals(-1, samplingLoopStart(table))
    }

    @Test
    fun blankAndShortInputAreNotFlagged() {
        assertEquals(-1, samplingLoopStart(""))
        assertEquals(-1, samplingLoopStart("短"))
    }
}
