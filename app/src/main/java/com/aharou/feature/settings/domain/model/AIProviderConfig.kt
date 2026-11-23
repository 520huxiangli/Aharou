package com.aharou.feature.settings.domain.model

import com.aharou.core.util.FileLogger
import java.util.Collections

data class AIProviderConfig(
    val id: String,
    val name: String,
    val type: ProviderType,
    val apiKey: String,
    /** 多 Key 模式开关：开启后从 [apiKeys] 轮换取用，关闭时只用 [apiKey]。 */
    val multiKeyEnabled: Boolean = false,
    /** 多 Key 模式下的候选 Key（按列表顺序优先，各条自带调度权重）。 */
    val apiKeys: List<ProviderKey> = emptyList(),
    /** 多 Key 取用策略：顺序（失败才切）或轮询（新会话轮流起步）。 */
    val keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
    /** 已废弃：早期「连续失败达阈值才切」的阈值。现在命中 [keySwitchStatusCodes] 即立即切换，保留仅为兼容旧数据库列。 */
    val keyFailoverThreshold: Int = 2,
    /** 被切走的 Key 冷却多少分钟后重新纳入候选；0 表示不冷却。 */
    val keyCooldownMinutes: Int = 5,
    /** 命中即触发多 Key 自动切换的 HTTP 状态码；留空时回退 [DEFAULT_KEY_SWITCH_STATUS_CODES]。 */
    val keySwitchStatusCodes: List<Int> = emptyList(),
    val baseUrl: String,
    val defaultModel: String,
    /** 该提供商已添加的可用模型列表（拉取或手动添加）。 */
    val models: List<String> = emptyList(),
    /** 当前选中使用的模型；为空时回退到 defaultModel。 */
    val selectedModel: String = defaultModel,
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false,
    /** Anthropic 显式缓存断点（cache_control）。仅 ANTHROPIC 类型生效，默认开启。 */
    val anthropicCacheBreakpoints: Boolean = true,
    /** Chat Completion 路径发送 prompt_cache_key（shard 路由）。仅 OPENAI 类型生效，默认关闭。 */
    val openaiChatCacheKey: Boolean = false,
    /** 自定义面板脚本路径（位于 ~/.aharou/scripts/，或绝对路径/自定义命令）。 */
    val dashboardScriptPath: String = "",
    /** 自定义面板自动刷新间隔（分钟），0 表示仅进入时/手动刷新，支持 1, 3, 5, 10 等。默认 5 分钟。 */
    val dashboardRefreshInterval: Int = 5,
    /**
     * 自定义请求头（Header 名 -> 值），完全覆盖该提供商所有请求的同名默认头。
     * 值支持占位符 `{{SESSION_ID}}`（会话 id）与 `{{API_KEY}}`（本次实际取用的 Key），
     * 发送请求前由 provider 适配器替换后写出。
     */
    val customHeaders: Map<String, String> = emptyMap(),
    /** 提供商列表排序序号，越小越靠前；-1 表示未分配（保存时取 max+1 排到末尾）。 */
    val sortOrder: Int = -1,
    /** 单独为该提供商配置代理（关闭时跟随全局代理设置）。 */
    val proxyEnabled: Boolean = false,
    val proxyType: ProxyType = ProxyType.HTTP,
    val proxyHost: String = "",
    val proxyPort: Int = 0,
    val proxyUsername: String = "",
    val proxyPassword: String = "",
    /**
     * 自定义面板 (DIY) 脚本参数（Key-Value）。
     * 执行面板脚本时注入为额外环境变量 `AICODE_KEY_<KEY>`，
     * 值支持引用提供商配置占位符，如 `{{PROVIDER_API_KEY}}`、`{{BASE_URL}}`。
     */
    val scriptParams: Map<String, String> = emptyMap(),
    /**
     * 自定义图标：本地文件绝对路径（如 `filesDir/aharou/provider-icons/<id>.png`）或 http(s) 链接。
     * 空表示按品牌名匹配内置图标。
     */
    val customIcon: String = "",
    /** 回退供应商 id：本供应商失败（额度/鉴权/限流）时自动切到它继续本轮请求；空=不回退。 */
    val fallbackProviderId: String = "",
    /** 回退时使用的模型；空=用回退供应商自己的有效模型。 */
    val fallbackModel: String = "",
    /** 声明式请求中间件配置（JSON）：模型映射、参数覆盖、系统提示注入。空串=不启用。 */
    val requestMiddleware: String = ""
) {
    /** 实际生效的模型：优先 selectedModel，其次 defaultModel。 */
    val effectiveModel: String
        get() = selectedModel.ifBlank { defaultModel }

    /**
     * 指定 Key 的生效 Base URL：该 Key 配了合法地址就用它，否则回退 [baseUrl]。
     *
     * 单条坏地址只应该影响它自己那条 Key，不能让整个 provider 报错；非法地址只记一次日志，
     * 原值照样保留，用户能在设置里看到并修正。
     */
    fun baseUrlForKey(keyValue: String?): String {
        val custom = effectiveApiKeys.firstOrNull { it.value == keyValue }?.baseUrl?.trim().orEmpty()
        if (custom.isEmpty()) return baseUrl
        if (!isValidProviderEndpoint(custom)) {
            if (warnedKeyEndpoints.add("$id|$custom")) {
                FileLogger.w(KEY_ENDPOINT_TAG, "供应商「$name」的 Key 地址缺少 http(s) 协议头，本条 Key 改用默认地址：$custom")
            }
            return baseUrl
        }
        return custom
    }

    /**
     * 实际可用的 Key 列表。多 Key 模式开启且列表非空时用 [apiKeys]，否则退回单 [apiKey]，
     * 使「开关关闭」与「开着但没填」都能落到既有单 Key 行为上。
     */
    val effectiveApiKeys: List<ProviderKey>
        get() = if (multiKeyEnabled && apiKeys.any { it.value.isNotBlank() }) {
            apiKeys.filter { it.value.isNotBlank() }
        } else {
            listOf(ProviderKey(apiKey)).filter { it.value.isNotBlank() }
        }

    /**
     * 是否可用于发起请求。除了配了 Key 的常规供应商，**空 Key 的免鉴权供应商**也算可用：
     * 这类供应商（本地网关、部分公益站等）本就不需要 Key，请求会不带鉴权头发出去。
     */
    val hasUsableApiKey: Boolean get() = effectiveApiKeys.isNotEmpty() || baseUrl.isNotBlank()

    /** 实际生效的切换状态码：留空回退默认集合。 */
    val effectiveKeySwitchStatusCodes: Set<Int>
        get() = keySwitchStatusCodes.filter { it in 100..599 }.toSet()
            .ifEmpty { DEFAULT_KEY_SWITCH_STATUS_CODES }

    /** 拉取模型列表 / 连通性测试等无会话上下文的请求用第一个可用 Key。 */
    val firstUsableApiKey: String get() = effectiveApiKeys.firstOrNull()?.value ?: ""
}

/**
 * 多 Key 模式下的一条候选 Key：值 + 调度权重 + 可选专属 Base URL。
 *
 * [weight] 参与轮询调度：越大被分到的新会话越多；≤ 0 表示该 Key 暂不参与调度（保留但不选用）。
 * [baseUrl] 是该 Key 单独走的地址（官方 Key 与中转 Key 混用时需要）；null 表示沿用提供商默认地址。
 * 用 null 而不是空串，是为了写回存储时不给「没有单独地址」的 Key 补一段空占位。
 */
data class ProviderKey(
    val value: String,
    val weight: Int = DEFAULT_KEY_WEIGHT,
    val baseUrl: String? = null
)

/** 调度权重默认值与上限。 */
const val DEFAULT_KEY_WEIGHT: Int = 1
const val MAX_KEY_WEIGHT: Int = 1_000_000

/** 已告警过的非法 Key 地址（providerId|地址）：一次请求会拼好几次 URL，不去重会把日志刷满。 */
private const val KEY_ENDPOINT_TAG = "ProviderEndpoint"
private val warnedKeyEndpoints: MutableSet<String> = Collections.synchronizedSet(HashSet())

/** 地址能否直接当 Base URL 用：拼 URL 前必须带 http(s) 协议头，否则 OkHttp 会直接抛异常。 */
fun isValidProviderEndpoint(url: String): Boolean =
    url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

/** 手填地址常见的「只有域名 / 域名加端口」形态：判为写错的地址，而不是 Key 的一部分。 */
private val ENDPOINT_LIKE_REGEX =
    Regex("""^(?:[\w-]+\.)+[A-Za-z]{2,}(?::\d+)?(?:/\S*)?$|^[\w-]+:\d+(?:/\S*)?$""")

private fun looksLikeEndpoint(raw: String): Boolean =
    raw.contains("://") || raw.startsWith("http", ignoreCase = true) || ENDPOINT_LIKE_REGEX.matches(raw)

/**
 * 解析多 Key 存储串（每行一条），格式 `值|权重|地址`。
 *
 * 后两段都可缺省，必须兼容两种旧数据：裸 `值`、`值|权重`。Key 自身含 `|` 时按旧规则
 * 「最后一个 `|` 之后不是数字就当 Key 的一部分」剥离。地址合法性留到使用时判定，这样写错的
 * 地址能在日志里被看到，而不是静默丢成默认地址。
 */
fun parseProviderKeyLines(raw: String): List<ProviderKey> =
    raw.split('\n').mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@mapNotNull null
        val parts = trimmed.split('|')
        val last = parts.last().trim()
        val value: String
        val weight: Int
        var url: String? = null
        val lastWeight = last.toIntOrNull()
        if (parts.size == 1) {
            value = last
            weight = DEFAULT_KEY_WEIGHT
        } else if (lastWeight != null) {
            // 旧格式 `值|权重`：末段一定是权重，前面的部分整体是 Key（兼容 Key 内含 `|`）。
            value = parts.dropLast(1).joinToString("|").trim()
            weight = lastWeight.coerceIn(0, MAX_KEY_WEIGHT)
        } else if (looksLikeEndpoint(last)) {
            url = last
            val head = parts.dropLast(1)
            val headWeight = head.last().trim().toIntOrNull()
            if (head.size > 1 && headWeight != null) {
                value = head.dropLast(1).joinToString("|").trim()
                weight = headWeight.coerceIn(0, MAX_KEY_WEIGHT)
            } else {
                value = head.joinToString("|").trim()
                weight = DEFAULT_KEY_WEIGHT
            }
        } else if (parts.size >= 3 && parts[parts.size - 2].trim().toIntOrNull() != null) {
            // `值|权重|地址`，但地址写错（缺协议头）：保留下来，使用侧会记日志并回退默认地址。
            value = parts.dropLast(2).joinToString("|").trim()
            weight = parts[parts.size - 2].trim().toInt().coerceIn(0, MAX_KEY_WEIGHT)
            url = last.ifEmpty { null }
        } else if (parts.size == 2) {
            // 两段且末段既不是权重也不像地址：旧行为本来就会丢掉尾段，这里当成写错的地址保留，
            // 取出的 Key 与旧行为一致。
            value = parts[0].trim()
            weight = DEFAULT_KEY_WEIGHT
            url = last.ifEmpty { null }
        } else {
            // 三段以上但倒数第二段不是权重：末段属于 Key 本身（旧规则），不当作地址。
            value = parts.dropLast(1).joinToString("|").trim()
            weight = DEFAULT_KEY_WEIGHT
        }
        if (value.isEmpty()) null else ProviderKey(value, weight, url)
    }

/** 序列化回存储串。没有单独地址的 Key 只写两段，避免每次保存都把旧配置整体改写成三段。 */
fun List<ProviderKey>.toProviderKeyLines(): String =
    joinToString("\n") { key ->
        val url = key.baseUrl?.trim().orEmpty()
        if (url.isEmpty()) "${key.value}|${key.weight}" else "${key.value}|${key.weight}|$url"
    }

/** 绝不包含空白的字段（API Key / URL / 代理主机）：连中间空白一并去掉。 */
private fun String.stripAllWhitespace(): String = filterNot { it.isWhitespace() }

/** 允许内部空格的字段（名称 / 模型名 / UA / 路径 / 代理账号）：去换行与制表符，再 trim 首尾。 */
private fun String.stripLineBreaks(): String =
    filterNot { it == '\n' || it == '\r' || it == '\t' }.trim()

/**
 * 清洗手填/粘贴的提供商配置。粘贴 API Key 常带入换行或首尾空格，直接拼进 Authorization
 * 头会被 OkHttp 拒绝（Unexpected char 0x0a in Authorization value）；baseUrl / 代理主机带空白则
 * 拼出非法 URL。写入与读取两侧各清洗一次，已存的脏数据不靠迁移也能恢复可用。
 */
fun AIProviderConfig.sanitized(): AIProviderConfig = copy(
    name = name.stripLineBreaks(),
    apiKey = apiKey.stripAllWhitespace(),
    apiKeys = apiKeys
        .map {
            it.copy(
                value = it.value.stripAllWhitespace(),
                weight = it.weight.coerceIn(0, MAX_KEY_WEIGHT),
                // 地址里的空白（粘贴带入的换行/空格）会让 URL 拼错，保存前一并清掉。
                baseUrl = it.baseUrl?.stripAllWhitespace()?.takeIf(String::isNotEmpty)
            )
        }
        .filter { it.value.isNotEmpty() }
        .distinctBy { it.value },
    keySwitchStatusCodes = keySwitchStatusCodes.filter { it in 100..599 }.distinct(),
    baseUrl = baseUrl.stripAllWhitespace(),
    defaultModel = defaultModel.stripLineBreaks(),
    models = models.map { it.stripLineBreaks() }.filter { it.isNotEmpty() }.distinct(),
    selectedModel = selectedModel.stripLineBreaks(),
    dashboardScriptPath = dashboardScriptPath.stripLineBreaks(),
    customHeaders = customHeaders
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() },
    proxyHost = proxyHost.stripAllWhitespace(),
    proxyUsername = proxyUsername.stripLineBreaks(),
    proxyPassword = proxyPassword.stripLineBreaks(),
    scriptParams = scriptParams
        .mapKeys { (k, _) -> k.trim() }
        .mapValues { (_, v) -> v.stripLineBreaks() }
        .filterKeys { it.isNotEmpty() }
)

enum class ProviderType {
    OPENAI, ANTHROPIC, GEMINI
}

/** 默认触发多 Key 自动切换的 HTTP 状态码：鉴权（401）、计费/额度（402）、权限（403）、限流（429）。 */
val DEFAULT_KEY_SWITCH_STATUS_CODES: Set<Int> = setOf(401, 402, 403, 429)

enum class KeyRotationStrategy {
    /** 顺序：始终用第一个未冷却的 Key，只有连续失败达阈值才前移。 */
    SEQUENTIAL,

    /** 轮询：新会话轮流分配起始 Key；同一会话内仍粘住同一个 Key 以保住服务端 prompt 缓存。 */
    ROUND_ROBIN
}

fun defaultProviderApiPath(type: ProviderType): String = when (type) {
    ProviderType.ANTHROPIC -> "v1/messages"
    ProviderType.GEMINI -> "v1beta"
    else -> "v1/chat/completions"
}
