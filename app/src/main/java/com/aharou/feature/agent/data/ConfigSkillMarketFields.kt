package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.skill.market.SkillMarketSourceDef
import com.aharou.feature.agent.domain.skill.market.SkillMarketSourceRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能市场自定义源 → 配置通道（集合式）。
 *
 * 只暴露用户自己加的那些源；官方预置源由 `data/skills.json` 远端托管，不在此列（也不该由 AI 改）。
 * 源 id 即集合 id，路径形如 `skill_market.sources.my-repo.repo`。
 */
@Singleton
class ConfigSkillMarketFields @Inject constructor(
    private val sourceRepository: SkillMarketSourceRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(SourceCollection())
    }

    private inner class SourceCollection : ConfigCollection {
        override val basePath = "skill_market.sources"
        override val displayName = "技能市场源"
        override val description =
            "自定义的技能市场源（官方预置源不在此列）。新增需要 id / repo / path，可选 branch（默认 main）、" +
                "kind（directory 扫描目录 / index 读 JSON 索引，默认 directory）、" +
                "host（github / gitee / gitlab / Gitea 系域名，默认 github）；源 id 即集合 id。"

        override fun childIds(): List<String> = sourceRepository.all().keys.toList()

        override fun fields(forId: String): List<ConfigField> {
            val def = sourceRepository.all()[forId] ?: return emptyList()
            return listOf(
                writable("$forId.repo", "仓库（$forId）", "形如 owner/repo。", def.repo) { repo ->
                    sourceRepository.add(forId, def.copy(repo = repo))
                },
                writable("$forId.branch", "分支（$forId）", "默认 main。", def.branch) { branch ->
                    sourceRepository.add(forId, def.copy(branch = branch))
                },
                writable(
                    "$forId.path",
                    "路径（$forId）",
                    "kind=directory 时为技能所在目录（如 skills）；kind=index 时为 JSON 索引文件路径。",
                    def.path
                ) { path ->
                    sourceRepository.add(forId, def.copy(path = path))
                },
                writable(
                    "$forId.kind",
                    "类型（$forId）",
                    "directory=扫目录里的 SKILL.md；index=读 JSON 索引。",
                    def.kind
                ) { kind ->
                    sourceRepository.add(forId, def.copy(kind = kind))
                },
                writable(
                    "$forId.host",
                    "平台（$forId）",
                    "github / gitee / gitlab，或 Gitea 系自建实例的域名（如 codeberg.org）。",
                    def.host
                ) { host ->
                    sourceRepository.add(forId, def.copy(host = host))
                },
            )
        }

        /** 一个可读可写的字符串子字段；写入即就地更新该源定义。 */
        private fun writable(
            suffix: String,
            label: String,
            hint: String,
            current: String,
            save: (String) -> Unit
        ): ConfigField = ClosureField(
            path = "$basePath.$suffix",
            displayName = label,
            description = hint,
            valueSchema = ConfigSchema.Str(),
            revertable = true,
            reader = { ConfigValue.Str(current) },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value?.trim()
                    ?: throw ConfigError.TypeMismatch("string")
                if (s.isEmpty()) throw ConfigError.InvalidValue("$label 不能为空")
                save(s)
            },
        )

        override fun add(payload: ConfigValue): String {
            val obj = (payload as? ConfigValue.Obj)?.value
                ?: throw ConfigError.InvalidValue("新增源需要 id / repo / path")

            val id = (obj["id"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            val repo = (obj["repo"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            val path = (obj["path"] as? ConfigValue.Str)?.value?.trim().orEmpty()
            val branch = (obj["branch"] as? ConfigValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: "main"
            val kind = (obj["kind"] as? ConfigValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() } ?: "directory"
            val host = (obj["host"] as? ConfigValue.Str)?.value?.trim()?.takeIf { it.isNotEmpty() }
                ?: com.aharou.feature.agent.domain.skill.market.SkillRepoAccess.GITHUB

            if (id.isEmpty()) throw ConfigError.InvalidValue("源 id 不能为空")
            if (sourceRepository.exists(id)) throw ConfigError.InvalidValue("源已存在：$id")
            if (!repo.contains("/")) throw ConfigError.InvalidValue("repo 应为 owner/repo 形式：$repo")
            if (path.isEmpty()) throw ConfigError.InvalidValue("path 不能为空")
            if (kind != "directory" && kind != "index") {
                throw ConfigError.InvalidValue("kind 只能是 directory 或 index：$kind")
            }

            sourceRepository.add(
                id,
                SkillMarketSourceDef(kind = kind, repo = repo, branch = branch, path = path, host = host)
            )
            return id
        }

        override fun remove(id: String) {
            if (!sourceRepository.exists(id)) throw ConfigError.InvalidValue("源不存在：$id")
            sourceRepository.remove(id)
        }
    }
}
