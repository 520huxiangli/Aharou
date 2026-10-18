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

private val Context.weixinImDataStore by preferencesDataStore(
    name = "weixin_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 微信 iLink（ClawBot）接入配置。
 *
 * `ilinkUserId` 是绑定后平台分配的标识，和 token 一起才能收发；`baseUrl` 留空走官方端点，
 * 走自建网关时改这里。凭证由用户自己填，软件不内置任何人的身份。
 */
data class WeixinConfig(
    override val enabled: Boolean = false,
    val botToken: String = "",
    val ilinkUserId: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    override val proxy: String = ""
) : ImChannelConfig {
    override val ready: Boolean
        get() = botToken.isNotBlank() && ilinkUserId.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com"
    }
}

@Singleton
class WeixinSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("weixin_enabled")
        val TOKEN_KEY = stringPreferencesKey("weixin_bot_token")
        val ILINK_USER_KEY = stringPreferencesKey("weixin_ilink_user_id")
        val BASE_URL_KEY = stringPreferencesKey("weixin_base_url")
        val PROXY_KEY = stringPreferencesKey("weixin_proxy")
        val CURSOR_KEY = stringPreferencesKey("weixin_cursor")
    }

    override val configFlow: Flow<WeixinConfig> = context.weixinImDataStore.data.map { prefs ->
        WeixinConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            botToken = prefs[TOKEN_KEY] ?: "",
            ilinkUserId = prefs[ILINK_USER_KEY] ?: "",
            baseUrl = prefs[BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: WeixinConfig.DEFAULT_BASE_URL,
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    suspend fun current(): WeixinConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.weixinImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setBotToken(token: String) {
        context.weixinImDataStore.edit { it[TOKEN_KEY] = token.trim() }
    }

    suspend fun setIlinkUserId(id: String) {
        context.weixinImDataStore.edit { it[ILINK_USER_KEY] = id.trim() }
    }

    suspend fun setBaseUrl(url: String) {
        context.weixinImDataStore.edit {
            val value = url.trim()
            if (value.isBlank()) it.remove(BASE_URL_KEY) else it[BASE_URL_KEY] = value
        }
    }

    suspend fun setProxy(proxy: String) {
        context.weixinImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    /** 长轮询游标：重启后接着上次位置，避免重放历史消息。 */
    suspend fun cursor(): String? = context.weixinImDataStore.data.map { it[CURSOR_KEY] }.first()

    suspend fun setCursor(cursor: String?) {
        context.weixinImDataStore.edit {
            if (cursor.isNullOrBlank()) it.remove(CURSOR_KEY) else it[CURSOR_KEY] = cursor
        }
    }

    // ── ImSettingsSource：编排层只认这三件事 ───────────────

    override suspend fun currentConfig(): ImChannelConfig = current()

    override suspend fun lastCursor(): String = cursor().orEmpty()

    override suspend fun saveCursor(cursor: String) {
        setCursor(cursor)
    }
}
