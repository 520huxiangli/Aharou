package com.aharou.feature.agent.domain.schedule

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 定时任务的调度与时间推进。
 *
 * WorkManager 周期任务最短 15 分钟，所以「检查有没有到点的任务」固定按 15 分钟唤醒；
 * 任务自身的周期由 `nextRunAt` 决定，两次检查之间到点的任务在下次检查时被认领。
 */
@Singleton
class ScheduledTaskScheduler @Inject constructor(
    private val dao: ScheduledTaskDao
) {

    companion object {
        /** WorkManager 周期任务允许的最小周期，也是任务周期下限。 */
        const val MIN_INTERVAL_MINUTES = 15

        private const val UNIQUE_NAME = "scheduled-task-check"
        private const val CHECK_PERIOD_MINUTES = 15L

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

        /** 周期收敛到下限：低于 15 分钟没有意义，WorkManager 也做不到。 */
        fun normalizeInterval(minutes: Int): Int = minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

        /** 下一次运行时间。 */
        fun nextRunAfter(baseMs: Long, intervalMinutes: Int): Long =
            baseMs + normalizeInterval(intervalMinutes) * 60_000L
    }

    /** 任务增删改之后调用：有启用中的任务才排周期检查，没有就取消（不为空转唤醒进程）。 */
    suspend fun ensureScheduled(context: Context) {
        if (dao.countEnabled() > 0) schedule(context) else cancel(context)
    }
}
