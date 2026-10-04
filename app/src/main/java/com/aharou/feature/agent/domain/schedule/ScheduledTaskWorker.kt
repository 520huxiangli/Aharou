package com.aharou.feature.agent.domain.schedule

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 定时任务检查 Worker：每 15 分钟醒一次，认领到点的任务并把它们派给会话层。
 *
 * 只做三件事——查到期任务、用 compare-and-set 认领、发一条运行请求；真正的 AI 运行由
 * 会话层负责（见 [ScheduledRunBus]）。这样调度出问题不会拖垮会话链路，反之亦然。
 *
 * **错过不补跑**：迟到超过一个周期的（例如 App 很久没打开）只推进时间并记 `skipped`，
 * 否则一开机可能连着补跑好几轮，既烧 token 也容易把会话刷满。
 */
@HiltWorker
class ScheduledTaskWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val dao: ScheduledTaskDao,
    private val runBus: ScheduledRunBus
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val now = System.currentTimeMillis()
        val due = runCatching { dao.due(now) }.getOrElse { e ->
            FileLogger.e(TAG, "读取到点的定时任务失败", e)
            return Result.retry()
        }

        due.forEach { task ->
            val interval = ScheduledTaskScheduler.normalizeInterval(task.intervalMinutes)
            // 认领：只有 nextRunAt 仍是我们读到的旧值时才推进，抢不到就跳过（别的检查已处理）。
            val claimed = runCatching {
                dao.claim(
                    id = task.id,
                    expectedNextRunAt = task.nextRunAt,
                    nextRunAt = ScheduledTaskScheduler.nextRunAfter(now, interval),
                    now = now
                )
            }.getOrElse { e ->
                FileLogger.e(TAG, "认领定时任务失败: ${task.id}", e)
                return@forEach
            }
            if (claimed == 0) return@forEach

            val lateMs = now - task.nextRunAt
            if (lateMs > interval * 60_000L) {
                dao.recordOutcome(task.id, OUTCOME_SKIPPED, now)
                FileLogger.w(TAG, "定时任务错过一个周期，未补跑: ${task.name}")
                return@forEach
            }

            val emitted = runBus.emit(
                ScheduledRunRequest(
                    taskId = task.id,
                    taskName = task.name,
                    prompt = task.prompt,
                    targetSessionId = task.targetSessionId,
                    workspacePath = task.workspacePath
                )
            )
            dao.recordOutcome(task.id, if (emitted) OUTCOME_DISPATCHED else OUTCOME_SKIPPED, now)
            if (!emitted) FileLogger.w(TAG, "定时任务投递失败（无消费方就绪）: ${task.name}")
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "ScheduledTaskWorker"
        const val OUTCOME_DISPATCHED = "dispatched"
        const val OUTCOME_SKIPPED = "skipped"
    }
}
