package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.screenOnDataStore by preferencesDataStore(
    name = "screen_on_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「屏幕常亮」开关。默认关闭。
 *
 * 开启后由 [com.aharou.MainActivity] 监听本开关，给窗口加
 * [android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON]。该标志只在 App 位于前台时
 * 约束屏幕，退到后台由系统自动失效，因此不需要像 wakeLock 那样手动释放。
 * DataStore 用法与 [KeepaliveSettingsRepository] 一致。
 */
@Singleton
class ScreenOnSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("screen_on_enabled")
    }

    /** 当前持久化的开关流；未设置时回退到 false（默认关闭，需手动开启）。 */
    val enabledFlow: Flow<Boolean> = context.screenOnDataStore.data.map { it[ENABLED_KEY] ?: false }

    /** 写入开关。窗口标志的增删由 [com.aharou.MainActivity] 监听本流统一完成。 */
    suspend fun setEnabled(enabled: Boolean) {
        context.screenOnDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    /** 备份快照：当前屏幕常亮开关是否开启。 */
    suspend fun snapshot(): Boolean = enabledFlow.first()

    /** 从备份还原屏幕常亮开关。 */
    suspend fun restore(enabled: Boolean) = setEnabled(enabled)
}
