package com.aharou.feature.settings.data.repository

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.titleModelDataStore by preferencesDataStore(
    name = "title_model_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 持久化「标题总结专用模型」选择（providerId + model 两字符串）。
 *
 * 新建会话生成标题默认跟随当前聊天模型；当用户在此指定一个专用模型后，
 * 标题生成会临时切换到该专用模型发送请求，发完恢复。providerId 为空（未配置）即视为「跟随当前聊天模型」。
 */
@Singleton
class TitleModelSettingsRepository @Inject constructor(
    @ApplicationContext context: Context
) : ModelSelectionSettingsRepository(
    context.titleModelDataStore, "title_provider_id", "title_model"
) {

    /** 写入标题总结专用模型（设空字符串即等同 [clear]）。 */
    suspend fun setTitleModel(providerId: String, model: String) = setSelection(providerId, model)

    /** 清空配置——回退到「跟随当前聊天模型」。 */
    suspend fun clear() = clearSelection()

    /** 读取一次当前标题总结专用 providerId（冷读用）。 */
    suspend fun getTitleProviderId(): String = readProviderId()

    /** 读取一次当前标题总结专用 model（冷读用）。 */
    suspend fun getTitleModel(): String = readModel()
}
