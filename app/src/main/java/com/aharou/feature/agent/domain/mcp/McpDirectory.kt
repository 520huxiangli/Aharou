package com.aharou.feature.agent.domain.mcp

import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Locale

/**
 * 内置连接器目录的数据模型与读取入口。
 *
 * 目录内容放在 `assets/mcp-directory.json`，只列**远程** HTTP MCP server，用于设置页的
 * 「浏览连接器」页面：选中一条即用它的 URL/名称预填添加表单，省去手敲地址。
 */
@Serializable
enum class McpDirectoryAuth {
    /** 无需鉴权，直接可用。 */
    @SerialName("none")
    NONE,

    /** 匿名即可用（通常有限速），填令牌可提升额度。 */
    @SerialName("optional")
    OPTIONAL,

    /** 必须提供静态令牌（API Key / PAT），可直接粘进请求头。 */
    @SerialName("token")
    TOKEN,

    /** 官方只提供 OAuth 授权流程。 */
    @SerialName("oauth")
    OAUTH
}

/** 双语文本：按用户当前语言取一版，缺一版时回退到另一版。 */
@Serializable
data class McpLocalizedText(val zh: String = "", val en: String = "") {
    fun resolve(languageTag: String?): String {
        val zhPreferred = (languageTag?.takeIf { it.isNotBlank() } ?: Locale.getDefault().language)
            .startsWith("zh", ignoreCase = true)
        val primary = if (zhPreferred) zh else en
        return primary.ifBlank { if (zhPreferred) en else zh }
    }
}

@Serializable
data class McpDirectoryCategory(val id: String, val name: McpLocalizedText)

@Serializable
data class McpDirectoryServer(
    /** ASCII 标识，同时用作预填的服务器名称（需符合 function-calling 命名规范）。 */
    val id: String,
    val title: String,
    val description: McpLocalizedText,
    val url: String,
    val auth: McpDirectoryAuth = McpDirectoryAuth.NONE,
    val categories: List<String> = emptyList(),
    val docsUrl: String? = null
) {
    /**
     * 预填到添加表单的配置：带名称与 URL。OAuth 目录项额外补一个空的 oauth 块，
     * 保存后配置行即出现「授权」入口（具体授权参数在授权流程里发现/填写）。
     */
    fun toPrefillConfig(): McpServerConfig = McpServerConfig(
        name = id,
        url = url,
        oauth = if (auth == McpDirectoryAuth.OAUTH) McpOAuthConfig() else null
    )
}

@Serializable
data class McpDirectoryCatalog(
    val categories: List<McpDirectoryCategory> = emptyList(),
    val servers: List<McpDirectoryServer> = emptyList()
)

/** 目录清单读取：纯本地 assets 同步直读，零网络请求；结果进程内缓存。 */
object McpDirectoryLibrary {
    private const val TAG = "McpDirectory"
    const val ASSET_FILE_NAME = "mcp-directory.json"

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile
    private var cached: McpDirectoryCatalog? = null

    fun load(context: Context): McpDirectoryCatalog {
        cached?.let { return it }
        val parsed = runCatching {
            context.assets.open(ASSET_FILE_NAME).bufferedReader().use { it.readText() }
                .let { JSON.decodeFromString(McpDirectoryCatalog.serializer(), it) }
        }.getOrElse {
            FileLogger.w(TAG, "读取连接器目录失败: ${it.message}")
            McpDirectoryCatalog()
        }
        cached = parsed
        return parsed
    }
}
