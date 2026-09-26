package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.visionModelDataStore by preferencesDataStore(
    name = "vision_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「识图模型」选择（providerId + model 两字符串）。
 *
 * 识图（viewImage）默认跟随当前聊天模型；用户在此指定专用模型后，识图会话使用该模型。
 * 不校验模型的视觉能力，调用失败时错误信息原样作为工具结果返回。
 * DataStore 用法与 [KeepaliveSettingsRepository] / [LogSettingsRepository] 一致。
 */
@Singleton
class VisionModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.visionModelDataStore, "vision_provider_id", "vision_model"
) {

    /** 写入识图专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setVisionModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——回退到「跟随当前聊天模型」。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前识图专用 providerId（冷读用）。 */
    suspend fun getVisionProviderId(): String = readProviderId()

    /** 读取一次当前识图专用 model（冷读用）。 */
    suspend fun getVisionModel(): String = readModel()
}
