package com.aharou.feature.plugin.domain.market

import kotlinx.serialization.Serializable

/** 插件市场源清单（远端 `data/plugins.json` 与内置 assets 兜底一致）。 */
@Serializable
data class PluginMarketData(
    val sources: Map<String, PluginMarketSourceDef> = emptyMap()
)

/**
 * 一个插件市场源。
 *
 * 目前只有 `index` 一种取法：源仓库里放一份 JSON 索引，条目自带下载地址。
 * 不像技能市场那样支持 `directory`（扫目录）——插件的元信息比 SKILL.md 复杂得多，
 * 靠扫目录逐个读 manifest 太费请求，索引集中维护更省事。
 */
@Serializable
data class PluginMarketSourceDef(
    val kind: String = "index",
    /** 形如 `owner/repo`。 */
    val repo: String = "",
    val branch: String = "main",
    /** 索引文件在仓库里的路径。 */
    val path: String = "plugins/index.json",
    /** Git 托管平台标识，取值与技能市场一致（github / gitee / gitlab），地址拼装复用同一套。 */
    val host: String = "github",
    val name: Map<String, String> = emptyMap()
) {
    fun displayName(lang: String): String =
        name[lang] ?: name.values.firstOrNull() ?: repo
}

/** 索引文件的内容。 */
@Serializable
data class PluginPackData(
    val plugins: List<PluginMarketEntry> = emptyList()
)

/**
 * 市场里一个可安装的插件。
 *
 * 字段与 RikkaHub 那套市场索引对齐（id / name / version / downloadUrl / type / tags），
 * 这样将来若要兼容别人的市场源，索引不必改格式。
 */
@Serializable
data class PluginMarketEntry(
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val author: String = "",
    val category: String = "",
    /** 插件包（`.txplugin`，实为 zip）的完整下载地址。 */
    val downloadUrl: String = "",
    /** 来源仓库（展示用）。 */
    val repo: String = "",
    val tags: List<String> = emptyList()
)