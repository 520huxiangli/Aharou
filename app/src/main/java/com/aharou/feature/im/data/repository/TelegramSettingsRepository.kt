package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.telegramImDataStore by preferencesDataStore(
    name = "telegram_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/** Telegram 机器人接入的持久配置快照。 */
data class TelegramConfig(
    val enabled: Boolean = false,
    val botToken: String = "",
    /** Bot API 基址。境内 api.telegram.org 不可直连时改为自建反代/镜像地址。 */
    val baseUrl: String = DEFAULT_BASE_URL,
    /** 可选代理，如 http://127.0.0.1:7890、socks5://127.0.0.1:1080；留空则走全局代理设置。 */
    val proxy: String = ""
) {
    /** 基址已配置且 token 非空才谈得上连接。 */
    val ready: Boolean
        get() = botToken.isNotBlank() && baseUrl.isNotBlank()

    companion object {
        const val DEFAULT_BASE_URL = "https://api.telegram.org"
    }
}

/**
 * Telegram 机器人接入配置（token / 基址 / 代理 / 启用开关）与长轮询游标。
 *
 * 存 DataStore 而非 WorkSpace 文件：token 属敏感凭据，且不应落在 agent 的文件工具可写范围内。
 * 用法与 [com.aharou.feature.settings.data.repository.KeepaliveSettingsRepository] 一致。
 */
@Singleton
class TelegramSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("telegram_enabled")
        val TOKEN_KEY = stringPreferencesKey("telegram_bot_token")
        val BASE_URL_KEY = stringPreferencesKey("telegram_base_url")
        val PROXY_KEY = stringPreferencesKey("telegram_proxy")
        val LAST_UPDATE_KEY = longPreferencesKey("telegram_last_update_id")
    }

    val configFlow: Flow<TelegramConfig> = context.telegramImDataStore.data.map { prefs ->
        TelegramConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            botToken = prefs[TOKEN_KEY] ?: "",
            baseUrl = prefs[BASE_URL_KEY]?.takeIf { it.isNotBlank() } ?: TelegramConfig.DEFAULT_BASE_URL,
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    /** 读一次当前配置（服务循环里每轮读取）。 */
    suspend fun current(): TelegramConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.telegramImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setBotToken(token: String) {
        context.telegramImDataStore.edit { it[TOKEN_KEY] = token.trim() }
    }

    suspend fun setBaseUrl(baseUrl: String) {
        context.telegramImDataStore.edit {
            val value = baseUrl.trim()
            if (value.isBlank()) it.remove(BASE_URL_KEY) else it[BASE_URL_KEY] = value
        }
    }

    suspend fun setProxy(proxy: String) {
        context.telegramImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    /** 长轮询游标：重启后从上次位置接续，避免重放历史消息。 */
    suspend fun lastUpdateId(): Long = context.telegramImDataStore.data.map {
        it[LAST_UPDATE_KEY] ?: 0L
    }.first()

    suspend fun setLastUpdateId(offset: Long) {
        context.telegramImDataStore.edit { it[LAST_UPDATE_KEY] = offset }
    }
}
