package com.aharou.feature.agent.data

import com.aharou.core.config.ConfigCollection
import com.aharou.core.config.ConfigError
import com.aharou.core.config.ConfigField
import com.aharou.core.config.ConfigRegistry
import com.aharou.core.config.ConfigSchema
import com.aharou.core.config.ConfigValue
import com.aharou.core.config.fields.ClosureField
import com.aharou.feature.agent.domain.skill.SkillConfigRepository
import com.aharou.feature.agent.domain.skill.SkillRepository
import com.aharou.feature.agent.domain.skill.SkillScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能启停 → 配置通道（集合式，只读列表 + 单个可写开关）。
 *
 * 技能本体是磁盘目录里的 SKILL.md，config 通道不负责新建/删除技能（那是文件操作，
 * 该走文件工具），只暴露「启用/停用」。仓库只持久化禁用名单，生效集合是全局与项目
 * 的并集，读不出也写不回单个作用域，所以：
 *  - 读：该技能是否在生效禁用集合里
 *  - 写：改**全局**名单（项目级保持原样，避免覆盖掉随工作区走的那份配置）
 */
@Singleton
class ConfigSkillFields @Inject constructor(
    private val skillRepository: SkillRepository,
    private val skillConfig: SkillConfigRepository,
) {

    fun registerInto(registry: ConfigRegistry) {
        registry.register(SkillCollection())
    }

    private fun skillNames(): List<String> = skillRepository.listAllSkills().map { it.skill.name }

    private inner class SkillCollection : ConfigCollection {
        override val basePath = "skill.skills"
        override val displayName = "技能"
        override val description =
            "已安装的技能（全局 + 项目）。子字段：disabled；技能名即集合 id。"
        override val addable = false
        override val removable = false

        override fun childIds(): List<String> = skillNames()

        override fun fields(forId: String): List<ConfigField> {
            if (forId !in skillNames()) return emptyList()
            return listOf(
                ClosureField(
                    path = "$basePath.$forId.disabled",
                    displayName = "停用（$forId）",
                    description =
                        "是否停用。读取的是全局与项目禁用名单的并集；写入只改全局名单，" +
                            "项目级名单不受影响。",
                    valueSchema = ConfigSchema.Bool,
                    revertable = true,
                    reader = { ConfigValue.Bool(skillRepository.isSkillDisabled(forId)) },
                    writer = { v ->
                        val b = (v as? ConfigValue.Bool)?.value
                            ?: throw ConfigError.TypeMismatch("boolean")
                        skillConfig.setDisabled(forId, b, SkillScope.GLOBAL)
                    },
                ),
            )
        }

        override fun add(payload: ConfigValue): String =
            throw ConfigError.InvalidValue("技能由 SKILL.md 目录决定，请直接创建技能目录")

        override fun remove(id: String) {
            throw ConfigError.InvalidValue("技能由 SKILL.md 目录决定，请直接删除技能目录")
        }
    }
}
