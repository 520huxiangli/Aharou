package com.aharou.feature.credentials.data.remote

import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** 内置 GitHub App 的公开参数（Client ID / slug 属公开信息，非密钥）。 */
object GitHubAppConfig {
    const val CLIENT_ID = "Iv23liRFlvafoZphgslm"
    const val SLUG = "aharou"
    const val INSTALL_URL = "https://github.com/apps/aharou/installations/new"
}

/** 设备码申请结果。[intervalSeconds] 为两次轮询之间的最小间隔。 */
data class GitHubDeviceCode(
    val deviceCode: String,
    val userCode: String,
    val verificationUri: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int
)

/** 轮询访问令牌的结果，映射 GitHub OAuth 设备流的各分支。 */
sealed interface GitHubTokenPollResult {
    /** 用户已完成授权，拿到 user access token。 */
    data class Success(val accessToken: String, val scope: String) : GitHubTokenPollResult

    /** 用户尚未完成授权，按 [intervalSeconds] 继续轮询。 */
    data object Pending : GitHubTokenPollResult

    /** 轮询过快，调用方应把间隔 +5 秒后再试。 */
    data object SlowDown : GitHubTokenPollResult

    /** 设备码已过期，需重新申请。 */
    data object Expired : GitHubTokenPollResult

    /** 用户拒绝了授权。 */
    data object Denied : GitHubTokenPollResult

    /** 其它错误，[code] 为 GitHub 返回的 error 字段。 */
    data class Error(val code: String) : GitHubTokenPollResult
}

/**
 * GitHub App 设备码流（Device Flow）客户端：无需回调地址，用户在浏览器输入设备码即可授权。
 *
 * 全部请求显式带 `Accept: application/json`（否则 GitHub 返回表单编码）。轮询间隔与过期由调用方控制，
 * 这里只负责单次请求与响应解析；令牌的落盘走 [com.aharou.feature.credentials.domain.repository.CredentialRepository]，
 * 因此容器 git 与 PR/CI 会自动复用。
 */
@Singleton
class GitHubDeviceFlowClient @Inject constructor() {

    private companion object {
        const val TAG = "GitHubDeviceFlow"
        const val DEVICE_CODE_URL = "https://github.com/login/device/code"
        const val ACCESS_TOKEN_URL = "https://github.com/login/oauth/access_token"
        const val API_BASE = "https://api.github.com"
        const val ACCEPT_JSON = "application/json"
        const val ACCEPT_API = "application/vnd.github+json"
        const val API_VERSION = "2022-11-28"
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"

        /** GitHub 设备码申请的默认轮询间隔（秒）。 */
        const val DEFAULT_INTERVAL_SECONDS = 5
    }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    /** 申请设备码与用户码。失败抛 [IOException]。 */
    suspend fun requestDeviceCode(): GitHubDeviceCode = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GitHubAppConfig.CLIENT_ID)
            .build()
        parseDeviceCode(postForm(DEVICE_CODE_URL, form))
    }

    /** 单次轮询访问令牌。失败（网络/非 2xx）抛 [IOException]，调用方可继续重试。 */
    suspend fun pollAccessToken(deviceCode: String): GitHubTokenPollResult = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("client_id", GitHubAppConfig.CLIENT_ID)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT)
            .build()
        parseAccessTokenResponse(postForm(ACCESS_TOKEN_URL, form))
    }

    /** 用 user token 取登录名；失败返回 null。 */
    suspend fun fetchUserLogin(token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val obj = JsonParser.parseString(apiGet("$API_BASE/user", token)).asJsonObject
            obj.get("login")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** 该 token 对应的账号是否已安装本 GitHub App（按 slug 比对）。失败时返回 true（不误报需安装）。 */
    suspend fun isAppInstalled(token: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val root = JsonParser.parseString(apiGet("$API_BASE/user/installations", token)).asJsonObject
            val arr = root.getAsJsonArray("installations") ?: return@runCatching false
            arr.any { el ->
                el.takeIf { it.isJsonObject }?.asJsonObject
                    ?.get("app_slug")?.takeIf { !it.isJsonNull }?.asString == GitHubAppConfig.SLUG
            }
        }.getOrDefault(true)
    }

    private fun parseDeviceCode(body: String): GitHubDeviceCode {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
            ?: throw IOException("设备码响应无法解析")
        val deviceCode = obj.get("device_code")?.takeIf { !it.isJsonNull }?.asString
        val userCode = obj.get("user_code")?.takeIf { !it.isJsonNull }?.asString
        if (deviceCode.isNullOrBlank() || userCode.isNullOrBlank()) {
            val err = obj.get("error")?.takeIf { !it.isJsonNull }?.asString ?: "invalid_response"
            throw IOException("设备码申请失败：$err")
        }
        return GitHubDeviceCode(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = obj.get("verification_uri")?.takeIf { !it.isJsonNull }?.asString
                ?: "https://github.com/login/device",
            expiresInSeconds = obj.get("expires_in")?.takeIf { !it.isJsonNull }?.asInt ?: 900,
            intervalSeconds = obj.get("interval")?.takeIf { !it.isJsonNull }?.asInt
                ?.coerceAtLeast(1) ?: DEFAULT_INTERVAL_SECONDS
        )
    }

    /** POST 表单请求；2xx 返回正文，其余抛 [IOException]。 */
    private fun postForm(url: String, form: FormBody): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_JSON)
            .post(form)
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                text
            }
        } catch (e: IOException) {
            FileLogger.w(TAG, "GitHub 设备流请求异常：$url", e)
            throw e
        }
    }

    /** GitHub REST GET；2xx 返回正文，其余抛 [IOException]。 */
    private fun apiGet(url: String, token: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_API)
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                text
            }
        } catch (e: IOException) {
            FileLogger.w(TAG, "GitHub REST 请求异常：$url", e)
            throw e
        }
    }
}

/**
 * 解析 access_token 端点返回的 JSON（纯函数，便于 JVM 单测）。
 *
 * 成功为 `{"access_token":"...","token_type":"bearer","scope":""}`；未完成授权等为
 * `{"error":"authorization_pending","error_description":"...","interval":5}`。
 */
internal fun parseAccessTokenResponse(body: String): GitHubTokenPollResult {
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        ?: return GitHubTokenPollResult.Error("invalid_response")
    val token = obj.get("access_token")?.takeIf { !it.isJsonNull }?.asString
    if (!token.isNullOrBlank()) {
        val scope = obj.get("scope")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return GitHubTokenPollResult.Success(token, scope)
    }
    val error = obj.get("error")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    return when (error) {
        "authorization_pending" -> GitHubTokenPollResult.Pending
        "slow_down" -> GitHubTokenPollResult.SlowDown
        "expired_token" -> GitHubTokenPollResult.Expired
        "access_denied" -> GitHubTokenPollResult.Denied
        "" -> GitHubTokenPollResult.Error("invalid_response")
        else -> GitHubTokenPollResult.Error(error)
    }
}
