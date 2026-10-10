package com.aharou.feature.account.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aharou.R
import com.aharou.core.datastore.preferencesCorruptionHandler
import com.aharou.core.security.KeystoreCipher
import com.aharou.feature.account.data.remote.AccountApiException
import com.aharou.feature.account.data.remote.AharouAccountApi
import com.aharou.feature.account.domain.model.AccountProfile
import com.aharou.feature.account.domain.model.AccountSession
import com.aharou.feature.account.domain.repository.AccountRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.accountDataStore by preferencesDataStore(
    name = "account_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

/**
 * 账号会话落盘实现。
 *
 * access token 是能直接花用户额度的凭证，按项目约定走 [KeystoreCipher] 加密后再进 DataStore；
 * 其余展示字段明文即可，被读取也没有危害。
 */
@Singleton
class AccountRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: AharouAccountApi,
    private val providerRepository: AIProviderRepository
) : AccountRepository {

    private companion object {
        val TOKEN_KEY = stringPreferencesKey("access_token")
        val USER_ID_KEY = stringPreferencesKey("user_id")
        val USERNAME_KEY = stringPreferencesKey("username")
        val EMAIL_KEY = stringPreferencesKey("email")
        val DISPLAY_NAME_KEY = stringPreferencesKey("display_name")
        val PROVIDER_ID_KEY = stringPreferencesKey("provider_id")
        /** 上次为本账号创建 provider 的归属者，换账号时据此判断要不要另建。 */
        val PROVIDER_OWNER_KEY = stringPreferencesKey("provider_owner")

        const val PROVIDER_NAME = "Aharou"
        /** 历史版本用过的内置供应商名，重新配置时自动纠正为 [PROVIDER_NAME]。 */
        val LEGACY_PROVIDER_NAMES = setOf("Aharou 内置AI")
        const val DEFAULT_MODEL = "deepseek-v4.1-flash"
        val DEFAULT_MODELS = listOf("deepseek-v4.1-flash", "glm-5.3", "kimi-k3", "grok-4.7")
    }

    override val session: Flow<AccountSession?> = context.accountDataStore.data.map { prefs ->
        val stored = prefs[TOKEN_KEY]?.takeIf { it.isNotBlank() } ?: return@map null
        val userId = prefs[USER_ID_KEY]?.toIntOrNull() ?: return@map null
        AccountSession(
            accessToken = KeystoreCipher.decryptString(stored),
            userId = userId,
            username = prefs[USERNAME_KEY].orEmpty(),
            email = prefs[EMAIL_KEY].orEmpty(),
            displayName = prefs[DISPLAY_NAME_KEY].orEmpty()
        )
    }

    override suspend fun sendVerificationCode(email: String): Result<Unit> =
        api.sendVerificationCode(email.trim())

    override suspend fun register(
        email: String,
        password: String,
        verificationCode: String
    ): Result<Unit> = api.register(email.trim(), password, verificationCode.trim())

    override suspend fun login(email: String, password: String): Result<AccountSession> =
        api.login(email.trim(), password).onSuccess { persist(it) }

    override suspend fun logout() {
        context.accountDataStore.edit { prefs ->
            val owner = prefs[PROVIDER_OWNER_KEY]
            prefs.clear()
            // provider 归属跟着会话走：会话清了就当作没配过，下次登录重新认领，
            // 免得把上一个账号的 Key 留在配置里继续被用。
            if (owner != null) prefs[PROVIDER_OWNER_KEY] = owner
        }
    }

    override suspend fun fetchProfile(): Result<AccountProfile> = runCatching {
        api.fetchProfile(requireToken()).getOrThrow()
    }

    override suspend fun redeem(redemptionCode: String): Result<Long> = runCatching {
        api.redeem(requireToken(), redemptionCode).getOrThrow()
    }

    override suspend fun provisionProvider(): Result<String> = runCatching {
        val session = currentSession()
            ?: throw AccountApiException(context.getString(R.string.account_error_not_logged_in))
        val key = api.ensureApiKey(session.accessToken, AharouAccountApi.APP_TOKEN_NAME).getOrThrow()

        val prefs = context.accountDataStore.data.first()
        val owner = prefs[PROVIDER_OWNER_KEY]?.toIntOrNull()
        val reusableId = prefs[PROVIDER_ID_KEY]
            ?.takeIf { owner == session.userId }
            ?.let { providerRepository.getProviderById(it) }

        val config = (
            reusableId ?: AIProviderConfig(
                id = UUID.randomUUID().toString(),
                name = PROVIDER_NAME,
                type = ProviderType.OPENAI,
                apiKey = key,
                baseUrl = AharouAccountApi.BASE_URL,
                defaultModel = DEFAULT_MODEL
            )
            ).copy(
            // 名字由 App 托管：重新配置一律写回 PROVIDER_NAME，不保留手工改的名字。
            name = PROVIDER_NAME,
            apiKey = key,
            baseUrl = AharouAccountApi.BASE_URL,
            // 用户改过模型或模型列表时保留他的选择，只补空缺。
            models = reusableId?.models?.takeIf { it.isNotEmpty() } ?: DEFAULT_MODELS,
            isEnabled = true
        )

        providerRepository.saveProvider(config)
        context.accountDataStore.edit { draft ->
            draft[PROVIDER_ID_KEY] = config.id
            draft[PROVIDER_OWNER_KEY] = session.userId.toString()
        }
        config.id
    }

    private suspend fun currentSession(): AccountSession? = session.first()

    private suspend fun requireToken(): String =
        currentSession()?.accessToken
            ?: throw AccountApiException(context.getString(R.string.account_error_not_logged_in))

    private suspend fun persist(session: AccountSession) {
        context.accountDataStore.edit { prefs ->
            val previousOwner = prefs[PROVIDER_OWNER_KEY]?.toIntOrNull()
            if (previousOwner != null && previousOwner != session.userId) {
                // 换了账号：上一个账号的 provider 不能继续复用。
                prefs.remove(PROVIDER_ID_KEY)
                prefs.remove(PROVIDER_OWNER_KEY)
            }
            prefs[TOKEN_KEY] = KeystoreCipher.encryptString(session.accessToken)
            prefs[USER_ID_KEY] = session.userId.toString()
            prefs[USERNAME_KEY] = session.username
            prefs[EMAIL_KEY] = session.email
            prefs[DISPLAY_NAME_KEY] = session.displayName
        }
    }
}
