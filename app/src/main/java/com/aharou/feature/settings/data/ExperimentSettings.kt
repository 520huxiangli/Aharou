package com.aharou.feature.settings.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 实验开关的本地存储：每个键三种取值 follow（跟随内置默认）/ on / off。
 *
 * 存 SharedPreferences 而不是记忆目录——这是本机的行为开关，不该同步到别的设备。
 * 取值与配置通道里的 `app.experiments.<key>` 同源，两处改动都走 [setValue]。
 */
@Singleton
class ExperimentSettings @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun value(key: String): String = prefs.getString(key, FOLLOW) ?: FOLLOW

    fun setValue(key: String, value: String) {
        val normalized = if (value in VALUES) value else FOLLOW
        prefs.edit().putString(key, normalized).apply()
    }

    /** [default] 是内置默认值；状态为 follow 时用它。 */
    fun isEnabled(key: String, default: Boolean): Boolean = when (value(key)) {
        ON -> true
        OFF -> false
        else -> default
    }

    companion object {
        const val FOLLOW = "follow"
        const val ON = "on"
        const val OFF = "off"

        val VALUES: List<String> = listOf(FOLLOW, ON, OFF)

        private const val PREFS = "aharou_experiments"
    }
}
