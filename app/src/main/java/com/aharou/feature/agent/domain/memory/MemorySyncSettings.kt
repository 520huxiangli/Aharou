package com.aharou.feature.agent.domain.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 记忆仓库同步的本机配置：连哪个仓库、上次什么时候同步成没成。
 *
 * 存 SharedPreferences 而不是写进记忆目录：这是「这台设备连哪个仓库」的本机设置，
 * 放进记忆目录会被同步进仓库本身，让别的设备跟着串配置。
 */
@Singleton
class MemorySyncSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("aharou_memory_sync", Context.MODE_PRIVATE)

    /** 用户自己的记忆仓库地址；空串表示没配过，此时不提供同步。 */
    var repoUrl: String
        get() = prefs.getString(KEY_REPO_URL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REPO_URL, value.trim()).apply()

    /** 推送分支，默认 main。 */
    var branch: String
        get() = prefs.getString(KEY_BRANCH, DEFAULT_BRANCH).orEmpty().ifBlank { DEFAULT_BRANCH }
        set(value) = prefs.edit().putString(KEY_BRANCH, value.trim().ifBlank { DEFAULT_BRANCH }).apply()

    /** 上次同步完成的时间（epoch millis）；从未同步过为 0。 */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC_AT, value).apply()

    /** 上次同步的一句话结果（成功或失败原因），用于界面回显。 */
    var lastResult: String
        get() = prefs.getString(KEY_LAST_RESULT, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_RESULT, value.take(400)).apply()

    /**
     * 上次同步中「疑似被其他设备删除」的条目清单（仓库相对路径，每行一条）；无则空串。
     *
     * 与 [lastResult] 一样持久化：同步结果对象是一次性的，界面在别的时间点也要能看到这份清单。
     * 只是报告，不涉及任何删除动作。
     */
    var lastPendingDeletions: String
        get() = prefs.getString(KEY_PENDING_DELETIONS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PENDING_DELETIONS, value.take(4000)).apply()

    /**
     * 是否自动同步（后台周期推一次）。
     *
     * 默认开：记忆同步是「推上去就好」的事，不该让人记着去点。开关与仓库地址都就绪才会真正排周期任务。
     */
    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** 自动同步间隔（分钟）。低于 [MIN_INTERVAL_MINUTES] 没有意义，WorkManager 也做不到。 */
    var autoSyncIntervalMinutes: Int
        get() = prefs.getInt(KEY_AUTO_INTERVAL, DEFAULT_INTERVAL_MINUTES).coerceAtLeast(MIN_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_AUTO_INTERVAL, value.coerceAtLeast(MIN_INTERVAL_MINUTES)).apply()

    private companion object {
        const val DEFAULT_BRANCH = "main"
        const val DEFAULT_INTERVAL_MINUTES = 60

        /** WorkManager 周期任务允许的最小周期。 */
        const val MIN_INTERVAL_MINUTES = 15

        const val KEY_REPO_URL = "repo_url"
        const val KEY_BRANCH = "branch"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_RESULT = "last_result"
        const val KEY_PENDING_DELETIONS = "pending_deletions"
        const val KEY_AUTO_SYNC = "auto_sync"
        const val KEY_AUTO_INTERVAL = "auto_interval_minutes"
    }
}
