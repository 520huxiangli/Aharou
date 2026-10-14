package com.aharou.feature.terminal.domain

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * 保活兜底 Worker：周期内检查常驻保活服务是否存活，被杀则重新拉起。
 *
 * START_STICKY 依赖系统在进程回收时重建，既不及时也不保证；WorkManager 由系统统一调度更可靠。
 * 仅在用户开启后台保活开关时被调度（见 [com.aharou.AIEditorApp]），关闭后任务取消，
 * 故这里仍复查一次开关，避免调度后用户又关闭导致的误拉起。
 *
 * **为何不用 @HiltWorker**：见 MemorySyncWorker 的说明——那个注解的处理与 Dagger 的 KSP
 * 处理器在本工程内冲突，会把 Hilt 的 `*_GeneratedInjector` 整体搞丢。普通 Worker + [EntryPoint]
 * 可由 HiltWorkerFactory 的反射兜底正常构造。
 */
class KeepaliveWorker(
    context: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result {
        val keepaliveSettings = EntryPointAccessors.fromApplication(
            applicationContext, KeepaliveEntryPoint::class.java
        ).keepaliveSettingsRepository()
        if (!keepaliveSettings.isEnabled()) return Result.success()
        if (!TerminalKeepaliveService.isRunning(applicationContext)) {
            TerminalKeepaliveService.enablePersistent(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "keepalive-check"
        /** WorkManager 允许的最小周期。 */
        private const val PERIOD_MINUTES = 15L

        /** 调度周期兜底任务（幂等）。保活开关开启时调用。 */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<KeepaliveWorker>(PERIOD_MINUTES, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /** 取消周期兜底任务。保活开关关闭时调用。 */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}

/** Worker 不在 Hilt 的注入图里，依赖得经 EntryPoint 取。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface KeepaliveEntryPoint {
    fun keepaliveSettingsRepository(): KeepaliveSettingsRepository
}