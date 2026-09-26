package com.aharou.core.watch

import com.aharou.core.util.GitIgnoreMatcher
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** 变更所属的监听根：工作区、AI 配置目录（`~/.aharou`），或订阅方指定的其它宿主目录。 */
enum class ChangeRoot { WORKSPACE, AICODE, OTHER }

/** 单条变更的类型。同一路径在一个批量窗口内多次变更会被合并为一条。 */
enum class ChangeKind { CREATED, MODIFIED, DELETED }

/**
 * 一条文件变更。[containerPath] 是容器视角路径（AI / 终端看到的），供消费方直接使用；
 * 按宿主路径匹配的消费方（如同步引擎）用 [hostPath]。
 */
data class FileChange(
    val root: ChangeRoot,
    val hostPath: String,
    val containerPath: String,
    val kind: ChangeKind
)

/**
 * 一个批量窗口内收集到的变更。[changes] 已按路径去重（同路径只出现一次，kind 已合并）；
 * [truncated] 为 true 表示明细超过窗口上限被截断，仅保证「该范围内有变更」这一信号。
 */
data class FileChangeBatch(
    val changes: List<FileChange>,
    val truncated: Boolean = false
)

/**
 * 订阅侧过滤规则：只影响「递归向下时是否进入某目录」与「该目录内事件是否上报」。
 * 判定只针对父路径段、不含最后一段——被剪枝目录自身的增删仍会上报，父目录列表才看得到它。
 *
 * 刻意不含任何内置忽略名单：剪枝依据完全来自订阅方（[ignoredNames] / [extraPatterns]）
 * 与订阅根的 .gitignore（[followGitignore]）。
 */
data class WatchFilter(
    val ignoredNames: Set<String> = emptySet(),
    val extraPatterns: List<String> = emptyList(),
    val followGitignore: Boolean = true
) {
    companion object {
        val DEFAULT = WatchFilter()
    }
}

/** 目录剪枝判定（纯逻辑，便于单测）。 */
class IgnoreRules internal constructor(
    private val ignoredNames: Set<String>,
    private val patterns: List<String>
) {
    /** [relativeParts] 为相对订阅根的路径段（调用方只传父段，不含最后一段）。 */
    fun isIgnoredDir(relativeParts: List<String>): Boolean {
        if (relativeParts.isEmpty()) return false
        if (ignoredNames.isNotEmpty() && relativeParts.any { it in ignoredNames }) return true
        return patterns.isNotEmpty() && GitIgnoreMatcher.isIgnored(patterns, relativeParts)
    }

    companion object {
        fun of(filter: WatchFilter, gitignorePatterns: List<String>): IgnoreRules =
            IgnoreRules(filter.ignoredNames, gitignorePatterns + filter.extraPatterns)
    }
}

/** 仅需「有变更」这一信号的 UI 订阅用；批量窗口已把同一目录的密集变更合并为一次。 */
fun Flow<FileChangeBatch>.asDirtySignal(): Flow<Unit> = map { }

/** 把宿主绝对路径转成相对 [root] 的路径段。 */
internal fun relativePartsOf(root: File, path: File): List<String> {
    val rootPath = root.absolutePath.trimEnd(File.separatorChar)
    val abs = path.absolutePath
    val rel = if (abs == rootPath) "" else abs.removePrefix("$rootPath/")
    return rel.split(File.separatorChar, '/').filter { it.isNotEmpty() }
}

/** 读取订阅根的 .gitignore（去空行与注释、去尾斜杠）；不存在或不可读返回空。 */
internal fun readGitignorePatterns(root: File): List<String> {
    val file = File(root, ".gitignore")
    if (!file.isFile) return emptyList()
    return runCatching {
        file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.removeSuffix("/") }
    }.getOrDefault(emptyList())
}

/**
 * 目录单层快照：子项名 → 标记。文件记 mtime+size，目录只记类型（目录自身的 mtime 会随其内部
 * 增删而变，纳入比对只会与子目录自己的事件重复上报）。
 */
internal fun snapshotDir(dir: File): Map<String, String> {
    val children = dir.listFiles() ?: return emptyMap()
    val map = HashMap<String, String>(children.size)
    for (child in children) {
        map[child.name] =
            if (child.isDirectory) "d" else "f:${child.lastModified()}:${child.length()}"
    }
    return map
}

/** 对比前后快照，产出子项名 → 变更类型。 */
internal fun diffSnapshot(
    prev: Map<String, String>,
    now: Map<String, String>
): List<Pair<String, ChangeKind>> {
    if (prev.isEmpty() && now.isEmpty()) return emptyList()
    val out = ArrayList<Pair<String, ChangeKind>>()
    for ((name, value) in now) {
        val old = prev[name]
        when {
            old == null -> out += name to ChangeKind.CREATED
            old != value -> out += name to ChangeKind.MODIFIED
        }
    }
    for (name in prev.keys) {
        if (name !in now) out += name to ChangeKind.DELETED
    }
    return out
}

/** 同一路径在一个窗口内先后发生多次变更时的合并规则。 */
internal fun mergeKind(prev: ChangeKind, next: ChangeKind): ChangeKind = when {
    prev == next -> prev
    (prev == ChangeKind.CREATED && next == ChangeKind.DELETED) ||
        (prev == ChangeKind.DELETED && next == ChangeKind.CREATED) -> ChangeKind.MODIFIED
    else -> next
}