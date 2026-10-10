package com.aharou.feature.agent.domain.knowledge

import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import kotlinx.serialization.Serializable

/** 一份知识库源清单（远端 `data/knowledge.json` 与内置 assets 兜底一致）。 */
@Serializable
data class KnowledgeData(
    val sources: Map<String, KnowledgeSourceDef> = emptyMap()
)

/**
 * 一个知识库源：某个仓库里的一棵 Markdown 目录树。
 *
 * [host] 是 Git 托管平台标识（github / gitee / gitlab，其余按 Gitea 系处理），语义与技能市场一致，
 * 地址拼装直接复用 [SkillRepoAccess]，不另起一套。
 */
@Serializable
data class KnowledgeSourceDef(
    val repo: String = "",
    val branch: String = SkillRepoAccess.HEAD,
    val path: String = "",
    val host: String = SkillRepoAccess.GITHUB,
    val name: Map<String, String> = emptyMap()
) {
    /** 按当前界面语言取展示名；没有本地化名时退回任何一个，再退回仓库名。 */
    fun displayName(lang: String): String =
        name[lang] ?: name.values.firstOrNull() ?: repo
}

/** 本地已同步的一篇文档。 */
data class KnowledgeDoc(
    val sourceId: String,
    val sourceName: String,
    val path: String,
    val title: String,
    val size: Long
)

/** 检索命中：含命中文档的定位信息与一小段上下文。 */
data class KnowledgeHit(
    val sourceId: String,
    val sourceName: String,
    val path: String,
    val title: String,
    val snippet: String,
    val score: Int
)
