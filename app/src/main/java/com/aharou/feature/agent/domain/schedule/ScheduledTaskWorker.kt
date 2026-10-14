package com.aharou.feature.agent.domain.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 定时任务检查 Worker：每 15 分钟醒一次，认领到点的任务并把它们派给会话层。
 *
 * 只做三件事——查到期任务、用 compare-and-set 认领、发一条运行请求；真正的 AI 运行由
 * 会话层负责（见 [ScheduledRunBus]）。这样调度出问题不会拖垮会话链路，反之亦然。
 *
 * **错过不补跑**：迟到超过一个周期的（例如 App 很久没打开）只推进时间并记 `skipped`，
 * 否则一开机可能连着补跑好几轮，既烧 token 也容易把会话刷满。
 * **为何不用 @HiltWorker**：见 MemorySyncWorker 的说明——那个注解的处理与 Dagger 的 KSP
 * 处理器在本工程内冲突，会把 Hilt 的 `*_GeneratedInjector` 整体搞丢。普通 Worker + [EntryPoint]
 * 可由 HiltWorkerFactory 的反射兜底正常构造。
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, ScheduledTaskEntryPoint::class.java
        )
        val dao = entryPoint.scheduledTaskDao()
        val runBus = entryPoint.scheduledRunBus()
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

/** Worker 不在 Hilt 的注入图里，依赖得经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledTaskEntryPoint {
    fun scheduledTaskDao(): ScheduledTaskDao
    fun scheduledRunBus(): ScheduledRunBus
}
