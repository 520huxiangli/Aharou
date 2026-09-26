package com.aharou.feature.workspace.domain.model

/** 工作区类型：内部（App 私有 projects 根下）/ 外部本地目录（用户所选设备目录）/ 远程。 */
enum class WorkspaceType {
    INTERNAL,
    EXTERNAL_LOCAL,
    REMOTE
}

/**
 * 一个工作区 = AI 的操作根目录。
 *
 * - [INTERNAL]：App 私有项目根目录（filesDir/projects）下的一个子文件夹，物理上在私有 ext4。
 * - [EXTERNAL_LOCAL]：用户通过系统目录选择器选定的设备本地目录（如 /storage/emulated/0/xxx），
 *   双向直接读写该目录，不复制。删除工作区只解除关联、不删物理文件。
 * - [REMOTE]：远程 SSH 服务器上 remoteWorkspacePath 下的子文件夹。
 *
 * AI 的文件工具与命令执行都以 [path] 为根，切换工作区即切换 AI 的操作范围。
 */
data class Workspace(
    /** 文件夹名，作为唯一标识。 */
    val name: String,
    /** 绝对路径，可直接用于 java.io.File 与容器挂载。 */
    val path: String,
    /** 工作区类型，默认内部工作区，保持旧构造兼容。 */
    val type: WorkspaceType = WorkspaceType.INTERNAL,
    /** 工作区当前是否可用（外部本地目录被移动/删除时为 false），默认可用。 */
    val available: Boolean = true
)
