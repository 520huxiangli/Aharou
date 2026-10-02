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

private const val TAG = "VoiceWakeSettings"

private val Context.voiceWakeDataStore by preferencesDataStore(
    name = "voice_wake_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「语音唤醒」开关。
 *
 * 单独一个 store，不并进通话或合成那边：这项决定麦克风是否常驻监听，
 * 关掉时要把服务整个停掉，生命周期与通话的启停互不牵连。
 */
@Singleton
class VoiceWakeSettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context
) {
    private val dataStore = context.voiceWakeDataStore
    private val enabledKey = booleanPreferencesKey("voice_wake_enabled")

    /**
     * 开关流：默认关。
     *
     * 读失败就一直重试，**绝不让这条流结束**——流一旦结束，收它的 UI 就再也收不到值，
     * 开关会冻在最后一次显示上（表现就是「显示关、实际开、怎么点都关不掉」）。
     */
    val enabledFlow: Flow<Boolean> = dataStore.data
        .map { it[enabledKey] ?: false }
        .retryWhen { cause, attempt ->
            FileLogger.w(TAG, "语音唤醒开关读取失败（第 ${attempt + 1} 次），重试", cause)
            delay(if (attempt < 3) 500 else 2000)
            true
        }

    /** 读一次开关状态。 */
    suspend fun isEnabled(): Boolean = enabledFlow.first()

    /** 写开关状态。 */
    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[enabledKey] = enabled }
    }
}
