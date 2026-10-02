package com.aharou.feature.agent.domain.skill.market

import kotlinx.serialization.Serializable

/** 一条技能的中文译文（仓库只给英文时由模型翻出来）。 */
@Serializable
data class SkillTranslation(
    val name: String = "",
    val description: String = ""
)

/** 译文的缓存键：仓库 + 技能目录。列表来去、换源再回来都认得出同一条。 */
internal fun MarketSkill.translationKey(): String = "$repo@$dir"

/**
 * 名称与描述里一个汉字都没有 = 仓库没给中文，值得送翻译。
 * 仓库自带中文的（WorkBuddy 的 `description_zh`）直接跳过，不花用户 token。
 */
internal fun MarketSkill.needsTranslation(): Boolean =
    !(name + displayName + description).any { isHan(it) }

private fun isHan(c: Char): Boolean =
    Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
