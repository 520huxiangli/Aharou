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
 * - `index`：仓库里有一份 JSON 索引（如我们自己的 `data/skill-packs.json`）；
 * - `directory`：没有索引，直接扫 [path] 目录下所有含 SKILL.md 的子目录（Trae / WorkBuddy 这类社区仓库）。
 *
 * [host] 是 Git 托管平台标识（github / gitee / gitlab，其余按 Gitea 系处理），
 * 决定取文件与列目录的地址格式；老数据没写这个字段时按 GitHub 处理。
 */
@Serializable
data class SkillMarketSourceDef(
    val kind: String = "index",
    val repo: String,
    val branch: String = "main",
    val path: String,
    val host: String = SkillRepoAccess.GITHUB,
    val name: Map<String, String> = emptyMap()
) {
    val isDirectory: Boolean get() = kind.equals("directory", ignoreCase = true)

    fun displayName(lang: String): String =
        name[lang] ?: name.values.firstOrNull() ?: repo
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
    val description: String,
    val version: String = "",
    val author: String = "",
    val license: String = "",
    /** `directory` 型：目录下的全部文件（相对目录），安装时逐个下载。 */
    val files: List<String> = emptyList(),
    /** `index` 型：技能包（zip）在源仓库里的完整相对路径，整包下载。 */
    val archivePath: String = ""
)
