package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen

private const val TAG = "AssistantOverlaySettings"

private val Context.assistantOverlayDataStore by preferencesDataStore(
    name = "assistant_overlay_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 系统助手浮层要往模型上下文里带什么。
 *
 * 两项都是「带出去就收不回」的隐私开关，所以默认只带屏幕文本、不带截图；
 * 用户关掉文本后，浮层仍然能对话，只是模型不知道你正看着什么。
 */
@Singleton
class AssistantOverlaySettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val dataStore = context.assistantOverlayDataStore
    private val screenTextKey = booleanPreferencesKey("assistant_overlay_screen_text")
    private val screenshotKey = booleanPreferencesKey("assistant_overlay_screenshot")
    private val powerKeyKey = booleanPreferencesKey("assistant_power_key")

    val screenTextFlow: Flow<Boolean> = dataStore.data
        .map { it[screenTextKey] ?: true }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "屏幕文本开关读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    val screenshotFlow: Flow<Boolean> = dataStore.data
        .map { it[screenshotKey] ?: false }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "截图开关读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    /** 长按电源键唤起（需 Shizuku 与前台服务常驻），默认关。 */
    val powerKeyFlow: Flow<Boolean> = dataStore.data
        .map { it[powerKeyKey] ?: false }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "电源键开关读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    suspend fun getScreenText(): Boolean = screenTextFlow.first()

    suspend fun getScreenshot(): Boolean = screenshotFlow.first()

    suspend fun setScreenText(enabled: Boolean) {
        dataStore.edit { it[screenTextKey] = enabled }
    }

    suspend fun setScreenshot(enabled: Boolean) {
        dataStore.edit { it[screenshotKey] = enabled }
    }

    suspend fun getPowerKey(): Boolean = powerKeyFlow.first()

    suspend fun setPowerKey(enabled: Boolean) {
        dataStore.edit { it[powerKeyKey] = enabled }
    }
}
