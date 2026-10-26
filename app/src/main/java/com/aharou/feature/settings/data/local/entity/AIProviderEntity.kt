package com.aharou.feature.settings.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** 明文 Room，与 git token 同口径；后续统一加密时一并处理。 */
    val apiKey: String,
    /** 多 Key 模式开关；关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 候选列表，以换行分隔持久化（同 [models]）。 */
    val apiKeys: String = "",
    /** 多 Key 取用策略：SEQUENTIAL / ROUND_ROBIN。 */
    val keyRotationStrategy: String = "SEQUENTIAL",
    /** 已废弃：「连续失败达阈值才切」的旧阈值，保留列以兼容旧数据库（SQLite 3.18 无 DROP COLUMN）。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却分钟数；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    /** 命中即触发多 Key 自动切换的 HTTP 状态码，以逗号分隔持久化；空串表示用默认值。 */
    val keySwitchStatusCodes: String = "",
    val baseUrl: String,
    val defaultModel: String,
    /** 可用模型列表，以换行分隔持久化。 */
    val models: String = "",
    /** 当前选中模型；为空时回退到 defaultModel。 */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic 显式缓存断点（cache_control）。仅 ANTHROPIC 类型使用，默认开启。 */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型使用，默认关闭（官方 API 不接受该字段）。 */
    val openaiChatCacheKey: Boolean = false,
    /** 自定义面板脚本路径。列名沿用历史命名以兼容已发布数据库。 */
    @ColumnInfo(name = "balanceScriptPath")
    val dashboardScriptPath: String = "",
    /** 自定义面板自动刷新间隔（分钟）。默认 5 分钟。列名沿用历史命名以兼容已发布数据库。 */
    @ColumnInfo(name = "balanceRefreshInterval")
    val dashboardRefreshInterval: Int = 5,
    /** 自定义请求头（JSON 编码的 Map<Header 名, 值>，空为 ""），完全覆盖同名默认头。 */
    val customHeaders: String = "",
    /** 提供商列表排序序号，越小越靠前；新建时分配 max+1。 */
    val sortOrder: Int = 0,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: String = "HTTP",
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /** 自定义面板 (DIY) 脚本参数（JSON 编码的 Map<String, String>，空为 ""）。 */
    val scriptParams: String = "",
    /**
     * 自定义图标：本地文件绝对路径或 http(s) 链接，空表示按品牌名匹配内置图标。
     * 表里的列名就是 customIcon（未改 @ColumnInfo）。
     */
    val customIcon: String = "",
    /**
     * 回退供应商 id：本供应商请求失败（额度耗尽/鉴权/限流）时自动切到它继续本轮请求；
     * 空串表示不回退。存 id 而非对象，避免循环引用与删除时的一致性麻烦。
     */
    val fallbackProviderId: String = "",
    /** 回退时使用的模型；空串表示用回退供应商自己的有效模型。 */
    val fallbackModel: String = "",
    /** 声明式请求中间件配置（JSON）；空串表示不启用。schema 见 domain/middleware/MiddlewareConfig。 */
    val requestMiddleware: String = ""
)
