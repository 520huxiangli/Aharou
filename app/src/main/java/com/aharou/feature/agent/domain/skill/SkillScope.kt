package com.aharou.feature.agent.domain.skill

/**
 * 技能的来源作用域：
 * - [BUILTIN]：随 App 打包（`assets/skills/`），只读，不可编辑/删除/停用；
 * - [GLOBAL]：全局，跨项目共享；
 * - [PROJECT]：项目级，仅当前工作区生效。
 */
enum class SkillScope { BUILTIN, GLOBAL, PROJECT }

/** 一个生效的技能条目：技能本体 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class SkillEntry(
    val skill: Skill,
    val scope: SkillScope
)
