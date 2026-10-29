package com.aharou.feature.agent.domain.memory

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「按需自动整理」的防抖状态与「自动整理记忆」开关。
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

    /** 深度整理（按主题归拢 + 重写摘要）开关；每次都要花模型调用，所以默认关。 */
    var autoOrganize: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ORGANIZE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ORGANIZE, value).apply()

    var lastOrganizeAt: Long
        get() = prefs.getLong(KEY_LAST_ORGANIZE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_ORGANIZE_AT, value).apply()

    /** 上次深度整理的结果：合并组数 / 重写摘要条数，从未跑过为 null。 */
    var lastOrganizeReport: MemoryOrganizeReport?
        get() {
            if (lastOrganizeAt <= 0L) return null
            return MemoryOrganizeReport(
                merged = prefs.getInt(KEY_LAST_ORGANIZE_MERGED, 0),
                described = prefs.getInt(KEY_LAST_ORGANIZE_DESCRIBED, 0),
            )
        }
        set(value) {
            prefs.edit()
                .putInt(KEY_LAST_ORGANIZE_MERGED, value?.merged ?: 0)
                .putInt(KEY_LAST_ORGANIZE_DESCRIBED, value?.described ?: 0)
                .apply()
        }

    private companion object {
        const val KEY_LAST_AUTO_COMPACT_AT = "last_auto_compact_at"
        const val KEY_AUTO_ORGANIZE = "auto_organize"
        const val KEY_LAST_ORGANIZE_AT = "last_organize_at"
        const val KEY_LAST_ORGANIZE_MERGED = "last_organize_merged"
        const val KEY_LAST_ORGANIZE_DESCRIBED = "last_organize_described"
    }
}
