package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.core.util.GitIgnoreMatcher
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.FileEntry
import com.aharou.feature.workspace.domain.WorkspacePathMapper

/** 当前容器在容器内的根路径，对应宿主上的 rootfs 目录（远程 SSH 下为远程服务器根）。 */
const val CONTAINER_FS_ROOT = "/"

/**
 * [path] 下的子路径前缀：容器根 `/` 下为 `/`，其余为 `<path>/`。
 * 拼接子路径、折叠时连带删后代都用它，避免 `/` 拼出 `//name`。
 */
fun childPathPrefix(path: String): String = if (path.endsWith("/")) path else "$path/"

/** [path] 的父目录；`/` 的直接子项父目录是 `/`（直接 `substringBeforeLast('/')` 会得到空串）。 */
fun parentPathOf(path: String): String =
    if (path.startsWith("/") && path.indexOf('/', 1) < 0) "/" else path.substringBeforeLast('/', "")

/**
 * 侧边栏文件树的扁平化构建器：把两个根（工作区、当前容器）按展开状态递归读成一串 [FileTreeNode]。
 *
 * listFiles 在本地是阻塞 IO、远程是网络调用，调用方须跑在 IO 调度器上。
 */
class FileBrowseTreeBuilder(private val fileAccess: FileAccessProvider) {

    /** 读取工作区根与当前容器根，按 [expanded] 递归展开，产出扁平的可见节点列表；工作区根读取失败则整体报错。 */
    fun build(expanded: Set<String>, containerRoot: FileTreeRoot.Container): FileBrowseState {
        val workspaceRoot = WorkspacePathMapper.CONTAINER_ROOT
        val workspaceEntries = runCatching { fileAccess.listFiles(workspaceRoot) }.getOrElse { e ->
            FileLogger.w(TAG, "列目录失败: $workspaceRoot", e)
            return FileBrowseState.Error(e.message)
        }
        val nodes = mutableListOf<FileTreeNode>()
        nodes += rootNode(FileTreeRoot.Workspace, workspaceRoot, expanded)
        if (workspaceRoot in expanded) {
            appendChildren(
                workspaceRoot, workspaceEntries, depth = 1, expanded = expanded,
                ignorePatterns = loadRootGitignore(workspaceRoot), relParts = emptyList(), out = nodes
            )
        }
        // 当前容器根：读不到（rootfs 未安装、远程未连接）时只把该行标红，不影响工作区那棵。
        val containerExpanded = CONTAINER_FS_ROOT in expanded
        val containerEntries = if (containerExpanded) listContainerRoot() else null
        nodes += rootNode(
            containerRoot, CONTAINER_FS_ROOT, expanded,
            hasError = containerExpanded && containerEntries == null
        )
        if (containerEntries != null) {
            appendChildren(
                CONTAINER_FS_ROOT, containerEntries, depth = 1, expanded = expanded,
                ignorePatterns = emptyList(), relParts = emptyList(), out = nodes
            )
        }
        return FileBrowseState.Success(nodes)
    }

    /**
     * 列容器根；读不到返回 null（该行标红）。
     *
     * 本地 listFiles 打不开目录时返回空表而非抛异常，故再用 isDirectory 兜一层判断——否则 rootfs
     * 未安装会显示成「已展开的空目录」；远程腿包了防崩壳，断连时同样是空表 + false。
     */
    private fun listContainerRoot(): List<FileEntry>? = runCatching {
        fileAccess.listFiles(CONTAINER_FS_ROOT)
            .takeIf { it.isNotEmpty() || fileAccess.isDirectory(CONTAINER_FS_ROOT) }
    }.getOrNull()

    /** 文件树的一个根节点行；[FileTreeNode.entry] 的 name 同时用作显示名（见 [FileTreeRoot.label]）。 */
    private fun rootNode(
        root: FileTreeRoot,
        path: String,
        expanded: Set<String>,
        hasError: Boolean = false
    ) = FileTreeNode(
        entry = FileEntry(name = root.label, isDirectory = true, size = 0, lastModified = 0),
        path = path,
        depth = 0,
        root = root,
        isExpanded = path in expanded,
        hasError = hasError
    )

    /** 读工作区根 .gitignore（本地/远程均可）；去空行/注释/否定行，读不到则空。仅根 .gitignore，不处理嵌套。 */
    private fun loadRootGitignore(root: String): List<String> = runCatching {
        val path = "$root/.gitignore"
        if (!fileAccess.exists(path)) return emptyList()
        fileAccess.readFile(path).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removeSuffix("/") }
            .toList()
    }.getOrDefault(emptyList())

    private fun appendChildren(
        parent: String,
        entries: List<FileEntry>,
        depth: Int,
        expanded: Set<String>,
        ignorePatterns: List<String>,
        relParts: List<String>,
        out: MutableList<FileTreeNode>
    ) {
        // 按名字去重：文件系统的 readdir 在 FUSE 存储（外部工作区/模拟存储）上可能重复返回同一条目，
        // AOSP 的 ReaddirHelper 亦做同样处理。同名条目会产生重复的节点 path，撞坏 LazyColumn 的 key。
        for (entry in entries.distinctBy { it.name }.sortedWith(BROWSE_ORDER)) {
            val path = childPathPrefix(parent) + entry.name
            val parts = relParts + entry.name
            val ignored = ignorePatterns.isNotEmpty() &&
                GitIgnoreMatcher.isIgnored(ignorePatterns, parts)
            val open = entry.isDirectory && path in expanded
            if (!open) {
                out += FileTreeNode(entry, path, depth, isExpanded = false, ignored = ignored)
                continue
            }
            val children = runCatching { fileAccess.listFiles(path) }.getOrNull()
            out += FileTreeNode(entry, path, depth, isExpanded = true, hasError = children == null, ignored = ignored)
            if (children != null) appendChildren(path, children, depth + 1, expanded, ignorePatterns, parts, out)
        }
    }

    private companion object {
        const val TAG = "FileBrowseTree"

        /** 文件浏览排序：目录在前，同类按名称不区分大小写。 */
        val BROWSE_ORDER: Comparator<FileEntry> =
            compareByDescending<FileEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
}
