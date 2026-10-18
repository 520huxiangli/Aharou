package com.aharou.feature.extension.domain

/**
 * 扩展统一模型：把 插件 / 技能 / MCP（连接器）/ 知识库 四类收进同一个视图。
 *
 * 这里只统一「发现与展示」的形状，**不接管各自的安装实现**——技能走 SkillMarketRepository、
 * 插件走 PluginManager、知识库走 KnowledgeSourceRepository、连接器走内置目录。四类的分发机制
 * 差得很远（有的一次拉 zip、有的只是引用一个远端仓库、连接器更是纯配置模板），
 * 强行统一安装管线只会把现在能用的逻辑改坏。
 */
enum class ExtensionKind {
    PLUGIN,
    SKILL,

    /** MCP 服务器：含内置连接器目录与用户自建 server。 */
    MCP,

    KNOWLEDGE,

    /** 内置连接器：一条 MCP 接入模板（带鉴权方式），严格说归属于 [MCP]。 */
    CONNECTOR,
}

/** 一条扩展在界面上的状态。 */
enum class ExtensionState {
    /** 可安装（尚未装）。 */
    AVAILABLE,

    /** 已安装。 */
    INSTALLED,

    /** 已安装且有新版（技能市场按版本号比对）。 */
    UPDATE_AVAILABLE,

    /** 已安装且正在运行（常驻插件服务）。 */
    RUNNING,

    /** 已安装但异常（插件启动失败等）。 */
    FAILED,
}

/** 统一列表里的一条。各类自己的字段（技能的作用域、插件的端口…）不进这里，需要时按 [id] 回查。 */
data class ExtensionEntry(
    val id: String,
    val kind: ExtensionKind,
    val name: String,
    val description: String = "",
    val author: String = "",
    val version: String = "",
    /** 来源展示：仓库名 / 内置目录 / 本地导入。 */
    val sourceLabel: String = "",
    val state: ExtensionState = ExtensionState.AVAILABLE,
    /** 附加一行说明（连接器的鉴权方式、知识库的分支等）。 */
    val detail: String = "",
)