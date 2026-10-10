package com.aharou.feature.account.data.remote

import android.content.Context
import com.aharou.R
import com.aharou.feature.account.domain.model.AccountProfile
import com.aharou.feature.account.domain.model.AccountSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 后端错误，message 为后端原文（多为英文），由上层决定如何展示。 */
class AccountApiException(message: String) : RuntimeException(message)

/**
 * 自建账号后端（New API 站点）的 REST 客户端。
 *
 * 与聊天用的 Retrofit 适配器无关，直接复用全局 OkHttpClient：后端除了标准 HTTP 状态码，
 * 还会用「200 + success=false」表达业务失败（余额不足、兑换码无效等），只看状态码会把失败当成功。
 */
@Singleton
class AharouAccountApi @Inject constructor(
    private val client: OkHttpClient,
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /**
     * 站点地址，不含路径尾斜杠；后端接口与 OpenAI 兼容接口都挂在这个地址下。
     *
     * 暂用裸 IP：域名 aharou.cloud 未完成 ICP 备案，被腾讯云 DNSPod 按 Host 拦截（webblock），
     * 而裸 IP 的 80 端口不受影响。备案通过后必须改回 `https://aharou.cloud` ——
     * 明文 HTTP 会把登录密码与 access token 裸传，只允许备案期间自用。
     */
    companion object {
        const val BASE_URL = "http://1.117.60.254"

        /** App 自动创建的令牌名，用于在既有令牌里认出我们建的那一个（后端不返回新建令牌的 id）。 */
        const val APP_TOKEN_NAME = "Aharou App"
    }

    /** 发送邮箱验证码。后端对非法邮箱也返回业务错误，同样按失败处理。 */
    suspend fun sendVerificationCode(email: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            execute(get("/api/verification?email=${enc(email)}"))
        }.map { }
    }

    /** 邮箱 + 验证码 + 密码注册。 */
    suspend fun register(email: String, password: String, verificationCode: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("username", email)
                    put("password", password)
                    put("email", email)
                    put("verification_code", verificationCode)
                }
                execute(post("/api/user/register", body))
            }.map { }
        }

    /** 邮箱 + 密码登录。 */
    suspend fun login(email: String, password: String): Result<AccountSession> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject {
                    put("username", email)
                    put("password", password)
                }
                val data = execute(post("/api/user/login", body)).obj("data")
                    ?: throw AccountApiException(context.getString(R.string.account_error_no_session))
                val user = data.obj("user")
                AccountSession(
                    accessToken = data.str("access_token"),
                    userId = user?.int("id") ?: 0,
                    username = user?.str("username").orEmpty(),
                    email = user?.str("email").orEmpty(),
                    displayName = user?.str("display_name").orEmpty()
                )
            }.mapCatching { session ->
                if (session.accessToken.isBlank()) {
                    throw AccountApiException(context.getString(R.string.account_error_no_session))
                }
                session
            }
        }

    /** 拉取当前账户的余额与用量。 */
    suspend fun fetchProfile(accessToken: String): Result<AccountProfile> =
        withContext(Dispatchers.IO) {
            runCatching {
                val data = execute(get("/api/user/self", accessToken)).obj("data")
                    ?: throw AccountApiException(context.getString(R.string.account_error_no_session))
                AccountProfile(
                    userId = data.int("id") ?: 0,
                    username = data.str("username").orEmpty(),
                    email = data.str("email").orEmpty(),
                    displayName = data.str("display_name").orEmpty(),
                    quota = data.long("quota"),
                    usedQuota = data.long("used_quota"),
                    requestCount = data.long("request_count")
                )
            }
        }

    /** 兑换码充值，成功时返回本次到账的额度（与账户 quota 同一单位）。 */
    suspend fun redeem(accessToken: String, redemptionCode: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = buildJsonObject { put("key", redemptionCode.trim()) }
                val root = execute(post("/api/user/topup", body, accessToken))
                root["data"]?.jsonPrimitive?.longOrNull
                    ?: throw AccountApiException(context.getString(R.string.account_redeem_failed))
            }
        }

    /**
     * 取回（必要时创建）属于本账号的 API Key。
     *
     * 后端创建令牌的接口不返回新令牌 id，只能建完再列出来按名字认领；已有同名令牌时直接复用它，
     * 避免用户每登录一次就多出一个令牌。
     */
    suspend fun ensureApiKey(accessToken: String, tokenName: String): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val existing = findTokenId(accessToken, tokenName)
                val tokenId = existing ?: run {
                    val body = buildJsonObject {
                        put("name", tokenName)
                        // 无限额度 + 永不过期：额度由账户本身控制，令牌再设一轮限额只会互相打架。
                        put("unlimited_quota", true)
                        put("expired_time", -1L)
                    }
                    execute(post("/api/token/", body, accessToken))
                    findTokenId(accessToken, tokenName)
                        ?: throw AccountApiException(context.getString(R.string.account_error_token_missing))
                }
                val key = execute(
                    post("/api/token/$tokenId/key", buildJsonObject { }, accessToken)
                ).obj("data")?.str("key").orEmpty()
                if (key.isBlank()) {
                    throw AccountApiException(context.getString(R.string.account_error_token_missing))
                }
                key
            }
        }

    /** 在既有令牌里按名字找 id；列表接口在不同版本下可能是数组或分页对象，两种都兜。 */
    private fun findTokenId(accessToken: String, tokenName: String): Int? {
        val root = execute(get("/api/token/?p=0&size=100", accessToken))
        val data = root["data"] ?: return null
        val items = when (data) {
            is JsonArray -> data
            is JsonObject -> (data["items"] ?: data["records"] ?: data["data"]) as? JsonArray
            else -> null
        } ?: return null
        return items.mapNotNull { it.jsonObject }
            .firstOrNull { it.str("name") == tokenName }
            ?.int("id")
    }

    private fun get(path: String, token: String? = null): Request =
        buildRequest(path, "GET", null, token)

    private fun post(path: String, body: JsonObject, token: String? = null): Request =
        buildRequest(path, "POST", json.encodeToString(JsonObject.serializer(), body), token)

    private fun buildRequest(path: String, method: String, body: String?, token: String?): Request =
        Request.Builder()
            .url("$BASE_URL$path")
            .apply { if (token != null) header("Authorization", "Bearer $token") }
            .apply {
                if (body != null) {
                    method(method, body.toRequestBody(jsonMedia))
                } else {
                    method(method, null)
                }
            }
            .build()

    /**
     * 执行请求并把响应体解析成对象。业务失败会抛 [AccountApiException]：
     * 后端把失败原因放在 message 里，直接丢弃会只剩一句无用的 HTTP 200。
     */
    private fun execute(request: Request): JsonObject {
        val (code, text) = client.newCall(request).execute().use { response ->
            response.code to response.body?.string().orEmpty()
        }
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw AccountApiException("HTTP $code: ${text.take(160)}")
        if (code !in 200..299) {
            throw AccountApiException(root.str("message").ifBlank { "HTTP $code" })
        }
        if (root["success"]?.jsonPrimitive?.contentOrNull == "false") {
            throw AccountApiException(root.str("message").ifBlank { "HTTP $code" })
        }
        return root
    }

    private fun JsonObject.obj(key: String): JsonObject? = this[key]?.let {
        runCatching { it.jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull()

    private fun JsonObject.long(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")
}
