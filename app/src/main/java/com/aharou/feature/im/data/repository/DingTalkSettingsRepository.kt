package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.im.domain.DingTalkChannelConfig
import com.aharou.feature.im.domain.ImSettingsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dingTalkImDataStore by preferencesDataStore(
    name = "dingtalk_im_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 钉钉机器人接入配置（开放平台「企业内部应用 - 机器人」）。
 *
 * 只需 `clientId`（AppKey）+ `clientSecret`（AppSecret），机器人编码留空时按 clientId 处理。
 * 事件走 Stream 长连接，客户端主动外连，不用配公网回调地址。
 * 凭证由用户自填，App 不内置任何人的身份。
 */
@Singleton
class DingTalkSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {
    private companion object {
        val ENABLED_KEY = booleanPreferencesKey("dingtalk_enabled")
        val CLIENT_ID_KEY = stringPreferencesKey("dingtalk_client_id")
        val CLIENT_SECRET_KEY = stringPreferencesKey("dingtalk_client_secret")
        val ROBOT_CODE_KEY = stringPreferencesKey("dingtalk_robot_code")
        val PROXY_KEY = stringPreferencesKey("dingtalk_proxy")
        val CURSOR_KEY = stringPreferencesKey("dingtalk_cursor")
    }

    override val configFlow: Flow<DingTalkChannelConfig> = context.dingTalkImDataStore.data.map { prefs ->
        DingTalkChannelConfig(
            enabled = prefs[ENABLED_KEY] ?: false,
            clientId = prefs[CLIENT_ID_KEY] ?: "",
            // 旧版本存的是明文，decryptString 对非密文原样返回，于是不用另做迁移；下次保存时写成密文。
            clientSecret = KeystoreCipher.decryptString(prefs[CLIENT_SECRET_KEY] ?: ""),
            robotCode = prefs[ROBOT_CODE_KEY] ?: "",
            proxy = prefs[PROXY_KEY] ?: ""
        )
    }

    /** 读一次当前配置（服务循环里每轮读取）。 */
    suspend fun current(): DingTalkChannelConfig = configFlow.first()

    suspend fun setEnabled(enabled: Boolean) {
        context.dingTalkImDataStore.edit { it[ENABLED_KEY] = enabled }
    }

    suspend fun setClientId(id: String) {
        context.dingTalkImDataStore.edit { it[CLIENT_ID_KEY] = id.trim() }
    }

    /** 写入 App Secret；密钥库不可用时返回 false（不抛异常，界面据此提示重试）。 */
    suspend fun setClientSecret(secret: String): Boolean {
        val encrypted = ImSecretCrypto.encryptOrNull(secret.trim()) ?: return false
        context.dingTalkImDataStore.edit { it[CLIENT_SECRET_KEY] = encrypted }
        return true
    }

    suspend fun setRobotCode(code: String) {
        context.dingTalkImDataStore.edit {
            val value = code.trim()
            if (value.isBlank()) it.remove(ROBOT_CODE_KEY) else it[ROBOT_CODE_KEY] = value
        }
    }

    suspend fun setProxy(proxy: String) {
        context.dingTalkImDataStore.edit {
            val value = proxy.trim()
            if (value.isBlank()) it.remove(PROXY_KEY) else it[PROXY_KEY] = value
        }
    }

    // ── ImSettingsSource：编排层只认这三件事 ───────────────

    override suspend fun currentConfig(): DingTalkChannelConfig = current()

    /**
     * 游标只用于「首次运行是否丢弃积压」这一件事：Stream 没有可续传的服务端游标，
     * 存下已处理到的本地 updateId 就够了。
     */
    override suspend fun lastCursor(): String =
        context.dingTalkImDataStore.data.map { it[CURSOR_KEY] }.first().orEmpty()

    override suspend fun saveCursor(cursor: String) {
        context.dingTalkImDataStore.edit {
            if (cursor.isBlank()) it.remove(CURSOR_KEY) else it[CURSOR_KEY] = cursor
        }
    }
}
