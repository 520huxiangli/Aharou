package com.aharou.feature.agent.domain.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemoryMaintenance"

/**
 * 记忆维护的周期任务：遗忘 + 整理。
 *
 * 之前这两件事只挂在「会话收尾」，不聊天就永远不跑——而记忆会不会挤占上下文，跟开不开会话无关。
 * 这里按天跑一次：先遗忘长期没人取用的条目，再把剩下的碎片合并，全局和每个工作区各来一遍。
 *
 * 失败不重试：整理是尽力而为，下个周期再来；用 Result.retry() 只会在断网时连着退避、白耗电。
 */
class MemoryMaintenanceWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint: MemoryMaintenanceEntryPoint
        get() = EntryPointAccessors.fromApplication(applicationContext, MemoryMaintenanceEntryPoint::class.java)

    override suspend fun doWork(): Result {
        val compactor = entryPoint.memoryCompactor()
        runCatching { compactor.forgetIfDue(null, MemoryScope.GLOBAL) }
            .onFailure { FileLogger.w(TAG, "全局遗忘失败：${it.message}") }
        runCatching { compactor.compressOversizedIfDue(null, MemoryScope.GLOBAL) }
            .onFailure { FileLogger.w(TAG, "全局压缩失败：${it.message}") }
        runCatching { compactor.downgradeSqueezedIdentityIfDue(null, MemoryScope.GLOBAL) }
            .onFailure { FileLogger.w(TAG, "全局降级失败：${it.message}") }
        runCatching { compactor.compactIfDue(null, MemoryScope.GLOBAL) }
            .onFailure { FileLogger.w(TAG, "全局整理失败：${it.message}") }

        val roots = runCatching {
            entryPoint.workspaceRepository().workspaces.value.map { it.path }
        }.getOrDefault(emptyList())
        roots.filter { it.isNotBlank() }.forEach { root ->
            runCatching { compactor.forgetIfDue(root, MemoryScope.PROJECT) }
                .onFailure { FileLogger.w(TAG, "工作区遗忘失败（$root）：${it.message}") }
            runCatching { compactor.compressOversizedIfDue(root, MemoryScope.PROJECT) }
                .onFailure { FileLogger.w(TAG, "工作区压缩失败（$root）：${it.message}") }
            runCatching { compactor.downgradeSqueezedIdentityIfDue(root, MemoryScope.PROJECT) }
                .onFailure { FileLogger.w(TAG, "工作区降级失败（$root）：${it.message}") }
            runCatching { compactor.compactIfDue(root, MemoryScope.PROJECT) }
                .onFailure { FileLogger.w(TAG, "工作区整理失败（$root）：${it.message}") }
        }
        return Result.success()
    }
}

/** Worker 不在 Hilt 注入图里，依赖经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemoryMaintenanceEntryPoint {
    fun memoryCompactor(): MemoryCompactor
    fun workspaceRepository(): WorkspaceRepository
}

/** 按天排定记忆维护任务；重复调用不会重置周期（KEEP）。 */
@Singleton
class MemoryMaintenanceScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun ensureScheduled() {
        val request = PeriodicWorkRequestBuilder<MemoryMaintenanceWorker>(1, TimeUnit.DAYS).build()
        runCatching {
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }.onFailure { FileLogger.w(TAG, "排定记忆维护任务失败：${it.message}") }
    }

    companion object {
        const val WORK_NAME = "aharou_memory_maintenance"
    }
}
