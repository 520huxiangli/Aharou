package com.aharou.feature.plugin.workflow

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 工作流到点执行的后台入口。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）`core/workflow/WorkflowWorker.kt`；
 * 差异是不按「一工作流一 Work」跑，而是统一跑一轮到期检查（见 [WorkflowScheduler]）。
 *
 * **为什么不用 @HiltWorker**：与 [com.aharou.feature.agent.domain.schedule.ScheduledTaskWorker]
 * 同一原因——该注解的 KSP 处理在本工程里会与 Dagger 冲突，把 Hilt 的 `*_GeneratedInjector` 搞丢。
 */
class WorkflowWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext, WorkflowWorkerEntryPoint::class.java
        )
        return try {
            entryPoint.workflowScheduler().runDue()
            Result.success()
        } catch (e: Exception) {
            FileLogger.e(TAG, "工作流到点检查失败", e)
            Result.retry()
        }
    }

    private companion object {
        const val TAG = "WorkflowWorker"
    }
}

/** Worker 不在 Hilt 注入图里，依赖经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkflowWorkerEntryPoint {
    fun workflowScheduler(): WorkflowScheduler
}
