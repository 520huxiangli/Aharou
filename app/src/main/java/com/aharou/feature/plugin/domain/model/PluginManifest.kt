package com.aharou.feature.plugin.domain.model

import kotlinx.serialization.Serializable

/**
 * 插件清单：`manifest.json`，位于插件包（`.txplugin`，实为 zip）根目录。
 *
 * 沿用太墟（TaiXu）插件包的字段契约，使现成的 `.txplugin` 包无需改造即可被本应用识别：
 * 清单是静态 JSON，宿主只读它、不执行其中任何代码；插件本体（payload/ 下的脚本与运行时）
 * 在容器里安装与运行，不进入 App 进程。
 */
@Serializable
data class PluginManifest(
    val schemaVersion: Int = 1,
    val id: String = "",
    val name: String = "",
    val description: String = "",
    val version: String = "",
    val publisher: String = "",
    val homepage: String = "",
    val icon: String = "",
    val category: String = "",
    /** 支持的 CPU 架构，如 ARM64。留空表示不限。 */
    val architectures: List<String> = emptyList(),
    /** 声明的权限（NETWORK / WORKSPACE_READ / WORKSPACE_WRITE 等），仅作安装前提示，不强制拦截。 */
    val permissions: List<String> = emptyList(),
    /** 启动形态（web / service / command 等）。当前仅用于展示。 */
    val launchType: String = "",
    /** 服务端口：启动后宿主轮询它判定就绪。0 表示不是常驻服务。 */
    val servicePort: Int = 0,
    /** 就绪探测路径（如 `/v1/models`），配合 [servicePort] 使用。 */
    val servicePath: String = "",
    /** 安装步骤：逐条在容器里执行。 */
    val installSteps: List<String> = emptyList(),
    /** 卸载步骤：逐条在容器里执行。 */
    val uninstallSteps: List<String> = emptyList(),
    /** 启动命令：常驻服务的可执行文件名或容器内绝对路径。 */
    val launchCommand: String = "",
    /** 安装后自检命令：退出码非 0 视为失败。 */
    val verifyCommand: String = "",
    /** 命令入口名列表，安装时由安装脚本负责放到容器 PATH。 */
    val commandLinks: List<String> = emptyList(),
    /** 启动时注入容器的环境变量。 */
    val environment: Map<String, String> = emptyMap(),
    /**
     * 以 MCP stdio server 形式暴露给 AI 的入口（容器内可执行名或绝对路径）。
     *
     * 留空表示插件不提供 AI 工具，只给用户手动用。填了之后插件目录下的入口会被派生成一个
     * 不落盘的 MCP server，工具名沿用 `mcp__plugin-<id>__<工具名>` 命名空间，权限走 EXTERNAL_TOOL。
     */
    val mcpEntry: String = "",
    /** 传给 [mcpEntry] 的启动参数（通常声明 stdio 协议，如 `--mcp`）。 */
    val mcpArgs: List<String> = emptyList(),
    /**
     * `payload/` 下每个文件的 SHA-256（相对路径 → 十六进制摘要）。留空表示包未提供校验。
     *
     * 不符只提示、不拒绝安装：静态指纹只能提供可跳过的诊断，不能阻断动作。
     */
    val integrity: Map<String, String> = emptyMap(),
) {
    /** 是否常驻服务（有服务端口）。 */
    val isService: Boolean get() = servicePort in 1..65535
}

/** 插件运行状态（内存态，不落盘）。 */
enum class PluginStatus {
    /** 未运行。 */
    STOPPED,

    /** 运行中（端口可访问）。 */
    RUNNING,

    /** 已安装但启动失败或异常退出（列表会带出失败原因）。 */
    FAILED,
}

/** 一条已安装插件的记录：清单 + 落盘位置 + 运行态。 */
data class InstalledPlugin(
    val manifest: PluginManifest,
    /** 宿主侧插件根目录（`filesDir/aharou/plugins/<id>`）。 */
    val hostDir: String,
    val installedAt: Long,
    val status: PluginStatus = PluginStatus.STOPPED,
    /** 最近一次操作的说明（错误信息或就绪提示）。 */
    val detail: String = "",
)
