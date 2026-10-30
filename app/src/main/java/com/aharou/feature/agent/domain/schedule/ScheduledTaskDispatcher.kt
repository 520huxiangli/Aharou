package com.aharou.feature.agent.domain.schedule

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.trigger.ConditionContext
import com.aharou.feature.agent.domain.schedule.trigger.ConditionEvaluator
import com.aharou.feature.agent.domain.schedule.trigger.ConditionFailure
import com.aharou.feature.agent.domain.schedule.trigger.ConditionResult
import com.aharou.feature.agent.domain.schedule.trigger.DeviceStateMonitor
import com.aharou.feature.agent.domain.schedule.trigger.DeviceStateSnapshot
import com.aharou.feature.agent.domain.schedule.trigger.EventPayload
import com.aharou.feature.agent.domain.schedule.trigger.EventTriggerBus
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
import com.aharou.feature.agent.domain.schedule.trigger.renderPrompt
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 到点任务的认领与派发：查到期任务 → compare-and-set 认领 → 条件/防抖/在跑判定 → 交给执行服务。
 *
 * 两条触发路径共用这一份逻辑，行为不会分叉：
 * - 精确闹钟到点的广播（[ScheduledTaskAlarmReceiver]）——有闹钟豁免，能起前台服务；
 * - 15 分钟周期 Worker（[ScheduledTaskWorker]）——兜底，闹钟被系统拦掉时仍能捞起。
 *
 * **为什么不再投内存总线**：总线只在主界面有人收时才真跑，没人收时 emit 照样返回成功，
 * 请求静默丢失。2026-10-30 真机现场：任务记成「已派发」，实际没执行。
 * 现在认领后直接拉起执行服务，与 OpenMinis 的做法一致。
 */
@Singleton
class ScheduledTaskDispatcher @Inject constructor(
    private val dao: ScheduledTaskDao,
    private val eventBus: EventTriggerBus,
    private val deviceStateMonitor: DeviceStateMonitor,
    private val scheduler: ScheduledTaskScheduler,
    @param:ApplicationContext private val context: Context,
) {

    /** 返回本轮真正派发出去的任务数。 */
    suspend fun dispatchDue(now: Long = System.currentTimeMillis()): Int {
        // 顺带比对一次设备状态（电量/充电/WiFi）：电量与网络没有实时监听，状态类事件最迟
        // 一个周期（15 分钟）才发现，是设计取舍。
        val state = runCatching { deviceStateMonitor.check() }.getOrNull()
        if (state?.hasChanges == true) {
            FileLogger.i(TAG, "设备状态变化：${state.summary()}")
            runCatching { eventBus.dispatchDeviceStateChange(state) }
                .onFailure { FileLogger.w(TAG, "设备状态事件派发失败", it) }
        }

        val due = runCatching { dao.due(now) }.getOrElse { e ->
            FileLogger.e(TAG, "读取到点的定时任务失败", e)
            return 0
        }
        // 条件求值与事件现场都用上面那一次读数，整轮只读一次电量/充电状态。
        val conditions = ConditionContext(
            nowMillis = now,
            batteryPercent = state?.current?.batteryPercent,
            charging = state?.current?.charging,
        )
        val snapshot = state?.current ?: DeviceStateSnapshot()

        var dispatched = 0
        due.forEach { task ->
            when (dispatchOne(task, now, conditions, snapshot)) {
                true -> dispatched++
                false -> Unit
            }
        }

        // 认领改变了 nextRunAt，精确闹钟要跟着指向新的「最早到期」任务。
        runCatching { scheduler.rescheduleAlarm(context) }
            .onFailure { FileLogger.e(TAG, "重排精确闹钟失败", it) }
        return dispatched
    }

    /** 单个任务：认领与各种前置判定，过了就交给执行服务。 */
    private suspend fun dispatchOne(
        task: ScheduledTaskEntity,
        now: Long,
        conditions: ConditionContext,
        snapshot: DeviceStateSnapshot,
    ): Boolean {
        val once = task.scheduleType == ScheduledTaskEntity.TYPE_ONCE
        // 单次任务没有「下一次」；事件类任务的 nextRunAt 只是「被事件投递进来了」的标记，
        // 跑完同样没有下一次——都占一个远期值，等下一次事件再来改写。
        val nextRunAt = if (once || TriggerType.isEventTrigger(task)) {
            Long.MAX_VALUE
        } else {
            ScheduledTaskScheduler.nextRunFor(task, now)
        }
        // 认领：只有 nextRunAt 仍是我们读到的旧值时才推进，抢不到说明别的路径已处理。
        val claimed = runCatching {
            dao.claim(task.id, expectedNextRunAt = task.nextRunAt, nextRunAt = nextRunAt, now = now)
        }.getOrElse { e ->
            FileLogger.e(TAG, "认领自动化任务失败: ${task.id}", e)
            return false
        }
        if (claimed == 0) return false
        if (once) dao.setEnabled(task.id, false, now)

        if (now - task.nextRunAt > graceMillis(task)) {
            dao.recordOutcome(task.id, OUTCOME_SKIPPED_MISSED, now)
            FileLogger.w(TAG, "自动化任务错过太久，未补跑: ${task.name}")
            return false
        }
        val condition = ConditionEvaluator.evaluate(task, conditions)
        if (condition is ConditionResult.Unsatisfied) {
            dao.recordOutcome(task.id, outcomeOf(condition.failure), now)
            FileLogger.w(TAG, "自动化任务条件不满足(${condition.failure})，跳过: ${task.name}")
            return false
        }
        if (withinDebounce(task, now)) {
            dao.recordOutcome(task.id, OUTCOME_SKIPPED_DEBOUNCE, now)
            FileLogger.w(TAG, "自动化任务在防抖窗口内，跳过: ${task.name}")
            return false
        }
        if (stillRunning(task, now)) {
            dao.recordOutcome(task.id, OUTCOME_SKIPPED_RUNNING, now)
            FileLogger.w(TAG, "自动化任务上一次还在跑，跳过: ${task.name}")
            return false
        }

        val rendered = renderPrompt(task.prompt, eventPayload(task, now, snapshot))
        return runCatching {
            ScheduledTaskExecutionService.start(
                context,
                task.id,
                task.name,
                rendered,
                task.workspacePath,
                task.targetSessionId,
            )
            dao.recordOutcome(task.id, OUTCOME_DISPATCHED, now)
            true
        }.getOrElse { e ->
            // 前台服务被系统拦（极少数）时落库，App 下次到前台捡跑，不丢任务。
            FileLogger.w(TAG, "自动化任务执行服务启动失败，落库待捡跑: ${task.name} - ${e.message}")
            dao.markPendingRun(task.id, rendered, now)
            false
        }
    }

    /**
     * 迟到多久还算「该跑」：周期任务给一个周期，定时与单次任务给两小时。
     * 提醒类任务晚了半天才响没有意义，宁可不跑。
     */
    private fun graceMillis(task: ScheduledTaskEntity): Long = when (task.scheduleType) {
        ScheduledTaskEntity.TYPE_DAILY, ScheduledTaskEntity.TYPE_ONCE -> 2 * 60 * 60 * 1000L
        else -> ScheduledTaskScheduler.normalizeInterval(task.intervalMinutes) * 60_000L
    }

    /**
     * 防抖只对事件触发有意义：一串同样的通知不该连着跑好几遍。基准取认领前读到的 lastRunAt
     * ——claim 会把它刷成 now，拿认领后的值比就永远落在窗口内。
     */
    private fun withinDebounce(task: ScheduledTaskEntity, now: Long): Boolean {
        if (!TriggerType.isEventTrigger(task)) return false
        val window = task.debounceMinutes * 60_000L
        if (window <= 0L) return false
        val last = task.lastRunAt ?: return false
        return now - last < window
    }

    /**
     * runMode=single 的近似实现：链路里没有「上一次跑完了吗」的回执，退化成
     * 「距上次运行不足 [RUNNING_GUARD_MINUTES] 分钟就当作还在跑」。
     */
    private fun stillRunning(task: ScheduledTaskEntity, now: Long): Boolean {
        if (task.runMode != ScheduledTaskEntity.RUN_SINGLE) return false
        val last = task.lastRunAt ?: return false
        return now - last < RUNNING_GUARD_MINUTES * 60_000L
    }

    /** 事件现场：优先用事件总线带进来的那份，缺的用本轮快照补；时间类任务走的就是补全这条路。 */
    private fun eventPayload(
        task: ScheduledTaskEntity,
        now: Long,
        snapshot: DeviceStateSnapshot,
    ): EventPayload {
        val live = eventBus.takePayload(task.id)
        if (live == null) {
            return EventPayload(
                app = task.triggerPackage,
                batteryPercent = snapshot.batteryPercent,
                ssid = snapshot.ssid,
                timeMillis = now,
            )
        }
        return live.copy(
            app = live.app.ifEmpty { task.triggerPackage },
            batteryPercent = live.batteryPercent ?: snapshot.batteryPercent,
            ssid = live.ssid.ifEmpty { snapshot.ssid },
            timeMillis = if (live.timeMillis > 0L) live.timeMillis else now,
        )
    }

    private companion object {
        const val TAG = "ScheduledTaskDispatch"
        const val OUTCOME_DISPATCHED = "dispatched"
        const val OUTCOME_SKIPPED_MISSED = "skipped_missed"
        const val OUTCOME_SKIPPED_DEBOUNCE = "skipped_debounce"
        const val OUTCOME_SKIPPED_RUNNING = "skipped_running"
        const val OUTCOME_SKIPPED_CONDITION = "skipped_condition"
        const val RUNNING_GUARD_MINUTES = 5L

        fun outcomeOf(failure: ConditionFailure): String {
            val reason = when (failure) {
                ConditionFailure.OUT_OF_WINDOW -> "out_of_window"
                ConditionFailure.NOT_CHARGING -> "not_charging"
                ConditionFailure.BATTERY_TOO_LOW -> "battery_too_low"
                ConditionFailure.STATE_UNKNOWN -> "state_unknown"
            }
            return "$OUTCOME_SKIPPED_CONDITION:$reason"
        }
    }
}
