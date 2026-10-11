package com.aharou.feature.connectors.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 连接器鉴权方式的规范化分类，供 UI 分流。清单里 `auth.kind` 有 oauth / key / none / auto 四值，
 * 其中 oauth 又分「服务端可动态注册」与「需用户自备 client id」，key 又分把密钥放请求头还是 URL query。
 */
enum class ConnectorAuthKind {
    /** 纯 OAuth：发现 + 动态客户端注册，点一下即走。 */
    OAUTH,

    /** OAuth，但服务端不提供动态注册，需用户去厂商后台自建应用拿 client id。 */
    OAUTH_CLIENT_ID,

    /** 密钥粘进指定请求头。 */
    KEY_HEADER,

    /** 密钥放进 URL query 参数。 */
    KEY_QUERY,

    /** 无需鉴权，直接可用。 */
    NONE,

    /** 服务端自行决定（本实现按 OAuth 尝试）。 */
    AUTO
}

/** 清单里的 `auth` 块，字段按 kind 取用。 */
@Serializable
data class ConnectorAuth(
    val kind: String,
    val clientIdRequired: Boolean = false,
    /** 需自建应用时的厂商开发者后台链接。 */
    val developer: String? = null,
    /** 密钥写入的请求头名。 */
    val header: String? = null,
    /** 请求头值前缀，如 `Bearer `。 */
    val prefix: String? = null,
    /** 密钥写入的 URL query 参数名。 */
    val query: String? = null,
    /** 密钥获取位置的人话说明。 */
    val where: String? = null
)

@Serializable
data class Connector(
    val id: String,
    val name: String,
    val category: String,
    val url: String,
    val auth: ConnectorAuth,
    val docs: String? = null,
    val icon: String? = null,
    val color: String = "#8A8A8A",
    /** 一句话简介，中英双语。 */
    val about: McpLocalizedText = McpLocalizedText()
) {
    val authKind: ConnectorAuthKind
        get() = when {
            auth.kind == "oauth" && auth.clientIdRequired -> ConnectorAuthKind.OAUTH_CLIENT_ID
            auth.kind == "oauth" -> ConnectorAuthKind.OAUTH
            auth.kind == "key" && !auth.query.isNullOrBlank() -> ConnectorAuthKind.KEY_QUERY
            auth.kind == "key" -> ConnectorAuthKind.KEY_HEADER
            auth.kind == "none" -> ConnectorAuthKind.NONE
            else -> ConnectorAuthKind.AUTO
        }

    /** 品牌色圆形标记上的首字母。 */
    val initial: String get() = name.trim().firstOrNull()?.uppercase() ?: "?"

    /**
     * 凭据库存这份密钥用的名字，由连接器 id 派生。用前缀避免与用户自建的凭据撞名；
     * 连接器 id 只含 [a-zA-Z0-9_-]，天然符合 [com.aharou.feature.vault.VaultRepository.NAME_REGEX]。
     */
    val vaultName: String get() = "connector_$id"
}

@Serializable
data class ConnectorCatalog(val connectors: List<Connector> = emptyList())

/** 连接器清单读取：纯本地 assets 同步直读，零网络请求；结果进程内缓存。 */
object ConnectorLibrary {
    private const val TAG = "ConnectorCatalog"
    const val ASSET_FILE_NAME = "connectors.json"

    /** 分类展示顺序，与清单顶层 `categories` 字段一致。 */
    val CATEGORY_ORDER = listOf("work", "talk", "files", "dev", "data", "design", "money", "search", "misc")

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cached: ConnectorCatalog? = null

    fun load(context: Context): ConnectorCatalog {
        cached?.let { return it }
        val parsed = runCatching {
            context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
                .let { JSON.decodeFromString(ConnectorCatalog.serializer(), it) }
        }.getOrElse {
            FileLogger.w(TAG, "读取连接器清单失败: ${it.message}")
            ConnectorCatalog()
        }
        cached = parsed
        return parsed
    }
}
