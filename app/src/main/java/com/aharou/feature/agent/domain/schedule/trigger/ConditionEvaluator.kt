package com.aharou.feature.agent.domain.schedule.trigger

import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import java.util.Calendar
import java.util.TimeZone

/** 求值那一刻的状态快照；电量与充电状态读不到时留 null，表示「无法确认」。 */
data class ConditionContext(
    val nowMillis: Long,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
)

/** 不满足的是哪一条。 */
enum class ConditionFailure {
    /** 不在允许的时段窗口内。 */
    OUT_OF_WINDOW,

    /** 要求充电中，但没在充电。 */
    NOT_CHARGING,

    /** 电量低于下限。 */
    BATTERY_TOO_LOW,

    /** 条件需要电量/充电状态，但这次没读出来。 */
    STATE_UNKNOWN,
}

sealed interface ConditionResult {
    /** 没有阻碍，可以跑。 */
    object Satisfied : ConditionResult

    data class Unsatisfied(val failure: ConditionFailure) : ConditionResult
}

/**
 * 条件求值：任务定义 + 当前状态 → 现在能不能跑。
 *
 * 纯函数、不碰 Android 类，时间也只经 [TimeZone] 换算（默认系统时区，测试可注入）。
 * 判定顺序固定（时段 → 充电 → 电量），只报第一条不满足的原因，便于把跳过理由讲给用户听。
 * 未设置的条件（-1 / false）直接跳过，不做求值。
 */
object ConditionEvaluator {

    private const val MINUTES_PER_DAY = 24 * 60

    fun evaluate(
        task: ScheduledTaskEntity,
        context: ConditionContext,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): ConditionResult {
        if (!inWindow(minuteOfDay(context.nowMillis, timeZone), task.conditionWindowFrom, task.conditionWindowTo)) {
            return ConditionResult.Unsatisfied(ConditionFailure.OUT_OF_WINDOW)
        }
        if (task.condRequireCharging) {
            when (context.charging) {
                null -> return ConditionResult.Unsatisfied(ConditionFailure.STATE_UNKNOWN)
                false -> return ConditionResult.Unsatisfied(ConditionFailure.NOT_CHARGING)
                true -> Unit
            }
        }
        // 电量 0 与不填等价（「不能低于 0%」恒真）：只有正数才算设了下限，
        // 否则会为一个没有约束力的条件去读电量，读不到就误判成跳过（2026-10-29 群友踩过）。
        if (task.condMinBattery > 0) {
            val battery = context.batteryPercent
                ?: return ConditionResult.Unsatisfied(ConditionFailure.STATE_UNKNOWN)
            if (battery < task.condMinBattery) {
                return ConditionResult.Unsatisfied(ConditionFailure.BATTERY_TOO_LOW)
            }
        }
        return ConditionResult.Satisfied
    }

    /** 当天第几分钟（0-1439），窗口判定的统一口径。 */
    fun minuteOfDay(nowMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Int {
        val calendar = Calendar.getInstance(timeZone).apply { timeInMillis = nowMillis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /**
     * 窗口判定：from > to 表示窗口跨午夜（23:00~06:00 是「今天 23 点之后」或「明天 6 点之前」）。
     * 两端都是 -1 视为不限；只给一端时另一端按当天边界补齐。
     */
    private fun inWindow(minute: Int, from: Int, to: Int): Boolean {
        if (from < 0 && to < 0) return true
        // 两端都是 00:00 是常见误填（表单默认值残留、AI 建任务时把「不限」写成 0）：
        // 按字面算它只覆盖 00:00 那一分钟，等于把「每天定时」的任务静默停掉。按不限处理。
        if (from == 0 && to == 0) return true
        val start = if (from < 0) 0 else from.coerceIn(0, MINUTES_PER_DAY - 1)
        val end = if (to < 0) MINUTES_PER_DAY - 1 else to.coerceIn(0, MINUTES_PER_DAY - 1)
        return if (start <= end) minute in start..end else minute >= start || minute <= end
    }
}