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

private val Context.qqImDataStore by preferencesDataStore(
    name = "qq_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * QQ 机器人接入配置（QQ 开放平台 / q.qq.com）。
 *
 * 只需 `appId` + `appSecret`（开放平台机器人管理端「开发设置」里拿）。
 * 事件走 WebSocket Gateway，不用配回调地址。凭证由用户自填，App 不内置任何人的身份。
 */
data class QqConfig(
    override val enabled: Boolean = false,
    val appId: String = "",
    val appSecret: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    override val proxy: String = ""
) : ImChannelConfig {
    override val ready: Boolean
        get() = appId.isNotBlank() && appSecret.isNotBlank()

    companion object {
        /** 官方文档：统一 OpenAPI 地址是 api.bot.qq.com（api.sgroup.qq.com 是旧域名）。 */
        const val DEFAULT_BASE_URL = "https://api.bot.qq.com"
    }
}

@Singleton
class QqSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("qq_enabled")
        val APP_ID_KEY = stringPreferencesKey("qq_app_id")
        val APP_SECRET_KEY = stringPreferencesKey("qq_app_secret")
        val BASE_URL_KEY = stringPreferencesKey("qq_base_url")
        val PROXY_KEY = stringPreferencesKey("qq_proxy")
        val CURSOR_KEY = stringPreferencesKey("qq_cursor")
    }

    override val configFlow: Flow<QqConfig> = context.qqImDataStore.data.map { prefs ->
        QqConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            appId = prefs[APP_ID_KEY] ?: "",
            appSecret = prefs[APP_SECRET_KEY] ?: "",
            baseUrl = prefs[BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: QqConfig.DEFAULT_BASE_URL,
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    suspend fun current(): QqConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.qqImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setAppId(id: String) {
        context.qqImDataStore.edit { it[APP_ID_KEY] = id.trim() }
    }

    suspend fun setAppSecret(secret: String) {
        context.qqImDataStore.edit { it[APP_SECRET_KEY] = secret.trim() }
    }

    suspend fun setBaseUrl(url: String) {
        context.qqImDataStore.edit {
            val value = url.trim()
            if (value.isBlank()) it.remove(BASE_URL_KEY) else it[BASE_URL_KEY] = value
        }
    }

    suspend fun setProxy(proxy: String) {
        context.qqImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    override suspend fun currentConfig(): ImChannelConfig = current()

    override suspend fun lastCursor(): String =
        context.qqImDataStore.data.map { it[CURSOR_KEY] }.first().orEmpty()

    override suspend fun saveCursor(cursor: String) {
        context.qqImDataStore.edit {
            if (cursor.isBlank()) it.remove(CURSOR_KEY) else it[CURSOR_KEY] = cursor
        }
    }
}
