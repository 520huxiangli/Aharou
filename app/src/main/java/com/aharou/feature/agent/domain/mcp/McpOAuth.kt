package com.aharou.feature.agent.domain.mcp

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * 默认回调地址：自定义 scheme。授权页跑在应用内 WebView，`shouldOverrideUrlLoading` 直接拦下该地址取
 * `code`，不需要在 `AndroidManifest.xml` 注册 intent-filter（未被系统解析器接管，仅 WebView 内拦截）。
 */
const val MCP_OAUTH_CUSTOM_SCHEME_REDIRECT = "aharou-mcp://oauth/callback"

/**
 * 回环回调地址（RFC 8252 原生 App 推荐形态）。同样靠 WebView 拦截取 `code`，无需 Manifest；
 * 适用于只接受 `http://127.0.0.1` 回调的授权服务器。端口固定，便于在服务方控制台登记。
 */
const val MCP_OAUTH_LOOPBACK_REDIRECT = "http://127.0.0.1:33418/callback"

/**
 * MCP server 的 OAuth 授权配置。**所有字段都有默认值**，旧 `mcp.json`（无 `oauth` 块）可照常解析。
 *
 * 安全：[accessToken] / [refreshToken] / [clientSecret] 三个敏感字段在内存与落盘时均保存
 * `KeystoreCipher` 密文（与供应商 API Key 同一套），使用前用 `KeystoreCipher.decryptString` 解密；
 * 非密文（历史明文 / 手工编辑）按原样使用。`clientId`、各端点与 `redirectUri` 非机密，明文保存。
 */
@Serializable
data class McpOAuthConfig(
    val clientId: String = "",
    val clientSecret: String = "",
    val authorizationEndpoint: String = "",
    val tokenEndpoint: String = "",
    val registrationEndpoint: String = "",
    val redirectUri: String = MCP_OAUTH_CUSTOM_SCHEME_REDIRECT,
    val scope: String = "",
    val resource: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val tokenType: String = "Bearer",
    /** 访问令牌过期时刻（epoch millis）；0 表示未知（不主动刷新）。 */
    val expiresAt: Long = 0L,
    val authorizationServer: String = ""
) {
    val hasAccessToken: Boolean get() = accessToken.isNotBlank()
    val hasRefreshToken: Boolean get() = refreshToken.isNotBlank()
}

/** 供设置页展示的 OAuth 授权状态。 */
enum class McpOAuthStatus { NOT_AUTHORIZED, AUTHORIZED, EXPIRED }

/**
 * 从配置推算展示状态：已过期但持有 refresh_token 时仍算「已授权」（连接时会自动续期），
 * 只有过期且无 refresh_token 才标 [McpOAuthStatus.EXPIRED]。
 */
fun oauthStatusOf(oauth: McpOAuthConfig?, now: Long = System.currentTimeMillis()): McpOAuthStatus {
    if (oauth == null || !oauth.hasAccessToken) return McpOAuthStatus.NOT_AUTHORIZED
    val expired = oauth.expiresAt in 1 until now
    if (!expired) return McpOAuthStatus.AUTHORIZED
    return if (oauth.hasRefreshToken) McpOAuthStatus.AUTHORIZED else McpOAuthStatus.EXPIRED
}

/**
 * 传输层消费的令牌提供者：给出可直接放进 `Authorization: Bearer` 的明文令牌。
 * 由 [McpOAuthClient] 按 server 名生成，内部串行化刷新，避免并发请求同时续期。
 */
interface McpBearerProvider {
    /** 返回可用令牌；未授权 / 无令牌时抛 [McpException]（消息面向用户可读）。 */
    suspend fun accessToken(): String

    /** 收到 401 后强制刷新一次；无 refresh_token 或刷新失败时抛 [McpException]。 */
    suspend fun refreshAfterUnauthorized(): String
}

/** 交互式授权流程的失败分类，UI 侧据此映射成双语提示。 */
enum class McpOAuthErrorKind {
    DISCOVERY_FAILED,
    DCR_FAILED,
    NETWORK,
    TOKEN_EXCHANGE_FAILED,
    AUTH_DENIED,
    INVALID_RESPONSE,
    UNKNOWN
}

/** 授权流程失败：携带分类 + 服务端细节（细节可能为空，UI 按需拼接）。 */
class McpOAuthFlowException(
    val kind: McpOAuthErrorKind,
    val detail: String? = null,
    cause: Throwable? = null
) : Exception(detail ?: kind.name, cause)

/** PKCE（RFC 7636）code_verifier / code_challenge 与 state 生成。 */
object McpPkce {
    private val RANDOM = SecureRandom()

    fun newCodeVerifier(): String = base64Url(ByteArray(32).also { RANDOM.nextBytes(it) })

    /** S256：challenge = BASE64URL(SHA256(ASCII(verifier)))。 */
    fun codeChallengeS256(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    fun newState(): String = base64Url(ByteArray(16).also { RANDOM.nextBytes(it) })

    private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
