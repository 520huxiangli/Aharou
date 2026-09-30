package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
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
    private val providers: AIProviderRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(ProviderCollection())
    }

    private fun snapshot(id: String): AIProviderConfig? =
        runBlocking { providers.getProviderById(id) }

    private fun save(updated: AIProviderConfig) =
        runBlocking { providers.saveProvider(updated) }

    private inner class ProviderCollection : ConfigCollection {
        override val basePath = "providers"
        override val displayName = "AI 供应商"
        override val description =
            "已配置的供应商列表。子字段：name / baseUrl / apiKey / defaultModel / selectedModel / panelScript / enabled。"

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
}
