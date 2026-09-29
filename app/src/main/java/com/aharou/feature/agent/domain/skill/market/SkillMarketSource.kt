package com.aharou.feature.agent.domain.skill.market

import kotlinx.serialization.Serializable

/** 一份技能目录清单（远端 `data/skills.json` 与内置 assets 兜底一致）。 */
@Serializable
data class SkillMarketData(
    val sources: Map<String, SkillMarketSourceDef> = emptyMap()
)

/**
 * 一个技能市场的源。
 *
 * [kind] 决定怎么列技能：
 * - `index`：仓库里有一份 JSON 索引，技能包以 zip 形式随仓库分发；
 * - `directory`：没有索引，直接扫 [path] 目录下所有含 SKILL.md 的子目录（Trae / WorkBuddy 这类社区仓库）；
 * - `search`：站点只有检索接口、没有「列全部」的入口，用户输关键词才出结果（见 [searchUrl]）。
 *
 * [host] 是 Git 托管平台标识（github / gitee / gitlab，其余按 Gitea 系处理），
 * 决定取文件与列目录的地址格式；老数据没写这个字段时按 GitHub 处理。
 */
@Serializable
data class SkillMarketSourceDef(
    val kind: String = "index",
    val repo: String = "",
    val branch: String = "main",
    val path: String = "",
    val host: String = SkillRepoAccess.GITHUB,
    val name: Map<String, String> = emptyMap(),
    /** `search` 型的检索接口，`{q}` 是关键词占位符。 */
    val searchUrl: String = ""
) {
    val isDirectory: Boolean get() = kind.equals("directory", ignoreCase = true)

    val isSearch: Boolean get() = kind.equals("search", ignoreCase = true)

    fun displayName(lang: String): String =
        name[lang] ?: name.values.firstOrNull() ?: repo

    /** 把关键词填进检索接口；没配接口或关键词为空时返回 null。 */
    fun searchUrlFor(query: String): String? {
        val q = query.trim()
        if (q.isEmpty() || searchUrl.isEmpty()) return null
        return searchUrl.replace("{q}", java.net.URLEncoder.encode(q, "UTF-8"))
    }
}

/** `index` 型源里的一份技能索引。 */
@Serializable
data class SkillPackData(
    val skills: List<SkillPackEntry> = emptyList()
)

/** `index` 型源索引里的单个技能条目。 */
@Serializable
data class SkillPackEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val license: String = "",
    val tags: List<String> = emptyList(),
    /** 技能包（zip）在源仓库里的相对路径。 */
    val path: String = ""
)

/** `search` 型源的检索响应：skills.sh 的 `/api/search` 就是这个形状。 */
@Serializable
data class SkillSearchResponse(
    val skills: List<SkillSearchItem> = emptyList()
)

/** 检索结果里的一条：只知道技能名与所在仓库，不知道它在仓库里的目录。 */
@Serializable
data class SkillSearchItem(
    /** 技能所在仓库，形如 `owner/repo`。 */
    val source: String = "",
    /** 技能标识，通常就是仓库里的目录名，但不保证。 */
    val skillId: String = "",
    val name: String = "",
    val installs: Long = 0
)

/**
 * 市场上一条可安装的技能：两种源最终统一成这个形状给 UI。
 *
 * 自带 [repo]/[branch]/[isDirectory] 而不靠 sourceId 回查源定义：这样「粘贴 GitHub 链接」
 * 这种一次性源不需要先进源清单也能安装，同时结果可直接序列化做磁盘缓存。
 */
@Serializable
data class MarketSkill(
    val sourceId: String,
    val host: String = SkillRepoAccess.GITHUB,
    val repo: String,
    val branch: String,
    val isDirectory: Boolean,
    /** 技能目录相对源仓库根的路径，如 `skills/git-commit-generator`。 */
    val dir: String,
    val name: String,
    /** 展示给用户看的名，优先仓库给的 display_name（国内仓库多为中文）；为空时用 [name]。 */
    val displayName: String = "",
    val description: String,
    val version: String = "",
    val author: String = "",
    val license: String = "",
    /** `directory` 型：目录下的全部文件（相对目录），安装时逐个下载。 */
    val files: List<String> = emptyList(),
    /** `index` 型：技能包（zip）在源仓库里的完整相对路径，整包下载。 */
    val archivePath: String = "",
    /**
     * 检索型源的结果：只知道技能名与所在仓库、不知道它在仓库里的目录，
     * 安装前要先拉一次文件树定位（见 [SkillMarketRepository.install]）。
     */
    val needsLocate: Boolean = false,
    /** 检索型源带来的安装量，仅用于展示。 */
    val installs: Long = 0
)
