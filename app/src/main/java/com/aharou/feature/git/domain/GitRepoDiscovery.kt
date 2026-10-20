package com.aharou.feature.git.domain

import com.aharou.feature.agent.domain.container.CommandEngine

/**
 * 扫描工作区根的一层直接子目录，标出哪些目录自身是 git 仓库根（Git 页的多目录识别用）。
 *
 * 走 [CommandEngine] 而非 `java.io.File`：远程 SSH 模式下路径在远端，本地 File 列不出来；engine 的
 * cwd 就是工作区（本地 PRoot 绑定到 `/root/workspace`），两种模式下语义一致。
 *
 * 判定「仓库根」用 `<dir>/.git` 是否存在，而不是 `git rev-parse --is-inside-work-tree`——后者对仓库内
 * 任意子目录都返回 true，工作区根本身是仓库时会把所有子目录都误判成仓库。`.git` 用 `-e` 判定：
 * worktree 的 `.git` 是文件而不是目录。
 */
internal object GitRepoDiscovery {

    /** 扫描输出行里「自身是仓库根」的标记。 */
    private const val MARKER_REPO = "r"

    /** 扫描输出行里「普通目录（不是仓库根）」的标记。 */
    private const val MARKER_DIR = "d"

    /**
     * 逐个子目录输出 `<标记> <名字>`。
     * 只用 shell 内建（`[`、`printf`、参数展开），本地 PRoot 与远程 SSH 都能跑；两个 glob（普通目录与
     * 点开头的隐藏目录）覆盖全部子目录，未命中时保持字面量、被 `[ -d ]` 挡掉。
     */
    private const val SCAN_SUBDIRS_CMD =
        "for d in */ .[!.]*/; do [ -d \"\$d\" ] || continue; " +
            "if [ -e \"\$d.git\" ]; then printf 'r %s\\n' \"\${d%/}\"; " +
            "else printf 'd %s\\n' \"\${d%/}\"; fi; done"

    /**
     * 返回「子目录绝对路径 → 自身是否仓库根」，按子目录名排序。
     * 扫描失败（后端未就绪、命令非零退出等）返回空列表，调用方退化为「只看工作区根」。
     */
    suspend fun scan(engine: CommandEngine, rootPath: String): List<Pair<String, Boolean>> {
        // 根为 "/"（远程模式下未选中工作区时的回退值）时 base 为空串，拼出的仍是绝对路径。
        val base = rootPath.trimEnd('/')
        val cwd = base.ifBlank { "/" }
        val result = runCatching {
            engine.runCommandSyncUnbounded(SCAN_SUBDIRS_CMD, cwd)
        }.getOrNull() ?: return emptyList()
        if (result.exitCode != 0) return emptyList()
        return result.output.lineSequence()
            .map { it.removeSuffix("\r") }
            .mapNotNull { line ->
                val marker = line.substringBefore(' ')
                val name = line.substringAfter(' ', "").trim()
                if (name.isEmpty()) return@mapNotNull null
                val isRepo = when (marker) {
                    MARKER_REPO -> true
                    MARKER_DIR -> false
                    else -> return@mapNotNull null
                }
                "$base/$name" to isRepo
            }
            .sortedBy { (path, _) -> path.substringAfterLast('/') }
            .toList()
    }
}
