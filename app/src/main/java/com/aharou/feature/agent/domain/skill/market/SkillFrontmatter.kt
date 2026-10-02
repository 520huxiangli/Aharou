package com.aharou.feature.agent.domain.skill.market

import com.aharou.core.util.FileLogger
import org.yaml.snakeyaml.Yaml

/**
 * 从 SKILL.md 的 YAML frontmatter 里提取市场展示用的元数据。
 *
 * 与 [com.aharou.feature.agent.domain.skill.SkillParser] 的分工：那个要产出可落盘的完整 Skill，
 * 这个只取列表页要显示的几个字段，且要容忍各家平台的字段差异。
 */
internal object SkillFrontmatter {
    private const val TAG = "SkillFrontmatter"
    private const val MAX_DESC_CHARS = 300

    data class Meta(
        val name: String,
        /** 面向用户的显示名。各家仓库叫法不一，WorkBuddy 用 display_name（基本是中文）。 */
        val displayName: String,
        val description: String,
        val author: String = "",
        val license: String = "",
        val version: String = ""
    )

    fun parse(text: String, fallbackName: String, lang: String): Meta {
        val map = readFrontmatter(text)
        val name = map["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
        return Meta(
            name = name,
            displayName = pick(map, displayNameKeys(lang)).ifEmpty { name },
            description = pick(map, descriptionKeys(lang)).take(MAX_DESC_CHARS),
            author = map["author"]?.toString()?.trim().orEmpty(),
            license = map["license"]?.toString()?.trim().orEmpty(),
            version = map["version"]?.toString()?.trim().orEmpty()
        )
    }

    /**
     * 描述字段的取值顺序。WorkBuddy 这类国内仓库同时给了 `description_zh` / `description_en`，
     * 按界面语言挑，缺哪个就退到通用字段（Trae 只给一个 `description`，那就是中文）。
     */
    private fun descriptionKeys(lang: String): List<String> =
        if (lang == "zh") {
            listOf("description_zh", "description", "description_en")
        } else {
            listOf("description_en", "description", "description_zh")
        }

    /** 显示名同理：有中文名就先给中文名。 */
    private fun displayNameKeys(lang: String): List<String> =
        if (lang == "zh") {
            listOf("display_name", "display_name_zh", "display_name_en")
        } else {
            listOf("display_name", "display_name_en", "display_name_zh")
        }

    private fun pick(map: Map<String, Any>, keys: List<String>): String =
        keys.firstNotNullOfOrNull { key ->
            map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        }.orEmpty()

    private fun readFrontmatter(text: String): Map<String, Any> {
        val normalized = text.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) return emptyMap()
        val end = normalized.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap()

        val block = normalized.substring(4, end)
        return runCatching {
            Yaml().load<Map<String, Any>>(block) ?: emptyMap()
        }.getOrElse { e ->
            FileLogger.w(TAG, "解析 frontmatter 失败：${e.message}")
            emptyMap()
        }
    }
}
