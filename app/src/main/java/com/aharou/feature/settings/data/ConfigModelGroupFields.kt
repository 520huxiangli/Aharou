package com.aharou.feature.settings.data

import com.aharou.core.config.ConfigAccess
import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.settings.data.repository.ModelGroupRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型组 → 配置通道（集合式）。
 *
 * 模型组存在 SharedPreferences（JSON），仓库是同步 API，不需要 runBlocking。
 * 仓库只公开 create / rename / delete 三个写入口，组级 thinkingLevel 与
 * contextLimitTokens 目前没有 setter（v1 仅存储），因此那两项做成只读。
 */
@Singleton
class ConfigModelGroupFields @Inject constructor(
    private val modelGroups: ModelGroupRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(GroupCollection())
    }

    private fun groups() = modelGroups.groups.value

    private fun group(id: String) = groups().firstOrNull { it.id == id }

    private inner class GroupCollection : ConfigCollection {
        override val basePath = "modelgroup.groups"
        override val displayName = "模型组"
        override val description =
            "把一个供应商的多个模型捆成一组，会话里按组名选择。子字段：name（可写）/ thinking_level、context_limit_tokens、members（只读）。"

        override fun childIds(): List<String> = groups().map { it.id }

        override fun fields(forId: String): List<ConfigField> {
            if (group(forId) == null) return emptyList()
            val path = "$basePath.$forId"

            fun readonly(segment: String, label: String, desc: String, read: () -> String) =
                ClosureField(
                    path = "$path.$segment",
                    displayName = "$label（$forId）",
                    description = desc,
                    valueSchema = ConfigSchema.Str(),
                    access = ConfigAccess.READONLY,
                    revertable = false,
                    reader = { ConfigValue.Str(read()) },
                    writer = { throw ConfigError.PermissionDenied("$segment 为只读字段") },
                )

            return listOf(
                ClosureField(
                    path = "$path.name",
                    displayName = "组名（$forId）",
                    description = "显示在模型选择列表里的名字。",
                    valueSchema = ConfigSchema.Str(),
                    revertable = true,
                    reader = { ConfigValue.Str(group(forId)?.name.orEmpty()) },
                    writer = { v ->
                        val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                        modelGroups.rename(forId, s)
                    },
                ),
                readonly("thinking_level", "思考档位", "组级默认思考强度（v1 仅存储）。") {
                    group(forId)?.thinkingLevel.orEmpty()
                },
                readonly("context_limit_tokens", "上下文上限", "组级上下文 token 上限（v1 仅存储）。") {
                    group(forId)?.contextLimitTokens?.toString().orEmpty()
                },
                readonly("members", "成员", "组内成员列表，格式 providerId|model，逗号分隔。") {
                    group(forId)?.members
                        ?.joinToString(",") { "${it.providerId}|${it.model}" }
                        .orEmpty()
                },
            )
        }

        override fun add(payload: ConfigValue): String {
            val name = (payload as? ConfigValue.Obj)?.value?.get("name")
                ?.let { (it as? ConfigValue.Str)?.value }
                ?: (payload as? ConfigValue.Str)?.value
                ?: throw ConfigError.InvalidValue("缺少 name")
            return modelGroups.create(name).id
        }

        override fun remove(id: String) {
            if (group(id) == null) throw ConfigError.InvalidValue("模型组不存在：$id")
            modelGroups.delete(id)
        }
    }
}
