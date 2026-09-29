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
        val description: String,
        val author: String = "",
        val license: String = "",
        val version: String = ""
    )

    fun parse(text: String, fallbackName: String): Meta {
        val map = readFrontmatter(text)
        val name = map["name"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: fallbackName
        // WorkBuddy 的技能只有 description_zh / description_en，没有 description 字段
        val description = sequenceOf("description", "description_zh", "description_en")
            .mapNotNull { key -> map[key]?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
            .firstOrNull()
            .orEmpty()
            .take(MAX_DESC_CHARS)
        return Meta(
            name = name,
            description = description,
            author = map["author"]?.toString()?.trim().orEmpty(),
            license = map["license"]?.toString()?.trim().orEmpty(),
            version = map["version"]?.toString()?.trim().orEmpty()
        )
    }

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
