package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigCollection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.core.config.fields.ReadOnlyField
import com.aharou.feature.settings.data.local.CustomModelMetadataStore
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ModelMetadata
import com.aharou.feature.settings.domain.model.DEFAULT_KEY_WEIGHT
import com.aharou.feature.settings.domain.model.KeyRotationStrategy
import com.aharou.feature.settings.domain.model.MAX_KEY_WEIGHT
import com.aharou.feature.settings.domain.model.ProviderKey
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.ProxyType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 供应商 → 配置通道（集合式）。
 *
 * 供应商是「按 id 动态成组」的记录，所以走 [ConfigCollection]：`config set
 * providers.<id>.apiKey xxx` 按子字段读写，`config add providers {...}` / `config remove
 * providers <id>` 增删整条。
 *
 * 集合的 childIds/fields/add/remove 都是同步签名，而仓库是 suspend + Room，所以统一
 * runBlocking（与 [com.aharou.core.config.fields.DataStoreBoolField] 同一取舍）。
 */
@Singleton
class ConfigProviderFields @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val modelMetadataStore: CustomModelMetadataStore,
    private val providers: AIProviderRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(ProviderCollection())
    }

    private fun snapshot(id: String): AIProviderConfig? =
        runBlocking { providers.getProviderById(id) }

    private fun save(updated: AIProviderConfig) =
        runBlocking { providers.saveProvider(updated) }

    /** 解析多 Key 串：每行 `值|权重`，与 AIProviderRepositoryImpl 的存储格式一致。 */
    private fun parseProviderKeys(text: String): List<ProviderKey> =
        text.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val sep = trimmed.lastIndexOf('|')
            if (sep < 0) return@mapNotNull ProviderKey(trimmed)
            val value = trimmed.substring(0, sep).trim()
            if (value.isEmpty()) return@mapNotNull null
            val weight = trimmed.substring(sep + 1).trim().toIntOrNull() ?: DEFAULT_KEY_WEIGHT
            ProviderKey(value, weight.coerceIn(0, MAX_KEY_WEIGHT))
        }

    /** 解析「名: 值」逐行的键值对，忽略空行与没有冒号的行。 */
    private fun parsePairs(text: String): Map<String, String> =
        text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains(':') }
            .associate { line ->
                val sep = line.indexOf(':')
                line.substring(0, sep).trim() to line.substring(sep + 1).trim()
            }

    private inner class ProviderCollection : ConfigCollection {
        override val basePath = "providers"
        override val displayName = "AI 供应商"
        override val description =
            "已配置的供应商列表。子字段：name / baseUrl / apiKey / defaultModel / selectedModel / " +
                "panelScript / enabled / multiKeyEnabled / apiKeys / keyRotationStrategy / " +
                "keyCooldownMinutes / keySwitchStatusCodes / proxyEnabled / proxyType / proxyHost / " +
                "proxyPort / proxyUsername / proxyPassword / useFullUrl / useResponseApi / customHeaders / " +
                "sortOrder（只读）/ dashboardRefreshInterval / anthropicCacheBreakpoints / openaiChatCacheKey。"

        override fun childIds(): List<String> =
            runBlocking { providers.getAllProviders().first() }.map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            val exists = snapshot(forId) != null
            fun str(
                field: String,
                name: String,
                desc: String,
                schema: ConfigSchema = ConfigSchema.Str(),
                risk: ConfigRisk = ConfigRisk.NORMAL,
                read: (AIProviderConfig) -> String,
                write: (AIProviderConfig, String) -> AIProviderConfig,
            ) = ClosureField(
                path = "$basePath.$forId.$field",
                displayName = "$name（$forId）",
                description = desc,
                valueSchema = schema,
                risk = risk,
                revertable = true,
                reader = { ConfigValue.Str(snapshot(forId)?.let(read).orEmpty()) },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val current = snapshot(forId) ?: throw ConfigError.InvalidValue("供应商不存在：$forId")
                    save(write(current, s))
                },
            )

            fun bool(
                field: String,
                name: String,
                desc: String,
                read: (AIProviderConfig) -> Boolean,
                write: (AIProviderConfig, Boolean) -> AIProviderConfig,
            ) = ClosureField(
                path = "$basePath.$forId.$field",
                displayName = "$name（$forId）",
                description = desc,
                valueSchema = ConfigSchema.Bool,
                revertable = true,
                reader = { ConfigValue.Bool(snapshot(forId)?.let(read) ?: false) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                    val current = snapshot(forId) ?: throw ConfigError.InvalidValue("供应商不存在：$forId")
                    save(write(current, b))
                },
            )

            fun int(
                field: String,
                name: String,
                desc: String,
                min: Int? = null,
                max: Int? = null,
                read: (AIProviderConfig) -> Int,
                write: (AIProviderConfig, Int) -> AIProviderConfig,
            ) = ClosureField(
                path = "$basePath.$forId.$field",
                displayName = "$name（$forId）",
                description = desc,
                valueSchema = ConfigSchema.Int(min = min, max = max),
                revertable = true,
                reader = { ConfigValue.Int(snapshot(forId)?.let(read) ?: 0) },
                writer = { v ->
                    val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("integer")
                    val current = snapshot(forId) ?: throw ConfigError.InvalidValue("供应商不存在：$forId")
                    save(write(current, i))
                },
            )

            fun enumField(
                field: String,
                name: String,
                desc: String,
                cases: List<String>,
                read: (AIProviderConfig) -> String,
                write: (AIProviderConfig, String) -> AIProviderConfig,
            ) = ClosureField(
                path = "$basePath.$forId.$field",
                displayName = "$name（$forId）",
                description = desc,
                valueSchema = ConfigSchema.StrEnum(cases),
                revertable = true,
                reader = { ConfigValue.Str(snapshot(forId)?.let(read).orEmpty()) },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val current = snapshot(forId) ?: throw ConfigError.InvalidValue("供应商不存在：$forId")
                    save(write(current, s))
                },
            )

            if (!exists) return emptyList()

            return listOf(
                str(
                    field = "name",
                    name = "显示名",
                    desc = "设置页里显示的供应商名称。",
                    read = { it.name },
                    write = { p, s -> p.copy(name = s) },
                ),
                str(
                    field = "base_url",
                    name = "接口地址",
                    desc = "API base URL；配合 useFullUrl 时可直接填完整请求地址。",
                    read = { it.baseUrl },
                    write = { p, s -> p.copy(baseUrl = s) },
                ),
                str(
                    field = "api_key",
                    name = "API Key",
                    desc = "该供应商的密钥（多 Key 模式关闭时使用）。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.apiKey },
                    write = { p, s -> p.copy(apiKey = s) },
                ),
                str(
                    field = "default_model",
                    name = "默认模型",
                    desc = "未显式选模型时使用的模型 id。",
                    read = { it.defaultModel },
                    write = { p, s -> p.copy(defaultModel = s) },
                ),
                str(
                    field = "selected_model",
                    name = "当前模型",
                    desc = "当前选中的模型；空则回退 defaultModel。",
                    read = { it.selectedModel },
                    write = { p, s -> p.copy(selectedModel = s) },
                ),
                str(
                    field = "panel_script",
                    name = "面板脚本",
                    desc = "输入框上方自定义面板的脚本路径（放在 ~/.aharou/scripts/ 下，支持 Python / Bash / Node）；留空则隐藏面板。",
                    read = { it.dashboardScriptPath },
                    write = { p, s -> p.copy(dashboardScriptPath = s) },
                ),
                str(
                    field = "fallback_provider_id",
                    name = "回退供应商 id",
                    desc = "本供应商请求失败（额度耗尽 / 鉴权 / 限流）时自动切到它继续本轮；留空不回退。填的是供应商 id（可用 config list 查）。",
                    read = { it.fallbackProviderId },
                    write = { p, s -> p.copy(fallbackProviderId = s.trim()) },
                ),
                str(
                    field = "fallback_model",
                    name = "回退模型",
                    desc = "回退时使用的模型名；留空则用回退供应商自己的有效模型。",
                    read = { it.fallbackModel },
                    write = { p, s -> p.copy(fallbackModel = s.trim()) },
                ),
                ClosureField(
                    path = "$basePath.$forId.model_metadata",
                    displayName = "模型显示名与图标（$forId）",
                    description = "JSON：{\"模型名\":{\"displayName\":\"别名\",\"customIcon\":\"/路径或链接\"}}。" +
                        "只覆盖显示名与图标两项，合并写入、其它元数据（价格/窗口/能力）不受影响；非法 JSON 直接报错不落盘。",
                    valueSchema = ConfigSchema.Json,
                    risk = ConfigRisk.NORMAL,
                    revertable = false,
                    reader = {
                        val map = runBlocking { modelMetadataStore.all() }
                        val own = map.entries
                            .filter { it.key.startsWith("$forId:") }
                            .associate { it.key.substringAfter(':') to it.value }
                        ConfigValue.Str(buildMetadataJson(own))
                    },
                    writer = { v ->
                        val raw = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                        val parsed = parseMetadataPatch(raw)
                            ?: throw ConfigError.InvalidValue("model_metadata 需要 JSON 对象：{\"模型名\":{\"displayName\":\"...\"}}")
                        runBlocking {
                            val current = modelMetadataStore.all()
                            parsed.forEach { (model, patch) ->
                                val key = "$forId:$model"
                                val base = current[key] ?: ModelMetadata(id = model, contextTokens = 0)
                                modelMetadataStore.put(forId, model, base.copy(
                                    displayName = patch.first ?: base.displayName,
                                    customIcon = patch.second ?: base.customIcon
                                ))
                            }
                        }
                        null
                    },
                ),
                str(
                    field = "request_middleware",
                    name = "请求中间件",
                    desc = "JSON 配置：{\"modelMap\":{\"别名\":\"真实模型\"},\"systemPrompt\":\"追加的系统提示\",\"systemPromptTargets\":[\"openai\"]}。留空不启用；参数覆盖（paramOverrides）暂未接入请求体。",
                    read = { it.requestMiddleware },
                    write = { p, s -> p.copy(requestMiddleware = s.trim()) },
                ),
                ClosureField(
                    path = "$basePath.$forId.enabled",
                    displayName = "启用（$forId）",
                    description = "关闭后该供应商不出现在模型选择列表里。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(snapshot(forId)?.isEnabled ?: false) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("boolean")
                        runBlocking { providers.setProviderEnabled(forId, b) }
                    },
                ),
                bool(
                    field = "multi_key_enabled",
                    name = "多 Key 模式",
                    desc = "开启后从 apiKeys 轮换取用，关闭时只用 apiKey。默认关闭。",
                    read = { it.multiKeyEnabled },
                    write = { p, b -> p.copy(multiKeyEnabled = b) },
                ),
                str(
                    field = "api_keys",
                    name = "多 Key 列表",
                    desc = "多 Key 模式下的候选 Key，每行一个「Key|权重」（权重省略取默认 1，≤0 表示暂不参与调度）。与库内存储同格式。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.apiKeys.joinToString("\n") { k -> "${k.value}|${k.weight}" } },
                    write = { p, s -> p.copy(apiKeys = parseProviderKeys(s)) },
                ),
                enumField(
                    field = "key_rotation_strategy",
                    name = "多 Key 策略",
                    desc = "sequential=顺序（失败才切，默认）/ round_robin=轮询（新会话轮流起步）。",
                    cases = listOf("sequential", "round_robin"),
                    read = { it.keyRotationStrategy.name.lowercase() },
                    write = { p, s ->
                        p.copy(keyRotationStrategy = KeyRotationStrategy.valueOf(s.uppercase()))
                    },
                ),
                int(
                    field = "key_cooldown_minutes",
                    name = "Key 冷却分钟",
                    desc = "被切走的 Key 冷却多少分钟后重新纳入候选；0 表示不冷却。默认 5。",
                    min = 0,
                    read = { it.keyCooldownMinutes },
                    write = { p, i -> p.copy(keyCooldownMinutes = i) },
                ),
                str(
                    field = "key_switch_status_codes",
                    name = "Key 切换状态码",
                    desc = "命中即触发多 Key 自动切换的 HTTP 状态码，逗号分隔（如 401,402,403,429）；留空回退默认集合。",
                    read = { it.keySwitchStatusCodes.joinToString(",") },
                    write = { p, s ->
                        p.copy(
                            keySwitchStatusCodes = s.split(',')
                                .mapNotNull { it.trim().toIntOrNull() }
                                .filter { it in 100..599 }
                                .distinct(),
                        )
                    },
                ),
                bool(
                    field = "proxy_enabled",
                    name = "独立代理",
                    desc = "单独为该供应商启用代理；关闭时跟随全局代理设置。默认关闭。",
                    read = { it.proxyEnabled },
                    write = { p, b -> p.copy(proxyEnabled = b) },
                ),
                enumField(
                    field = "proxy_type",
                    name = "代理协议",
                    desc = "http=CONNECT 隧道（可代理全部 http/https）/ socks5=TCP 级转发。默认 http。",
                    cases = listOf("http", "socks5"),
                    read = { it.proxyType.name.lowercase() },
                    write = { p, s -> p.copy(proxyType = ProxyType.valueOf(s.uppercase())) },
                ),
                str(
                    field = "proxy_host",
                    name = "代理主机",
                    desc = "代理服务器地址或域名；代理关闭时留空即可。",
                    read = { it.proxyHost },
                    write = { p, s -> p.copy(proxyHost = s) },
                ),
                int(
                    field = "proxy_port",
                    name = "代理端口",
                    desc = "代理端口，最大 65535；0 表示未设置。",
                    min = 0,
                    max = 65535,
                    read = { it.proxyPort },
                    write = { p, i -> p.copy(proxyPort = i) },
                ),
                str(
                    field = "proxy_username",
                    name = "代理用户名",
                    desc = "代理认证用户名；无认证时留空。",
                    read = { it.proxyUsername },
                    write = { p, s -> p.copy(proxyUsername = s) },
                ),
                str(
                    field = "proxy_password",
                    name = "代理密码",
                    desc = "代理认证密码；无认证时留空。",
                    risk = ConfigRisk.SENSITIVE,
                    read = { it.proxyPassword },
                    write = { p, s -> p.copy(proxyPassword = s) },
                ),
                bool(
                    field = "use_full_url",
                    name = "完整地址",
                    desc = "开启后 baseUrl 直接作为完整请求地址使用（不再拼接默认路径）。默认关闭。",
                    read = { it.useFullUrl },
                    write = { p, b -> p.copy(useFullUrl = b) },
                ),
                bool(
                    field = "use_response_api",
                    name = "Response API",
                    desc = "OpenAI 类型改用 Response API 端点。默认关闭。",
                    read = { it.useResponseApi },
                    write = { p, b -> p.copy(useResponseApi = b) },
                ),
                str(
                    field = "custom_headers",
                    name = "自定义请求头",
                    desc = "覆盖同名默认请求头，每行一个「名: 值」；值支持 {{SESSION_ID}} / {{API_KEY}} 占位符。留空表示不加。",
                    read = { it.customHeaders.entries.joinToString("\n") { (k, v) -> "$k: $v" } },
                    write = { p, s -> p.copy(customHeaders = parsePairs(s)) },
                ),
                ReadOnlyField(
                    path = "$basePath.$forId.sort_order",
                    displayName = "排序序号（$forId）",
                    description = "列表排序序号，越小越靠前；由仓库按保存时的当前顺序维护，配置通道只读。",
                    valueSchema = ConfigSchema.Int(),
                    reader = { ConfigValue.Int(snapshot(forId)?.sortOrder ?: -1) },
                ),
                int(
                    field = "dashboard_refresh_interval",
                    name = "面板刷新间隔",
                    desc = "自定义面板自动刷新间隔（分钟），0 表示仅进入/手动刷新。默认 5。",
                    min = 0,
                    read = { it.dashboardRefreshInterval },
                    write = { p, i -> p.copy(dashboardRefreshInterval = i) },
                ),
                bool(
                    field = "anthropic_cache_breakpoints",
                    name = "Anthropic 缓存断点",
                    desc = "发送显式 cache_control 断点；仅 ANTHROPIC 类型生效。默认开启。",
                    read = { it.anthropicCacheBreakpoints },
                    write = { p, b -> p.copy(anthropicCacheBreakpoints = b) },
                ),
                bool(
                    field = "openai_chat_cache_key",
                    name = "OpenAI 缓存 Key",
                    desc = "Chat Completion 请求发送 prompt_cache_key（分片路由）；仅 OPENAI 类型生效。默认关闭。",
                    read = { it.openaiChatCacheKey },
                    write = { p, b -> p.copy(openaiChatCacheKey = b) },
                ),
            )
        }

        override val addPayloadSchema: ConfigSchema = ConfigSchema.Json

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("providers 的新项需为 JSON 对象")
            val name = (obj["name"] as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 name")
            val baseUrl = (obj["baseUrl"] as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 baseUrl")
            val type = (obj["type"] as? ConfigValue.Str)?.value
                ?.let { runCatching { ProviderType.valueOf(it.uppercase()) }.getOrNull() }
                ?: ProviderType.OPENAI
            val id = UUID.randomUUID().toString()
            save(
                AIProviderConfig(
                    id = id,
                    name = name,
                    type = type,
                    apiKey = (obj["apiKey"] as? ConfigValue.Str)?.value.orEmpty(),
                    baseUrl = baseUrl,
                    defaultModel = "",
                ),
            )
            return id
        }

        override fun remove(id: String) {
            if (snapshot(id) == null) throw ConfigError.InvalidValue("供应商不存在：$id")
            runBlocking { providers.deleteProvider(id) }
        }
    }

    /** 把某供应商的模型显示名/图标序列化成 `{"模型名":{"displayName":..,"customIcon":..}}`。 */
    private fun buildMetadataJson(entries: Map<String, ModelMetadata>): String {
        val obj = JsonObject(entries.mapValues { (_, meta) ->
            JsonObject(
                buildMap {
                    if (meta.displayName.isNotBlank() && meta.displayName != meta.id) {
                        put("displayName", JsonPrimitive(meta.displayName))
                    }
                    if (meta.customIcon.isNotBlank()) put("customIcon", JsonPrimitive(meta.customIcon))
                }
            )
        })
        return obj.toString()
    }

    /**
     * 解析写入请求。只接受「模型名 → 对象」的形态，对象里只认 displayName / customIcon；
     * 其它键忽略（价格、窗口、能力不从这里改）。整体非法就返回 null，由调用方报错，不落盘。
     */
    private fun parseMetadataPatch(raw: String): Map<String, Pair<String?, String?>>? {
        val root = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        val out = mutableMapOf<String, Pair<String?, String?>>()
        for ((model, value) in root) {
            val obj = runCatching { value.jsonObject }.getOrNull() ?: return null
            val displayName = runCatching { obj["displayName"]?.jsonPrimitive?.content }.getOrNull()
            val customIcon = runCatching { obj["customIcon"]?.jsonPrimitive?.content }.getOrNull()
            out[model] = displayName to customIcon
        }
        return out
    }
}
