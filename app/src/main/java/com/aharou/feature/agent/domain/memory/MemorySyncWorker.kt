package com.aharou.feature.agent.domain.memory

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * 记忆自动同步的周期任务。
 *
 * 每次醒来只做一件事：跑一遍 [MemorySyncManager.sync]（收集 → 提交 → 拉取 → 推送 → 补回缺失）。
 * 跑到一半失败不重试——周期任务本就是「尽力跟上」的语义，下个周期再来一遍就好，
 * 用 Result.retry() 反而会在断网时连着退避重试、白耗电。
 *
 * **为何不用 @HiltWorker**：那个注解靠 `androidx.hilt:hilt-compiler` 生成注册表，而它与 Dagger
 * 的 KSP 处理器在同一工程里会冲突，把 Hilt 的 `*_GeneratedInjector` 整体搞丢（实测装上直接
 * NoClassDefFoundError 崩在 Application 构造）。改用普通 Worker + [EntryPoint] 手动取依赖：
 * HiltWorkerFactory 查不到注册时会回退反射构造，而普通 Worker 正好有 (Context, WorkerParameters)
 * 构造，这条路反而是通的。
 */
class MemorySyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint: MemorySyncEntryPoint
        get() = EntryPointAccessors.fromApplication(applicationContext, MemorySyncEntryPoint::class.java)

    override suspend fun doWork(): Result {
        val settings = entryPoint.memorySyncSettings()
        // 周期任务可能在开关关掉后还醒最后一次（取消是异步的），这里再确认一遍。
        if (!settings.autoSync || settings.repoUrl.isBlank()) return Result.success()
        val outcome = entryPoint.memorySyncManager().sync()
        if (!outcome.success) FileLogger.w(TAG, "自动同步失败：${outcome.message}")
        return Result.success()
    }

    private companion object {
        const val TAG = "MemorySyncWorker"
    }
}

/** Worker 不在 Hilt 的注入图里，依赖得经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MemorySyncEntryPoint {
    fun memorySyncManager(): MemorySyncManager
    fun memorySyncSettings(): MemorySyncSettings
}
