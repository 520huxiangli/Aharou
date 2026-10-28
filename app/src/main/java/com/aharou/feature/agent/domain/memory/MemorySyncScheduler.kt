package com.aharou.feature.agent.domain.memory

import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆自动同步的周期调度。
 *
 * 与定时任务那套一样用 WorkManager 周期任务；分钟数收敛到 15 的下限。
 * 只在「开关开着 + 填了仓库地址」时排队，否则取消——不为空转唤醒进程。
 */
@Singleton
class MemorySyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: MemorySyncSettings,
    private val memoryMaintenanceScheduler: MemoryMaintenanceScheduler,
) {

    /**
     * 按当前设置排/撤周期任务。开关、地址、间隔任一变化后都要调一次。
     *
     * 入口打诊断日志（不打 URL 正文，只报是否已填）：`enqueueUniquePeriodicWork` 是异步的、异常
     * 不回传，cancel 分支又完全静默，外部看不出它到底走了哪一支——只有这里能看到。
     *
     * @param force 配置项变了才传 true（地址 / 开关 / 间隔），此时必须重建任务以应用新参数。
     *   App 启动时传 false：已有任务能跑就保留它的周期计时，否则每次冷启动都把 20 分钟归零，
     *   重启得勤的时候任务永远等不到下一次到期。
     */
    fun apply(force: Boolean = false) {
        // 记忆维护（遗忘 + 整理）与同步开关无关：哪怕不开同步，也得按天整理一次。
        memoryMaintenanceScheduler.ensureScheduled()
        if (!enabled()) {
            FileLogger.i(TAG, "调度：不满足入队条件（auto=${settings.autoSync} urlFilled=${settings.repoUrl.isNotBlank()}），撤销周期任务")
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val minutes = settings.autoSyncIntervalMinutes
            .coerceAtLeast(MIN_INTERVAL_MINUTES)
            .toLong()
        val request = PeriodicWorkRequestBuilder<MemorySyncWorker>(minutes, TimeUnit.MINUTES).build()
        val policy = resolvePolicy(force)
        FileLogger.i(TAG, "调度：请求入队周期任务（每 $minutes 分钟，策略=$policy）")
        // 入队是异步的，失败不回传异常。只打「准备入队」会让人把「日志出现」当成「入队成功」
        // （实测日志有那句、但系统 JobScheduler 里并没有任务），所以把 Operation 的结果也落下来。
        val operation = WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            policy,
            request
        )
        operation.result.addListener(
            {
                val ok = runCatching { operation.state.value is Operation.State.SUCCESS }
                    .getOrDefault(false)
                FileLogger.i(TAG, "调度：入队结果=${if (ok) "成功" else "未成功"}（$UNIQUE_NAME）")
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /**
     * 选入队策略。
     *
     * 必须用 `CANCEL_AND_REENQUEUE` 的两个场合共用同一条理由：`UPDATE` 的语义是「就地更新、
     * 保留原有状态」，一旦某次实例化把任务置成 `FAILED`，之后每次重排都会把这个状态原样留着，
     * 任务永远不再执行（实测 WorkSpec `state=FAILED`、`run_attempt_count=0`，而日志一直报
     * 「入队成功」）。反过来，无脑每次重建的代价是重启即重置周期计时，所以运行中的任务交给 `KEEP`。
     */
    private fun resolvePolicy(force: Boolean): ExistingPeriodicWorkPolicy {
        if (force) return ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
        return when (existingState()) {
            null, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
            else -> ExistingPeriodicWorkPolicy.KEEP
        }
    }

    /** 当前唯一任务的状态；查不到（没排过或查询失败）返回 null。 */
    private fun existingState(): WorkInfo.State? = runBlocking(Dispatchers.IO) {
        runCatching {
            WorkManager.getInstance(context).getWorkInfosForUniqueWork(UNIQUE_NAME).get()
                .firstOrNull()
                ?.state
        }.getOrNull()
    }

    private fun enabled(): Boolean = settings.autoSync && settings.repoUrl.isNotBlank()

    companion object {
        private const val TAG = "MemorySyncScheduler"

        /** WorkManager 周期任务允许的最小周期。 */
        const val MIN_INTERVAL_MINUTES = 15

        private const val UNIQUE_NAME = "memory-sync"
    }
}
