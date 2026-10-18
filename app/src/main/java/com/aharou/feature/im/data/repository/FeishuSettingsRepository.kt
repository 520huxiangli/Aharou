package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.feature.im.domain.ImChannelConfig
import com.aharou.feature.im.domain.ImSettingsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.feishuImDataStore by preferencesDataStore(
    name = "feishu_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 飞书接入配置。
 *
 * 只需要自建应用的 `appId` + `appSecret`（开放平台「凭证与基础信息」里拿），
 * 事件走 WebSocket 长连接，不用配回调地址。凭证由用户自填，App 不内置任何人的身份。
 */
data class FeishuConfig(
    override val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    override val proxy: String = ""
) : ImChannelConfig {
    override val ready: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://open.feishu.cn"
    }
}

@Singleton
class FeishuSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("feishu_enabled")
        val APP_ID_KEY = stringPreferencesKey("feishu_app_id")
        val APP_SECRET_KEY = stringPreferencesKey("feishu_app_secret")
        val BASE_URL_KEY = stringPreferencesKey("feishu_base_url")
        val PROXY_KEY = stringPreferencesKey("feishu_proxy")
        val CURSOR_KEY = stringPreferencesKey("feishu_cursor")
    }

    override val configFlow: Flow<FeishuConfig> = context.feishuImDataStore.data.map { prefs ->
        FeishuConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            appId = prefs[APP_ID_KEY] ?: "",
            appSecret = prefs[APP_SECRET_KEY] ?: "",
            baseUrl = prefs[BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: FeishuConfig.DEFAULT_BASE_URL,
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    suspend fun current(): FeishuConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.feishuImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setAppId(id: String) {
        context.feishuImDataStore.edit { it[APP_ID_KEY] = id.trim() }
    }

    suspend fun setAppSecret(secret: String) {
        context.feishuImDataStore.edit { it[APP_SECRET_KEY] = secret.trim() }
    }

    suspend fun setBaseUrl(url: String) {
        context.feishuImDataStore.edit {
            val value = url.trim()
            if (value.isBlank()) it.remove(BASE_URL_KEY) else it[BASE_URL_KEY] = value
        }
    }

    suspend fun setProxy(proxy: String) {
        context.feishuImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    override suspend fun currentConfig(): ImChannelConfig = current()

    override suspend fun lastCursor(): String =
        context.feishuImDataStore.data.map { it[CURSOR_KEY] }.first().orEmpty()

    override suspend fun saveCursor(cursor: String) {
        context.feishuImDataStore.edit {
            if (cursor.isBlank()) it.remove(CURSOR_KEY) else it[CURSOR_KEY] = cursor
        }
    }
}
