package com.aharou.feature.agent.domain.schedule

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 自动化任务的总开关。
 *
 * 与单个任务的 `enabled` 是两回事：那是「这个任务要不要跑」，这是「所有自动化一律先别跑」。
 * 用户临睡前或专注时想一次性静音全部任务，不必逐个关掉再逐个记着开回来。
 *
 * 暂停只影响「触发」，不删数据、不改任务自身的启用状态：恢复后该跑的照旧会跑，错过的按
 * 各自策略处理（时间类会在下一周期补上，事件类本来就不补）。
 */
@Singleton
class AutomationGlobalSwitch @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private companion object {
        const val PREFS = "aharou_automation_switch"
        const val KEY_PAUSED = "paused"
    }

    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    fun isPaused(): Boolean = prefs.getBoolean(KEY_PAUSED, false)

    fun setPaused(paused: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).commit()
    }
}
