package com.aharou.feature.settings.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 「专用模型选择」的公共持久化实现：providerId + model 两个字符串存于独立的 DataStore。
 *
 * 三个用途（识图 / 压缩 / 标题总结）结构完全一致，仅 DataStore 文件与 key 不同，故抽本基类；
 * 子类各自声明 `preferencesDataStore(name=...)` 委托并传入 key 名，方法语义统一：
 * providerId 为空（未配置）即视为「跟随当前聊天模型」。
 */
abstract class ModelSelectionSettingsRepository(
    protected val dataStore: DataStore<Preferences>,
    providerIdKeyName: String,
    modelKeyName: String
) {
    protected val providerIdKey = stringPreferencesKey(providerIdKeyName)
    protected val modelKey = stringPreferencesKey(modelKeyName)

    /** 当前持久化的专用 providerId 流；未设置时为空字符串（=跟随聊天模型）。 */
    val providerIdFlow: Flow<String> = dataStore.data.map { it[providerIdKey] ?: "" }

    /** 当前持久化的专用 model 流；未设置时为空字符串。 */
    val modelFlow: Flow<String> = dataStore.data.map { it[modelKey] ?: "" }

    /** 写入专用模型（设空字符串即等同 [clearSelection]）。 */
    protected suspend fun setSelection(providerId: String, model: String) {
        dataStore.edit {
            it[providerIdKey] = providerId
            it[modelKey] = model
        }
    }

    /** 清空配置——回退到「跟随当前聊天模型」。 */
    protected suspend fun clearSelection() {
        dataStore.edit {
            it.remove(providerIdKey)
            it.remove(modelKey)
        }
    }

    /** 读取一次当前专用 providerId（冷读用）。 */
    protected suspend fun readProviderId(): String = providerIdFlow.first()

    /** 读取一次当前专用 model（冷读用）。 */
    protected suspend fun readModel(): String = modelFlow.first()
}
