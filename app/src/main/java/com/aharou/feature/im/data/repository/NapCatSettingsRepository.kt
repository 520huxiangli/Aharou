package com.aharou.feature.im.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.im.domain.ImChannelConfig
import com.aharou.feature.im.domain.ImSettingsSource
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.napCatDataStore by preferencesDataStore(
    name = "qq_napcat_prefs", corruptionHandler = preferencesCorruptionHandler
)

data class NapCatConfig(
    override val enabled: Boolean = false,
    override val proxy: String = "",
    val baseUrl: String = "http://127.0.0.1:3001",
    val websocketUrl: String = "ws://127.0.0.1:3002",
    val webUiUrl: String = "http://127.0.0.1:6099",
    val token: String = "",
    val webUiToken: String = "",
    val accountId: String = "",
    val lastAccountId: String = "",
    val respondPrivate: Boolean = true,
    val respondGroups: Boolean = false,
    val requireMention: Boolean = true,
    val respondSelf: Boolean = false,
    val allowedUsers: Set<String> = emptySet(),
    val allowedGroups: Set<String> = emptySet(),
    val workspacePath: String = "",
    val providerId: String = "",
    val model: String = ""
) : ImChannelConfig {
    override val ready: Boolean get() = accountId.isNotBlank() && token.isNotBlank()
}

@Singleton
class NapCatSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) : ImSettingsSource {
    private object Keys {
        val enabled = booleanPreferencesKey("enabled")
        val proxy = stringPreferencesKey("proxy")
        val baseUrl = stringPreferencesKey("base_url")
        val websocketUrl = stringPreferencesKey("websocket_url")
        val webUiUrl = stringPreferencesKey("web_ui_url")
        val token = stringPreferencesKey("token")
        val webUiToken = stringPreferencesKey("web_ui_token")
        val accountId = stringPreferencesKey("account_id")
        val lastAccountId = stringPreferencesKey("last_account_id")
        val privateChats = booleanPreferencesKey("respond_private")
        val groups = booleanPreferencesKey("respond_groups")
        val mention = booleanPreferencesKey("require_mention")
        val self = booleanPreferencesKey("respond_self")
        val users = stringSetPreferencesKey("allowed_users")
        val allowedGroups = stringSetPreferencesKey("allowed_groups")
        val workspace = stringPreferencesKey("workspace_path")
        val provider = stringPreferencesKey("provider_id")
        val model = stringPreferencesKey("model")
        val cursor = stringPreferencesKey("cursor")
    }

    override val configFlow: Flow<NapCatConfig> = context.napCatDataStore.data.map { p ->
        NapCatConfig(
            enabled = p[Keys.enabled] ?: false,
            proxy = p[Keys.proxy].orEmpty(),
            baseUrl = p[Keys.baseUrl] ?: "http://127.0.0.1:3001",
            websocketUrl = p[Keys.websocketUrl] ?: "ws://127.0.0.1:3002",
            webUiUrl = p[Keys.webUiUrl] ?: "http://127.0.0.1:6099",
            token = KeystoreCipher.decryptString(p[Keys.token].orEmpty()),
            webUiToken = KeystoreCipher.decryptString(p[Keys.webUiToken].orEmpty()),
            accountId = p[Keys.accountId].orEmpty(),
            lastAccountId = p[Keys.lastAccountId] ?: p[Keys.accountId].orEmpty(),
            respondPrivate = p[Keys.privateChats] ?: true,
            respondGroups = p[Keys.groups] ?: false,
            requireMention = p[Keys.mention] ?: true,
            respondSelf = p[Keys.self] ?: false,
            allowedUsers = p[Keys.users].orEmpty(),
            allowedGroups = p[Keys.allowedGroups].orEmpty(),
            workspacePath = p[Keys.workspace].orEmpty(),
            providerId = p[Keys.provider].orEmpty(),
            model = p[Keys.model].orEmpty()
        )
    }

    suspend fun current(): NapCatConfig = configFlow.first()
    override suspend fun currentConfig(): ImChannelConfig = current()
    suspend fun setEnabled(value: Boolean) { context.napCatDataStore.edit { it[Keys.enabled] = value } }
    suspend fun setProxy(value: String) = setString(Keys.proxy, value)
    suspend fun setBaseUrl(value: String) = setString(Keys.baseUrl, value)
    suspend fun setWebsocketUrl(value: String) = setString(Keys.websocketUrl, value)
    suspend fun setWebUiUrl(value: String) = setString(Keys.webUiUrl, value)
    suspend fun setToken(value: String): Boolean = setSecret(Keys.token, value)
    suspend fun setWebUiToken(value: String): Boolean = setSecret(Keys.webUiToken, value)

    suspend fun setConnection(baseUrl: String, webUiUrl: String, token: String, webUiToken: String) {
        val encryptedToken = KeystoreCipher.encryptString(token.trim())
        val encryptedWebToken = KeystoreCipher.encryptString(webUiToken.trim())
        context.napCatDataStore.edit {
            it[Keys.baseUrl] = baseUrl.trim()
            it[Keys.webUiUrl] = webUiUrl.trim()
            it[Keys.token] = encryptedToken
            it[Keys.webUiToken] = encryptedWebToken
        }
    }

    suspend fun setAccountId(value: String) {
        val account = value.trim()
        require(account.isEmpty() || account.matches(Regex("[0-9]{5,20}"))) { "Invalid QQ account ID" }
        context.napCatDataStore.edit {
            if (it[Keys.accountId] != account) it.remove(Keys.cursor)
            if (account.isNotEmpty()) it[Keys.lastAccountId] = account
            else if (it[Keys.lastAccountId] == null) it[Keys.lastAccountId] = it[Keys.accountId].orEmpty()
            it[Keys.accountId] = account
        }
    }

    suspend fun setLastAccountId(value: String) {
        require(value.matches(Regex("[0-9]{5,20}"))) { "Invalid QQ recovery account" }
        context.napCatDataStore.edit { it[Keys.lastAccountId] = value }
    }

    suspend fun clearLoginRecovery() {
        context.napCatDataStore.edit {
            it[Keys.accountId] = ""
            it[Keys.lastAccountId] = ""
            it.remove(Keys.cursor)
        }
    }

    suspend fun updatePolicy(
        respondPrivate: Boolean, respondGroups: Boolean, requireMention: Boolean, respondSelf: Boolean,
        allowedUsers: Set<String>, allowedGroups: Set<String>
    ) {
        val users = allowedUsers.map(String::trim).filter(String::isNotEmpty).toSet()
        val groups = allowedGroups.map(String::trim).filter(String::isNotEmpty).toSet()
        require((users + groups).all { value -> value.all { it in '0'..'9' } }) { "QQ account and group IDs must be numeric strings" }
        context.napCatDataStore.edit {
            it[Keys.privateChats] = respondPrivate
            it[Keys.groups] = respondGroups
            it[Keys.mention] = requireMention
            it[Keys.self] = respondSelf
            it[Keys.users] = users
            it[Keys.allowedGroups] = groups
        }
    }

    suspend fun setSessionDefaults(workspacePath: String, providerId: String, model: String) {
        require(providerId.isBlank() == model.isBlank()) { "Provider and model must be set together" }
        require(workspacePath.isBlank() || workspacePath.trim().startsWith('/')) { "Workspace must be an absolute path" }
        context.napCatDataStore.edit {
            it[Keys.workspace] = workspacePath.trim()
            it[Keys.provider] = providerId.trim()
            it[Keys.model] = model.trim()
        }
    }

    override suspend fun lastCursor(): String = context.napCatDataStore.data.first()[Keys.cursor].orEmpty()
    override suspend fun saveCursor(cursor: String) = setString(Keys.cursor, cursor)

    private suspend fun setString(key: Preferences.Key<String>, value: String) {
        context.napCatDataStore.edit { it[key] = value.trim() }
    }

    private suspend fun setSecret(key: Preferences.Key<String>, value: String): Boolean {
        val encrypted = ImSecretCrypto.encryptOrNull(value.trim()) ?: return false
        context.napCatDataStore.edit { it[key] = encrypted }
        return true
    }
}
