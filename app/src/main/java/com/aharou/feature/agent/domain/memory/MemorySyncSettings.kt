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

    private companion object {
        const val DEFAULT_BRANCH = "main"
        const val KEY_REPO_URL = "repo_url"
        const val KEY_BRANCH = "branch"
        const val KEY_LAST_SYNC_AT = "last_sync_at"
        const val KEY_LAST_RESULT = "last_result"
    }
}
