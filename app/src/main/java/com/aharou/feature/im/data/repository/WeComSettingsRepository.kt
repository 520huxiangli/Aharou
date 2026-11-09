package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.im.domain.ImChannelConfig
import com.aharou.feature.im.domain.ImSettingsSource
import com.aharou.feature.im.domain.WeComChannelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.wecomImDataStore by preferencesDataStore(
    name = "wecom_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 企业微信智能机器人（长连接模式）的持久配置。
 *
 * 只有 BotID + Secret 两项凭证：Secret 是长连接专用密钥，与回调地址模式的 Token/EncodingAESKey 不同，
 * 用户得自己在管理后台「智能机器人 → API 模式 → 长连接」里取，软件不内置任何人的身份。
 *
 * 存 DataStore 而非工作区文件：Secret 属敏感凭据，不应落在 agent 的文件工具可写范围内。
 */
@Singleton
class WeComSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {

    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("wecom_enabled")
        /** BotID 只是标识，不是密钥，和微信的 ilinkUserId 一样明文存。 */
        val BOT_ID_KEY = stringPreferencesKey("wecom_bot_id")
        val SECRET_KEY = stringPreferencesKey("wecom_secret")
        val PROXY_KEY = stringPreferencesKey("wecom_proxy")
        val CURSOR_KEY = stringPreferencesKey("wecom_cursor")
    }

    override val configFlow: Flow<WeComChannelConfig> = context.wecomImDataStore.data.map { prefs ->
        WeComChannelConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            botId = prefs[BOT_ID_KEY] ?: "",
            // 旧值可能是明文，decryptString 对非密文原样返回，于是不用另做迁移；下次保存时写成密文。
            secret = KeystoreCipher.decryptString(prefs[SECRET_KEY] ?: ""),
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    /** 读一次当前配置（服务循环里每轮读取）。 */
    suspend fun current(): WeComChannelConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.wecomImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setBotId(botId: String) {
        context.wecomImDataStore.edit { it[BOT_ID_KEY] = botId.trim() }
    }

    /** 写入 Secret；密钥库不可用时返回 false（不抛异常，界面据此提示重试）。 */
    suspend fun setSecret(secret: String): Boolean {
        val encrypted = ImSecretCrypto.encryptOrNull(secret.trim()) ?: return false
        context.wecomImDataStore.edit { it[SECRET_KEY] = encrypted }
        return true
    }

    suspend fun setProxy(proxy: String) {
        context.wecomImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    /** 会话游标：重启后接着上次位置，避免重放已经处理过的消息。 */
    suspend fun cursor(): String? = context.wecomImDataStore.data.map { it[CURSOR_KEY] }.first()

    suspend fun setCursor(cursor: String?) {
        context.wecomImDataStore.edit {
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
