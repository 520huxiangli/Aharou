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
) {
    /** 是否常驻服务（有服务端口）。 */
    val isService: Boolean get() = servicePort in 1..65535
}

/** 插件运行状态（内存态，不落盘）。 */
enum class PluginStatus {
    /** 未运行。 */
    STOPPED,

    /** 启动中（进程已起，等待端口就绪）。 */
    STARTING,

    /** 运行中（端口可访问）。 */
    RUNNING,

    /** 已安装但启动失败或异常退出。 */
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
