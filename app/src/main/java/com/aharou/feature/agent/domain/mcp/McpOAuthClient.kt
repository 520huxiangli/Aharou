package com.aharou.feature.agent.domain.mcp

import android.net.Uri
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.coroutines.resumeWithException

/**
 * MCP OAuth 2.1 客户端（MCP 授权规范：RFC 9728 + RFC 8414 + RFC 7591 + RFC 7636）。
 *
 * 职责：
 * - **发现**：按 RFC 9728 取受保护资源元数据（`/.well-known/oauth-protected-resource`），
 *   再按 RFC 8414（回退 OIDC discovery）取授权服务器元数据；
 * - **客户端注册**：有 `registration_endpoint` 走 DCR（RFC 7591）；否则交回调用方要求用户手填 client_id；
 * - **PKCE**：S256（见 [McpPkce]）；
 * - **换令牌 / 续期**：`authorization_code` 换 token、`refresh_token` 续期；
 * - **持久化**：令牌与 client_secret 一律经 [KeystoreCipher] 加密后写入 `mcp.json`（复用 [McpConfigRepository]）；
 * - **令牌提供者**：给传输层产出 [McpBearerProvider]，过期前自动刷新。
 *
 * 日志绝不打印令牌明文，只记 URL 路径与 HTTP 状态码。
 */
@Singleton
class McpOAuthClient @Inject constructor(
    @Named("Mcp") private val client: OkHttpClient,
    private val configRepository: McpConfigRepository
) {
    private companion object {
        const val TAG = "McpOAuth"
        /** 过期前提前刷新的时间窗。 */
        const val EXPIRY_SKEW_MS = 60_000L
        val JSON_MEDIA = "application/json".toMediaType()
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }
    }

    /** 发现的授权服务器端点集合。 */
    data class Discovery(
        val authorizationServer: String,
        val authorizationEndpoint: String,
        val tokenEndpoint: String,
        val registrationEndpoint: String?,
        val scopesSupported: List<String>,
        val resource: String
    )

    /** 发起授权的第一步结果：需要用户手填 client_id，或已可跳转授权页。 */
    sealed interface BeginResult {
        object NeedsClientId : BeginResult
        data class AuthUrl(val url: String) : BeginResult
    }

    private data class PendingAuth(
        val base: McpOAuthConfig,
        val codeVerifier: String,
        val state: String,
        val scope: McpScope
    )

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val tokenType: String,
        val expiresIn: Long
    )

    // 同一时刻只会有一次交互式授权（UI 单飞），用 Map 兜住多 server 的并发场景。
    private val pending = HashMap<String, PendingAuth>()

    // ───────────────────────── 发现 ─────────────────────────

    /** RFC 9728 protected-resource + RFC 8414 authorization-server 元数据发现。 */
    suspend fun discover(serverUrl: String): Discovery = withContext(Dispatchers.IO) {
        val origin = originOf(serverUrl)
            ?: throw McpOAuthFlowException(McpOAuthErrorKind.DISCOVERY_FAILED, "无法解析服务器地址")
        val prm = protectedResourceUrl(serverUrl)?.let { getJson(it) }
        val resource = jsonStr(prm, "resource") ?: serverUrl
        val authServers = (prm?.get("authorization_servers") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.filter { it.isNotBlank() }.orEmpty()
        val scopes = (prm?.get("scopes_supported") as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
        val candidates = authServers.ifEmpty { listOf(origin) }

        for (authServer in candidates) {
            val meta = runCatching { fetchAuthServerMetadata(authServer) }.getOrNull() ?: continue
            val authEp = jsonStr(meta, "authorization_endpoint")
            val tokenEp = jsonStr(meta, "token_endpoint")
            if (authEp.isNullOrBlank() || tokenEp.isNullOrBlank()) continue
            return@withContext Discovery(
                authorizationServer = authServer,
                authorizationEndpoint = authEp,
                tokenEndpoint = tokenEp,
                registrationEndpoint = jsonStr(meta, "registration_endpoint")?.takeIf { it.isNotBlank() },
                scopesSupported = scopes,
                resource = resource
            )
        }
        throw McpOAuthFlowException(McpOAuthErrorKind.DISCOVERY_FAILED, "未发现授权服务器元数据")
    }

    private suspend fun fetchAuthServerMetadata(authServer: String): JsonObject? {
        val origin = originOf(authServer) ?: return null
        val path = authServer.toHttpUrlOrNull()?.encodedPath.orEmpty().trimEnd('/')
        val rfc8414 = if (path.isEmpty()) "$origin/.well-known/oauth-authorization-server"
        else "$origin/.well-known/oauth-authorization-server$path"
        getJson(rfc8414)?.let { return it }
        val oidc = if (path.isEmpty()) "$origin/.well-known/openid-configuration"
        else "$origin$path/.well-known/openid-configuration"
        return getJson(oidc)
    }

    // ───────────────────────── 客户端注册 + 构建授权 URL ─────────────────────────

    /**
     * 授权第一步：发现 + （必要时）DCR 注册 + 构建 authorization URL。
     * DCR 不可用且未提供 client_id 时返回 [BeginResult.NeedsClientId]，由 UI 让用户手填后再调用本方法。
     */
    suspend fun beginAuthorization(
        serverName: String,
        scope: McpScope,
        serverUrl: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): BeginResult = withContext(Dispatchers.IO) {
        val discovery = discover(serverUrl)
        var cid = clientId.trim()
        var secret = clientSecret.trim()
        if (cid.isEmpty()) {
            val regEndpoint = discovery.registrationEndpoint
            if (regEndpoint.isNullOrBlank()) return@withContext BeginResult.NeedsClientId
            val registered = registerClient(regEndpoint, redirectUri, discovery.scopesSupported)
            cid = registered.first
            secret = registered.second
        }
        val existing = configRepository.findEntry(serverName)?.server?.oauth
        val base = McpOAuthConfig(
            clientId = cid,
            clientSecret = encryptSecret(secret),
            authorizationEndpoint = discovery.authorizationEndpoint,
            tokenEndpoint = discovery.tokenEndpoint,
            registrationEndpoint = discovery.registrationEndpoint.orEmpty(),
            redirectUri = redirectUri,
            scope = discovery.scopesSupported.joinToString(" "),
            resource = discovery.resource,
            authorizationServer = discovery.authorizationServer,
            // 重新授权失败时保留旧令牌，别把已授权状态清掉。
            accessToken = existing?.accessToken.orEmpty(),
            refreshToken = existing?.refreshToken.orEmpty(),
            tokenType = existing?.tokenType ?: "Bearer",
            expiresAt = existing?.expiresAt ?: 0L
        )
        val verifier = McpPkce.newCodeVerifier()
        val stateValue = McpPkce.newState()
        pending[serverName] = PendingAuth(base, verifier, stateValue, scope)
        // 先落盘 clientId 与各端点：中途关页面/失败后重试不必重新注册。
        saveOAuth(serverName, scope, base)
        BeginResult.AuthUrl(buildAuthorizationUrl(base, McpPkce.codeChallengeS256(verifier), stateValue))
    }

    private fun buildAuthorizationUrl(oauth: McpOAuthConfig, codeChallenge: String, state: String): String {
        val builder = oauth.authorizationEndpoint.toHttpUrl().newBuilder()
            .addQueryParameter("response_type", "code")
            .addQueryParameter("client_id", oauth.clientId)
            .addQueryParameter("redirect_uri", oauth.redirectUri)
            .addQueryParameter("code_challenge", codeChallenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("state", state)
        if (oauth.scope.isNotBlank()) builder.addQueryParameter("scope", oauth.scope)
        if (oauth.resource.isNotBlank()) builder.addQueryParameter("resource", oauth.resource)
        return builder.build().toString()
    }

    private suspend fun registerClient(
        registrationEndpoint: String,
        redirectUri: String,
        scopes: List<String>
    ): Pair<String, String> {
        val body = buildJsonObject {
            put("client_name", "Aharou")
            put("application_type", "native")
            put("redirect_uris", buildJsonArray { add(redirectUri) })
            put("grant_types", buildJsonArray { add("authorization_code"); add("refresh_token") })
            put("response_types", buildJsonArray { add("code") })
            put("token_endpoint_auth_method", "none")
            if (scopes.isNotEmpty()) put("scope", scopes.joinToString(" "))
        }
        val req = Request.Builder()
            .url(registrationEndpoint)
            .header("Accept", "application/json")
            .post(JSON.encodeToString(JsonObject.serializer(), body).toRequestBody(JSON_MEDIA))
            .build()
        val resp = send(req)
        val obj = parseObject(resp.body)
        if (!resp.ok) {
            throw McpOAuthFlowException(McpOAuthErrorKind.DCR_FAILED, errorDetail(obj) ?: "HTTP ${resp.code}")
        }
        val cid = jsonStr(obj, "client_id")
            ?: throw McpOAuthFlowException(McpOAuthErrorKind.DCR_FAILED, "注册响应缺少 client_id")
        return cid to (jsonStr(obj, "client_secret") ?: "")
    }

    // ───────────────────────── 换令牌 ─────────────────────────

    /**
     * 授权第二步：用回调 URL 里的 code 换 token，成功后落盘并返回最新配置。
     * 回调 URL 由 WebView 在拦截到 [McpOAuthConfig.redirectUri] 时传入。
     */
    suspend fun completeAuthorization(serverName: String, redirectUrl: String): McpOAuthConfig =
        withContext(Dispatchers.IO) {
            val p = pending.remove(serverName)
                ?: throw McpOAuthFlowException(McpOAuthErrorKind.INVALID_RESPONSE, "授权会话已失效，请重新发起")
            val uri = Uri.parse(redirectUrl)
            uri.getQueryParameter("error")?.let { err ->
                throw McpOAuthFlowException(
                    McpOAuthErrorKind.AUTH_DENIED,
                    uri.getQueryParameter("error_description") ?: err
                )
            }
            val code = uri.getQueryParameter("code")
                ?: throw McpOAuthFlowException(McpOAuthErrorKind.INVALID_RESPONSE, "回调缺少 code")
            uri.getQueryParameter("state")?.let { st ->
                if (p.state.isNotBlank() && st != p.state) {
                    throw McpOAuthFlowException(McpOAuthErrorKind.INVALID_RESPONSE, "state 校验失败")
                }
            }
            val form = FormBody.Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", p.base.redirectUri)
                .add("client_id", p.base.clientId)
                .add("code_verifier", p.codeVerifier)
                .apply { if (p.base.clientSecret.isNotBlank()) add("client_secret", decrypt(p.base.clientSecret)) }
                .apply { if (p.base.resource.isNotBlank()) add("resource", p.base.resource) }
                .build()
            val tokens = postToken(p.base.tokenEndpoint, form)
            val updated = applyTokens(p.base, tokens)
            saveOAuth(serverName, p.scope, updated)
            updated
        }

    /** 续期：用 refresh_token 换新 token；返回的配置已加密，调用方直接落盘即可。 */
    private suspend fun refreshTokens(oauth: McpOAuthConfig): McpOAuthConfig = withContext(Dispatchers.IO) {
        if (!oauth.hasRefreshToken) {
            throw McpOAuthFlowException(McpOAuthErrorKind.TOKEN_EXCHANGE_FAILED, "缺少 refresh_token")
        }
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", decrypt(oauth.refreshToken))
            .add("client_id", oauth.clientId)
            .apply { if (oauth.clientSecret.isNotBlank()) add("client_secret", decrypt(oauth.clientSecret)) }
            .apply { if (oauth.resource.isNotBlank()) add("resource", oauth.resource) }
            .build()
        applyTokens(oauth, postToken(oauth.tokenEndpoint, form))
    }

    private suspend fun postToken(tokenEndpoint: String, form: FormBody): TokenResponse {
        val req = Request.Builder()
            .url(tokenEndpoint)
            .header("Accept", "application/json")
            .post(form)
            .build()
        val resp = send(req)
        val obj = parseObject(resp.body)
        if (!resp.ok) {
            throw McpOAuthFlowException(
                McpOAuthErrorKind.TOKEN_EXCHANGE_FAILED,
                errorDetail(obj) ?: "HTTP ${resp.code}"
            )
        }
        val access = jsonStr(obj, "access_token")
            ?: throw McpOAuthFlowException(
                McpOAuthErrorKind.TOKEN_EXCHANGE_FAILED,
                errorDetail(obj) ?: "响应缺少 access_token"
            )
        return TokenResponse(
            accessToken = access,
            refreshToken = jsonStr(obj, "refresh_token") ?: "",
            tokenType = jsonStr(obj, "token_type") ?: "Bearer",
            expiresIn = jsonLong(obj, "expires_in")
        )
    }

    private fun applyTokens(base: McpOAuthConfig, tokens: TokenResponse): McpOAuthConfig = base.copy(
        accessToken = encryptSecret(tokens.accessToken),
        refreshToken = if (tokens.refreshToken.isNotBlank()) encryptSecret(tokens.refreshToken) else base.refreshToken,
        tokenType = tokens.tokenType.ifBlank { "Bearer" },
        expiresAt = if (tokens.expiresIn > 0) System.currentTimeMillis() + tokens.expiresIn * 1000 else 0L
    )

    // ───────────────────────── 持久化 / 撤销 ─────────────────────────

    suspend fun currentOAuth(serverName: String): McpOAuthConfig? =
        configRepository.findEntry(serverName)?.server?.oauth

    private suspend fun saveOAuth(serverName: String, scope: McpScope, oauth: McpOAuthConfig?) {
        configRepository.updateServerOAuth(serverName, scope, oauth)
    }

    /** 撤销授权：清空令牌但保留 clientId 与端点，方便重新授权。 */
    suspend fun revoke(serverName: String, scope: McpScope) {
        val current = configRepository.findEntry(serverName)?.server?.oauth ?: return
        saveOAuth(serverName, scope, current.copy(accessToken = "", refreshToken = "", expiresAt = 0L))
        pending.remove(serverName)
    }

    // ───────────────────────── 传输层令牌提供者 ─────────────────────────

    /** 为该 server 生成令牌提供者：命中过期窗口先刷新，401 时强制刷新一次。 */
    fun bearerProvider(serverName: String): McpBearerProvider = object : McpBearerProvider {
        private val mutex = Mutex()

        override suspend fun accessToken(): String = mutex.withLock { resolve(serverName, forceRefresh = false) }

        override suspend fun refreshAfterUnauthorized(): String =
            mutex.withLock { resolve(serverName, forceRefresh = true) }
    }

    private suspend fun resolve(serverName: String, forceRefresh: Boolean): String {
        val entry = configRepository.findEntry(serverName)
            ?: throw McpException(message = "MCP server '$serverName' 不存在，无法获取 OAuth 令牌")
        val oauth = entry.server.oauth
            ?: throw McpException(message = "MCP server '$serverName' 未配置 OAuth 授权")
        if (!oauth.hasAccessToken && !forceRefresh) {
            throw McpException(message = "MCP server '$serverName' 尚未完成 OAuth 授权，请在「设置 → MCP」中授权后重试")
        }
        val expiring = oauth.expiresAt in 1 until (System.currentTimeMillis() + EXPIRY_SKEW_MS)
        if (forceRefresh || expiring) {
            if (!oauth.hasRefreshToken) {
                throw McpException(message = "MCP server '$serverName' 的 OAuth 授权已失效，请在设置中重新授权")
            }
            val refreshed = try {
                refreshTokens(oauth)
            } catch (e: Exception) {
                throw McpException(message = "MCP server '$serverName' 的 OAuth 令牌刷新失败：${e.message}", cause = e)
            }
            saveOAuth(serverName, entry.scope, refreshed)
            FileLogger.i(TAG, "[$serverName] OAuth 令牌已刷新")
            return decrypt(refreshed.accessToken)
        }
        return decrypt(oauth.accessToken)
    }

    // ───────────────────────── HTTP 辅助 ─────────────────────────

    private data class HttpResult(val code: Int, val body: String) {
        val ok: Boolean get() = code in 200..299
    }

    private suspend fun getJson(url: String): JsonObject? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/json").get().build()
        val resp = send(req)
        if (!resp.ok) return@withContext null
        parseObject(resp.body)
    }

    private suspend fun send(req: Request): HttpResult = withContext(Dispatchers.IO) {
        awaitCall(client.newCall(req)).use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) FileLogger.w(TAG, "${req.url.encodedPath} → HTTP ${resp.code}")
            HttpResult(resp.code, body)
        }
    }

    private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(
                    McpOAuthFlowException(McpOAuthErrorKind.NETWORK, e.message, e)
                )
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                    return
                }
                cont.resume(response, null)
            }
        })
    }

    private fun parseObject(body: String): JsonObject? =
        runCatching { JSON.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun jsonStr(obj: JsonObject?, key: String): String? =
        (obj?.get(key))?.jsonPrimitive?.contentOrNull

    private fun jsonLong(obj: JsonObject?, key: String): Long =
        (obj?.get(key))?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L

    private fun errorDetail(obj: JsonObject?): String? =
        jsonStr(obj, "error_description") ?: jsonStr(obj, "error") ?: jsonStr(obj, "message")

    private fun originOf(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val port = if (parsed.port == HttpUrl.defaultPort(parsed.scheme)) "" else ":${parsed.port}"
        return "${parsed.scheme}://${parsed.host}$port"
    }

    /** RFC 9728：`{origin}/.well-known/oauth-protected-resource{/resource-path}`。 */
    private fun protectedResourceUrl(serverUrl: String): String? {
        val origin = originOf(serverUrl) ?: return null
        val path = serverUrl.toHttpUrlOrNull()?.encodedPath.orEmpty().trimEnd('/')
        val suffix = if (path.isEmpty()) "" else path
        return "$origin/.well-known/oauth-protected-resource$suffix"
    }

    private fun encryptSecret(value: String): String =
        if (value.isBlank()) "" else KeystoreCipher.encryptString(value)

    private fun decrypt(value: String): String =
        runCatching { KeystoreCipher.decryptString(value) }.getOrDefault(value)
}
