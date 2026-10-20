package com.aharou.feature.git.presentation.component

/**
 * Git 页的仓库路径换算（纯函数，便于单测）：仓库路径 → 显示名、仓库相对路径 → 工作区相对路径。
 */

/** 仓库路径 → 显示名（最后一段路径；空路径原样返回）。 */
internal fun repoDisplayName(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifBlank { path }

/** 显示名：工作区名/子目录名（如 default/git1）；工作区根本身只显示工作区名。 */
internal fun repoDisplayPath(path: String, workspaceRoot: String): String {
    val root = workspaceRoot.trimEnd('/')
    val rootName = root.substringAfterLast('/').ifBlank { root }
    val relative = path.removePrefix(root).trimStart('/')
    return if (relative.isBlank()) rootName else "$rootName/$relative"
}

/**
 * diff 里的路径是仓库相对路径（来自 git 输出）；当仓库是工作区的子目录时，补上「仓库相对工作区根」
 * 的前缀再交给编辑器，否则 `src/Main.kt` 会被按工作区根解析成另一个文件（或同名但不相关的文件）。
 * 仓库就是工作区根时不加前缀，保持原行为。
 */
internal fun repoRelativeToWorkspace(path: String, currentRepo: String?, workspaceRoot: String): String {
    val repo = currentRepo?.trimEnd('/') ?: return path
    val root = workspaceRoot.trimEnd('/')
    if (repo == root) return path
    val prefix = repo.removePrefix(root).trimStart('/')
    return if (prefix.isBlank()) path else "$prefix/$path"
}
