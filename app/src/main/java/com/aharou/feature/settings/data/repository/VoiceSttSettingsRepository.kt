package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.voiceSttDataStore by preferencesDataStore(
    name = "voice_stt_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「语音识别模型」选择（providerId + model）。
 *
 * 语音输入默认走 APK 内置的离线模型；用户在此指定云端模型后，识别改走该模型的转录接口，
 * 失败时回退离线。DataStore 用法与 [VisionModelSettingsRepository] 一致。
 */
@Singleton
class VoiceSttSettingsRepository @Inject constructor(
    @param:ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.voiceSttDataStore, "voice_stt_provider_id", "voice_stt_model"
) {

    /** 写入识别模型（设空字符串即等同 [clear]）。 */
    suspend fun setSttModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——回退到内置离线模型。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前识别 providerId（冷读用）。 */
    suspend fun getSttProviderId(): String = readProviderId()

    /** 读取一次当前识别 model（冷读用）。 */
    suspend fun getSttModel(): String = readModel()
}
