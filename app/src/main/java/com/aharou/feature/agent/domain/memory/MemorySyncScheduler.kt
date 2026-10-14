package com.aharou.feature.agent.domain.memory

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
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
) {

    /** 按当前设置排/撤周期任务。开关、地址、间隔任一变化后都要调一次。 */
    fun apply() {
        if (!enabled()) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
            return
        }
        val minutes = settings.autoSyncIntervalMinutes
            .coerceAtLeast(MIN_INTERVAL_MINUTES)
            .toLong()
        val request = PeriodicWorkRequestBuilder<MemorySyncWorker>(minutes, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun enabled(): Boolean = settings.autoSync && settings.repoUrl.isNotBlank()

    companion object {
        /** WorkManager 周期任务允许的最小周期。 */
        const val MIN_INTERVAL_MINUTES = 15

        private const val UNIQUE_NAME = "memory-sync"
    }
}
