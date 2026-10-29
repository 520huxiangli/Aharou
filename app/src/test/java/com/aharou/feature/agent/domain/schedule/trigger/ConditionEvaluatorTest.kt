package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 条件求值：默认不限、时段窗口（含跨午夜与 00:00~00:00 的误填）、充电与电量三种条件。
 */
class ConditionEvaluatorTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun task(
        windowFrom: Int = -1,
        windowTo: Int = -1,
        requireCharging: Boolean = false,
        minBattery: Int = -1
    ) = ScheduledTaskEntity(
        id = "t1",
        name = "测试任务",
        prompt = "跑一轮",
        intervalMinutes = 15,
        conditionWindowFrom = windowFrom,
        conditionWindowTo = windowTo,
        condRequireCharging = requireCharging,
        condMinBattery = minBattery,
        nextRunAt = 0L,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun at(hour: Int, minute: Int): Long =
        Calendar.getInstance(utc).apply {
            clear()
            set(2026, Calendar.OCTOBER, 29, hour, minute)
        }.timeInMillis

    @Test
    fun noConditions_pass() {
        val result = ConditionEvaluator.evaluate(
            task(),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = 40, charging = false),
            utc
        )
        assertTrue(result is ConditionResult.Satisfied)
    }

    @Test
    fun zeroWindow_meansUnlimited() {
        // 0:00~0:00 曾被当成「只在 00:00 这一分钟」，把每天定时的任务静默跳过，这里锁住回归
        val result = ConditionEvaluator.evaluate(
            task(windowFrom = 0, windowTo = 0),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = 40, charging = false),
            utc
        )
        assertTrue(result is ConditionResult.Satisfied)
    }

    @Test
    fun outsideWindow_isSkipped() {
        val result = ConditionEvaluator.evaluate(
            task(windowFrom = 8 * 60 + 30, windowTo = 23 * 60),
            ConditionContext(nowMillis = at(3, 30)),
            utc
        )
        assertEquals(
            ConditionFailure.OUT_OF_WINDOW,
            (result as ConditionResult.Unsatisfied).failure
        )
    }

    @Test
    fun midnightCrossingWindow_passes() {
        // 23:00~06:00 跨午夜
        val result = ConditionEvaluator.evaluate(
            task(windowFrom = 23 * 60, windowTo = 6 * 60),
            ConditionContext(nowMillis = at(2, 0)),
            utc
        )
        assertTrue(result is ConditionResult.Satisfied)
    }

    @Test
    fun requireCharging_withoutCharging_isSkipped() {
        val result = ConditionEvaluator.evaluate(
            task(requireCharging = true),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = 80, charging = false),
            utc
        )
        assertEquals(
            ConditionFailure.NOT_CHARGING,
            (result as ConditionResult.Unsatisfied).failure
        )
    }

    @Test
    fun requireCharging_withUnknownState_isSkipped() {
        val result = ConditionEvaluator.evaluate(
            task(requireCharging = true),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = null, charging = null),
            utc
        )
        assertEquals(
            ConditionFailure.STATE_UNKNOWN,
            (result as ConditionResult.Unsatisfied).failure
        )
    }

    @Test
    fun zeroMinBattery_isTreatedAsUnset() {
        // 0 曾让求值器去读电量，读到 null 就判 STATE_UNKNOWN 把任务跳过；现在按未设处理
        val result = ConditionEvaluator.evaluate(
            task(minBattery = 0),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = null, charging = null),
            utc
        )
        assertTrue(result is ConditionResult.Satisfied)
    }

    @Test
    fun batteryBelowMinimum_isSkipped() {
        val result = ConditionEvaluator.evaluate(
            task(minBattery = 30),
            ConditionContext(nowMillis = at(3, 30), batteryPercent = 20, charging = true),
            utc
        )
        assertEquals(
            ConditionFailure.BATTERY_TOO_LOW,
            (result as ConditionResult.Unsatisfied).failure
        )
    }
}
