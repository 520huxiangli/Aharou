package com.aharou.feature.agent.domain.schedule.trigger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件载荷收敛：字段上限、总字节上限、触底停止，以及截断不劈开代理对。
 *
 * 覆盖的是投递侧唯一会把外部通知正文塞进提示词的地方——上游正文没有任何长度约束。
 */
class EventPayloadLimitsTest {

    private val mark = EventPayloadLimits.TRUNCATION_MARK

    @Test
    fun shortPayloadIsUntouched() {
        val payload = EventPayload(
            app = "com.tencent.mm",
            title = "张三",
            text = "在吗",
            batteryPercent = 42,
            ssid = "home",
            timeMillis = 1_700_000_000_000L,
        )

        val out = payload.limited()

        assertEquals("com.tencent.mm", out.app)
        assertEquals("张三", out.title)
        assertEquals("在吗", out.text)
        assertEquals(42, out.batteryPercent)
        assertEquals("home", out.ssid)
        assertEquals(1_700_000_000_000L, out.timeMillis)
        assertFalse(out.text.contains(mark))
    }

    @Test
    fun longTextIsTrimmedToFieldLimit() {
        val out = EventPayload(text = "字".repeat(2_000)).limited()

        assertTrue(out.text.length <= EventPayloadLimits.TEXT_MAX_CHARS)
        assertTrue(out.text.endsWith(mark))
    }

    @Test
    fun longTitleAndPackageAreTrimmed() {
        val out = EventPayload(app = "a".repeat(300), title = "标".repeat(500)).limited()

        assertTrue(out.app.length <= EventPayloadLimits.PACKAGE_MAX_CHARS)
        assertTrue(out.title.length <= EventPayloadLimits.TITLE_MAX_CHARS)
        assertTrue(out.app.endsWith(mark))
        assertTrue(out.title.endsWith(mark))
    }

    @Test
    fun exactLimitIsNotTrimmed() {
        val text = "x".repeat(EventPayloadLimits.TEXT_MAX_CHARS)
        val out = EventPayload(text = text).limited()

        assertEquals(text, out.text)
        assertFalse(out.text.contains(mark))
    }

    @Test
    fun fieldsStopAtMinimumWhenPayloadStaysOversized() {
        // ssid 不在字段上限之列，用它把整条载荷撑到砍完三个字段也放不进，验证循环会停下而不是砍成空
        val out = EventPayload(
            app = "a".repeat(200),
            title = "t".repeat(300),
            text = "x".repeat(2_000),
            ssid = "s".repeat(4_000),
        ).limited()

        assertTrue(out.app.startsWith("a"))
        assertTrue(out.text.startsWith("x"))
        assertTrue(out.app.length >= EventPayloadLimits.MIN_FIELD_CHARS)
        assertTrue(out.text.length >= EventPayloadLimits.MIN_FIELD_CHARS)
    }

    @Test
    fun trimmingDoesNotSplitSurrogatePair() {
        // 让截断位置正好落在表情的高低位之间：前面留 keep-1 个单字节字符，下一格就是表情
        val keep = EventPayloadLimits.TEXT_MAX_CHARS - mark.length
        val text = "a".repeat(keep - 1) + "😀" + "x".repeat(200)

        val out = EventPayload(text = text).limited()

        assertFalse(hasLoneSurrogate(out.text))
    }

    @Test
    fun emojiHeavyTextIsMeasuredInBytes() {
        // 表情按 UTF-8 是四字节：字符数没过字段上限，但体积远超按字符估算的量，总上限要兜住
        val out = EventPayload(text = "😀".repeat(900), title = "😀".repeat(120)).limited()

        assertFalse(hasLoneSurrogate(out.text))
        assertFalse(hasLoneSurrogate(out.title))
        assertTrue(out.text.length <= EventPayloadLimits.TEXT_MAX_CHARS)
    }

    private fun hasLoneSurrogate(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                Character.isHighSurrogate(c) -> {
                    if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return true
                    i += 2
                }
                Character.isLowSurrogate(c) -> return true
                else -> i++
            }
        }
        return false
    }
}
