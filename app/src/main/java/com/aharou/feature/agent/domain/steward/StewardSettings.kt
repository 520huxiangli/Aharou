package com.aharou.feature.agent.domain.steward

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能托管的开关与轮数上限。
 *
 * 存 SharedPreferences 而不是记忆目录：这是本机的运行开关，写进记忆目录会被同步到别的设备上。
 */
@Singleton
class StewardSettings @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 托管总开关。 */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    /** 自动续发轮数上限，收敛到 [MIN_LOOPS]..[MAX_LOOPS]。 */
    var maxLoops: Int
        get() = prefs.getInt(KEY_MAX_LOOPS, DEFAULT_MAX_LOOPS).coerceIn(MIN_LOOPS, MAX_LOOPS)
        set(value) = prefs.edit().putInt(KEY_MAX_LOOPS, value.coerceIn(MIN_LOOPS, MAX_LOOPS)).apply()

    /** 不限轮数（仍受控制器里的安全阀约束）。 */
    var unlimited: Boolean
        get() = prefs.getBoolean(KEY_UNLIMITED, false)
        set(value) = prefs.edit().putBoolean(KEY_UNLIMITED, value).apply()

    companion object {
        const val DEFAULT_MAX_LOOPS = 5
        const val MIN_LOOPS = 1
        const val MAX_LOOPS = 20

        private const val PREFS = "aharou_steward_prefs"
        private const val KEY_ENABLED = "steward_enabled"
        private const val KEY_MAX_LOOPS = "steward_max_loops"
        private const val KEY_UNLIMITED = "steward_unlimited"
    }
}