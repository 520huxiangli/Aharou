package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.imageGenModelDataStore by preferencesDataStore(name = "image_gen_model_prefs")

/**
 * 持久化「生图模型」选择（providerId + model 两字符串）。
 *
 * 生图（generateImage）必须使用支持图像输出能力的模型（如 OpenAI 的 gpt-image-1 /
 * dall-e-3），不跟随当前聊天模型——聊天模型通常没有图像输出能力。未配置时生图工具
 * 返回带设置指引的错误提示。
 * DataStore 用法与 [VisionModelSettingsRepository] 一致。
 */
@Singleton
class ImageGenModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.imageGenModelDataStore, "image_gen_provider_id", "image_gen_model"
) {

    /** 写入生图专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setImageGenModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——生图模型回到未配置状态。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前生图专用 providerId（冷读用）。 */
    suspend fun getImageGenProviderId(): String = readProviderId()

    /** 读取一次当前生图专用 model（冷读用）。 */
    suspend fun getImageGenModel(): String = readModel()
}