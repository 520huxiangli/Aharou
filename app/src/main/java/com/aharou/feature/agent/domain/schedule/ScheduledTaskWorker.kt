package com.aharou.feature.agent.domain.schedule

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 自动化任务检查 Worker：每 15 分钟醒一次，交给 [ScheduledTaskDispatcher] 认领并派发到点任务。
 *
 * 它只是**兜底**：准点触发走精确闹钟的广播（[ScheduledTaskAlarmReceiver]），那条路有闹钟豁免、
 * 能直接拉起前台服务；闹钟被系统拦掉或权限被收回时，靠这一轮把漏掉的任务捞起来。
 *
 * 具体判定（认领、迟到宽限、条件、防抖、在跑）全在 [ScheduledTaskDispatcher]，
 * 两条路径共用，行为不会分叉。
 *
 * **错过不补跑**：迟到超过宽限的只记 `skipped_missed` 并推进时间，否则一开机可能连着补跑。
 * **为何不用 @HiltWorker**：见 MemorySyncWorker 的说明——那个注解的处理与 Dagger 的 KSP
 * 处理器在本工程内冲突，会把 Hilt 的 `*_GeneratedInjector` 整体搞丢。
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, ScheduledTaskEntryPoint::class.java
        )
        return try {
            entryPoint.scheduledTaskDispatcher().dispatchDue()
            Result.success()
        } catch (e: Exception) {
            FileLogger.e(TAG, "自动化任务派发失败", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "ScheduledTaskWorker"
    }
}

/** Worker 与闹钟广播都不在 Hilt 注入图里，依赖经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScheduledTaskEntryPoint {
    fun scheduledTaskDispatcher(): ScheduledTaskDispatcher
}
