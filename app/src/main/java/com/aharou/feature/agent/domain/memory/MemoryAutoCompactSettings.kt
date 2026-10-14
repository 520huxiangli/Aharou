package com.aharou.feature.agent.domain.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「按需自动整理」的防抖状态：上次自动整理跑完的时间。
 *
 * 存 SharedPreferences 而不是写进记忆目录：这是本机的调度状态，放进记忆目录会被同步进
 * 记忆仓库、让别的设备跟着串调度。
 */
@Singleton
class MemoryAutoCompactSettings @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("aharou_memory_auto_compact", Context.MODE_PRIVATE)

    /** 上次自动整理跑完的时间（epoch millis）；从未跑过为 0。 */
    var lastAutoCompactAt: Long
        get() = prefs.getLong(KEY_LAST_AUTO_COMPACT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AUTO_COMPACT_AT, value).apply()

    private companion object {
        const val KEY_LAST_AUTO_COMPACT_AT = "last_auto_compact_at"
    }
}
