package com.aharou.feature.agent.domain.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动化任务的调度与时间推进。
 *
 * 两条腿走路：
 * - **精确闹钟**（AlarmManager.setExactAndAllowWhileIdle）负责「准点」——每天 8:00 的任务，
 *   Android 12+ 需要用户在系统里授予「闹钟和提醒」特殊权限，没授权时自动降级为不精确闹钟；
 * - **周期兜底**（WorkManager，15 分钟一次）负责「不漏」——精确闹钟被系统拦掉或权限被收回时，
 *   仍能在下次检查里把到点任务捞起来。
 *
 * 任务自身的到期时刻一律由 [ScheduledTaskEntity.nextRunAt] 决定，两种触发方式共用同一份状态，
 * 谁先醒来谁认领（DAO 的 compare-and-set 保证不会重复跑）。
 */
@Singleton
class ScheduledTaskScheduler @Inject constructor(
    private val dao: ScheduledTaskDao
) {

    companion object {
        /** WorkManager 周期任务允许的最小周期，也是周期类任务的下限。 */
        const val MIN_INTERVAL_MINUTES = 15

        const val ACTION_ALARM = "com.aharou.intent.AUTOMATION_ALARM"

        private const val UNIQUE_NAME = "scheduled-task-check"
        private const val CHECK_PERIOD_MINUTES = 15L
        private const val ALARM_REQUEST_CODE = 0x5A17

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduledTaskWorker>(CHECK_PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }

        /**
         * 立刻催一次检查：事件投递与精确闹钟到点都走这里，免得两处各写一遍 OneTimeWork 请求。
         */
        fun scheduleNow(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<ScheduledTaskWorker>().build()
            )
        }

        /** 周期收敛到下限：低于 15 分钟没有意义，WorkManager 也做不到。 */
        fun normalizeInterval(minutes: Int): Int = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

        /** 周期类任务的下一次运行时间。 */
        fun nextRunAfter(baseMs: Long, intervalMinutes: Int): Long =
            baseMs + normalizeInterval(intervalMinutes) * 60_000L

        /** 按调度类型算下次运行时刻。 */
        fun nextRunFor(task: ScheduledTaskEntity, now: Long): Long = when (task.scheduleType) {
            ScheduledTaskEntity.TYPE_DAILY -> nextDailyOccurrence(task.dailyMinuteOfDay, now)
            ScheduledTaskEntity.TYPE_ONCE -> task.onceAtMillis
            else -> nextRunAfter(now, task.intervalMinutes)
        }

        /** 下一个「当天第 minuteOfDay 分钟」的时刻；今天已过就顺延到明天。 */
        fun nextDailyOccurrence(minuteOfDay: Int, now: Long): Long {
            val safe = minuteOfDay.coerceIn(0, 24 * 60 - 1)
            val calendar = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, safe / 60)
                set(Calendar.MINUTE, safe % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (calendar.timeInMillis <= now) calendar.add(Calendar.DAY_OF_YEAR, 1)
            return calendar.timeInMillis
        }

        /**
         * 排一个精确唤醒。没有「闹钟和提醒」权限（Android 12+）时退回不精确的
         * setAndAllowWhileIdle；系统仍会兜住，只是可能晚几分钟——所以周期兜底不能撤。
         */
        fun scheduleExactAlarm(context: Context, atMillis: Long) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            val pending = alarmIntent(context)
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
            runCatching {
                if (canExact) {
                    manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                } else {
                    manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
                }
            }
        }

        fun cancelExactAlarm(context: Context) {
            val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
            runCatching { manager.cancel(alarmIntent(context)) }
        }

        private fun alarmIntent(context: Context): PendingIntent {
            val intent = Intent(context, ScheduledTaskAlarmReceiver::class.java).setAction(ACTION_ALARM)
            return PendingIntent.getBroadcast(
                context,
                ALARM_REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    /** 任务增删改、以及 App 启动时调用：有启用中的任务才排调度，没有就全部撤掉。 */
    suspend fun ensureScheduled(context: Context) {
        // 周期兜底对两类触发都要留着：事件类任务的 nextRunAt 由事件总线在触发那一刻写入，
        // 也得靠这轮检查把它们捞起来跑，所以「只有事件类任务」不能把周期任务撤掉。
        if (enabledTasks().isEmpty()) {
            cancel(context)
            cancelExactAlarm(context)
            return
        }
        schedule(context)
        rescheduleAlarm(context)
    }

    /** 把精确闹钟重新指向「最早到期的那个任务」。每次认领之后都要重排一次。 */
    suspend fun rescheduleAlarm(context: Context) {
        val next = liveTasks().minOfOrNull { it.nextRunAt }
        if (next == null) {
            cancelExactAlarm(context)
            return
        }
        scheduleExactAlarm(context, next.coerceAtLeast(System.currentTimeMillis() + 1_000L))
    }

    /** 启用中且未超运行上限的任务；两类触发都算。 */
    private suspend fun enabledTasks(): List<ScheduledTaskEntity> =
        dao.getAllOnce().filter { it.enabled && (it.maxRuns == 0 || it.runCount < it.maxRuns) }

    /**
     * 参与精确闹钟的任务：只有时间类触发才存在调度器算出来的 nextRunAt。事件类任务的
     * nextRunAt 是事件总线写的「现在」，混进来会把闹钟排到无意义的时刻上。
     */
    private suspend fun liveTasks(): List<ScheduledTaskEntity> =
        enabledTasks().filter { TriggerType.isTimeTrigger(it) }
}