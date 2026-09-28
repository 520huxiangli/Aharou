package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigRisk
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.data.repository.EnvVarRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 环境变量 → 配置通道（集合式）。
 *
 * 变量注入容器的键值对，存 SharedPreferences（JSON）。集合 id 就是变量名本身
 * （受 [EnvVarRepository.NAME_REGEX] 约束，形如 `FOO_BAR`），因此路径为
 * `env.vars.FOO_BAR.value`。值默认按敏感处理——环境变量里放密钥是常见用法。
 */
@Singleton
class ConfigEnvFields @Inject constructor(
    private val envVars: EnvVarRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(VarCollection())
    }

    private fun vars() = envVars.entries.value

    private fun entry(name: String) = vars().firstOrNull { it.name == name }

    private inner class VarCollection : ConfigCollection {
        override val basePath = "env.vars"
        override val displayName = "环境变量"
        override val description =
            "注入容器的环境变量。子字段：value / secret；变量名即集合 id。"

        override fun childIds(): List<String> = vars().map { it.name }

        override fun fields(forId: String): List<ConfigField> {
            if (entry(forId) == null) return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.value",
                    displayName = "值（$forId）",
                    description = "变量值；容器内以 \$forId 取用。",
                    valueSchema = ConfigSchema.Str(),
                    risk = ConfigRisk.SENSITIVE,
                    revertable = true,
                    reader = { ConfigValue.Str(entry(forId)?.value.orEmpty()) },
                    writer = { v ->
                        val s = (v as? ConfigValue.Str)?.value
                            ?: throw ConfigError.TypeMismatch("string")
                        val current = entry(forId)
                            ?: throw ConfigError.InvalidValue("环境变量不存在：$forId")
                        envVars.upsert(forId, s, current.secret)
                    },
                ),
                ClosureField(
                    path = "$basePath.$forId.secret",
                    displayName = "标记为敏感（$forId）",
                    description = "开启后，值在设置页与工具输出里打码。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(entry(forId)?.secret ?: true) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        val current = entry(forId)
                            ?: throw ConfigError.InvalidValue("环境变量不存在：$forId")
                        envVars.upsert(forId, current.value, b)
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String {
            val name = (payload as? ConfigValue.Obj)?.value?.get("name")
                ?.let { (it as? ConfigValue.Str)?.value }
                ?: throw ConfigError.InvalidValue("新增环境变量需要 name")
            if (!EnvVarRepository.NAME_REGEX.matches(name)) {
                throw ConfigError.InvalidValue("变量名不合法（须匹配 ${EnvVarRepository.NAME_REGEX.pattern}）：$name")
            }
            if (name in EnvVarRepository.RESERVED_NAMES) {
                throw ConfigError.InvalidValue("变量名保留：$name")
            }
            if (entry(name) != null) throw ConfigError.InvalidValue("环境变量已存在：$name")
            val value = (payload as? ConfigValue.Obj)?.value?.get("value")
                ?.let { (it as? ConfigValue.Str)?.value }.orEmpty()
            val secret = (payload as? ConfigValue.Obj)?.value?.get("secret")
                ?.let { (it as? ConfigValue.Bool)?.value } ?: true
            envVars.upsert(name, value, secret)
            return name
        }

        override fun remove(id: String) {
            if (entry(id) == null) throw ConfigError.InvalidValue("环境变量不存在：$id")
            envVars.remove(id)
        }
    }
}
