package com.aharou.feature.agent.domain.memory

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.aharou.core.util.FileLogger
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * 记忆自动同步的周期任务。
 *
 * 每次醒来只做一件事：跑一遍 [MemorySyncManager.sync]（收集 → 提交 → 拉取 → 推送 → 补回缺失）。
 * 跑到一半失败不重试——周期任务本就是「尽力跟上」的语义，下个周期再来一遍就好，
 * 用 Result.retry() 反而会在断网时连着退避重试、白耗电。
 */
@HiltWorker
class MemorySyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncManager: MemorySyncManager,
    private val settings: MemorySyncSettings,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // 周期任务可能在开关关掉后还醒最后一次（取消是异步的），这里再确认一遍。
        if (!settings.autoSync || settings.repoUrl.isBlank()) return Result.success()
        val outcome = syncManager.sync()
        if (!outcome.success) FileLogger.w(TAG, "自动同步失败：${outcome.message}")
        return Result.success()
    }

    private companion object {
        const val TAG = "MemorySyncWorker"
    }
}
