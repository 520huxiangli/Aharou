package com.aharou.feature.agent.domain.subagent

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理写路径租约：`task` 派发子代理时可声明它允许写的路径，
 * 写文件类工具据此在执行期做强制闸门——声明空集即只读，越界直接拒绝。
 *
 * 闸门只覆盖文件工具（writeFile / editFile）；shell 里的重定向、`sed -i`、`mv`、`rm`
 * 不在拦截范围内。要严格隔离写入，就必须让子代理走文件工具并按路径拆分任务。
 */
@Singleton
class SubAgentWriteLease @Inject constructor() {
    private val leases = ConcurrentHashMap<String, Set<String>>()

    fun register(sessionId: String, paths: Set<String>) {
        if (leases.size > MAX_ENTRIES) leases.clear()
        leases[sessionId] = paths
    }

    fun release(sessionId: String) {
        leases.remove(sessionId)
    }

    /**
     * 运行期追加可写路径（与已声明的合并，不是覆盖），返回合并后的完整集合。
     *
     * 给「派发时把路径猜窄了、子代理写到租约外被拦」兜底：主会话收到求助后追加即可，
     * 不必重派一个同样的任务（重派还要再烧一遍上下文）。
     */
    fun grant(sessionId: String, paths: Set<String>): Set<String> {
        val merged = (leases[sessionId].orEmpty() + paths).toSet()
        if (leases.size > MAX_ENTRIES && !leases.containsKey(sessionId)) leases.clear()
        leases[sessionId] = merged
        return merged
    }

    /**
     * 追加的写范围是否与**其它**仍在运行的租约重叠（不含 [sessionId] 自己）。
     * 运行期追加要过这道检查，规则与派发时一致。
     */
    fun conflictFor(sessionId: String, writePaths: Set<String>, activeSessionIds: Set<String>): String? =
        leases.entries.firstOrNull { (id, existing) ->
            id != sessionId && id in activeSessionIds && WriteLease.overlaps(writePaths, existing)
        }?.key

    /** 该会话声明的可写路径；未登记（主会话、或派发时未声明）返回 null 表示不限制。 */
    fun pathsFor(sessionId: String?): Set<String>? = sessionId?.let { leases[it] }

    /**
     * 新派发的写租约是否与**仍在运行**的租约重叠（同一时刻两个子代理写同一处会互相踩）。
     * 只读租约（空集）不参与冲突；`*` 与任何会写的租约互斥。
     *
     * 只看活跃集合：已结束但未显式 stop 的子代理不再算冲突，它们的租约保留不释放——
     * 被后续消息重新唤醒时会话仍受同一份写范围约束。
     *
     * @return 冲突的会话 id；null 表示无冲突。
     */
    fun conflictingSession(writePaths: Set<String>, activeSessionIds: Set<String>): String? = leases.entries
        .firstOrNull { (id, existing) -> id in activeSessionIds && WriteLease.overlaps(writePaths, existing) }
        ?.key

    private companion object {
        /** 兜底上限：子会话 id 不会复用，正常路径走 release；超限时整体清空避免无限累积。 */
        const val MAX_ENTRIES = 200
    }
}

/** 写路径租约的判定逻辑，纯函数形式供文件工具调用（工具侧无需再注入租约本体）。 */
internal object WriteLease {
    const val WILDCARD = "*"

    /** 租约拒绝文案的固定前缀：被拦截的写入据此被判定层识别。 */
    const val DENIAL_PREFIX = "写路径租约拒绝"

    /** 冲突文案的下一步：派发新子代理时。 */
    const val NEXT_CREATE = "下一步：等它结束后再派发；或把这份活儿改成只读并行（writePaths=[]）；" +
        "或收窄 writePaths 到与它不相交的位置。"

    /** 冲突文案的下一步：运行期追加写权限时。 */
    const val NEXT_GRANT = "下一步：等它结束后再 grant，或收窄 writePaths 到与它不相交的位置；" +
        "也可以让父会话自己写这部分。"

    /** 冲突文案的下一步：流水线同层。 */
    const val NEXT_LAYER = "下一步：把其中一个改成只读（writePaths=[]），或用 dependsOn 把它们串到不同层。"

    /**
     * 返回拒绝原因；null 表示放行。
     *
     * 文案要能让读到它的模型直接动手：谁被拒、想写哪里、允许哪里、下一步调什么工具。
     * [sessionId] / [workspaceRoot] 只用于把话说清（子代理 id 与相对路径），拿不到时退化成通用说法，
     * 判定本身仍只看 [writePaths] 与 [path]。
     */
    fun denialReason(
        writePaths: Set<String>?,
        path: String,
        sessionId: String? = null,
        workspaceRoot: String? = null
    ): String? {
        if (writePaths == null) return null
        if (WILDCARD in writePaths) return null
        val who = sessionId?.let { "子代理 $it" } ?: "本子代理"
        val target = relativeForm(path, workspaceRoot)
        if (writePaths.isEmpty()) {
            return "$who 是只读的（派发时 writePaths=[]），不能写 $target。" +
                "把结论交回父会话；确需写入由父会话执行 ${grantHint(sessionId, target)} 追加，别自行放宽写范围。"
        }
        if (writePaths.any { matches(it, path) }) return null
        return "$who 想写 $target，不在它声明的可写范围（${pathsText(writePaths, workspaceRoot)}）内。" +
            "下一步：把这条求助交回父会话，由它执行 ${grantHint(sessionId, target)} 追加，" +
            "grant 即改即生效、不必重派；写范围不由本子代理自行放宽。"
    }

    /** grant 的调用样例：id 取被拒的那个子会话，writePaths 就填被拦的那条路径。 */
    private fun grantHint(sessionId: String?, path: String): String =
        "task(action=\"grant\", id=\"${sessionId ?: "<本子代理会话 id>"}\", writePaths=[\"$path\"])"

    /** 与在跑子代理的写冲突：说清是谁（id + 有标题就带上标题）、它占着什么、你申请什么、下一步怎么办。 */
    fun conflictMessage(
        conflictId: String,
        conflictTitle: String?,
        mine: Set<String>?,
        theirs: Set<String>?,
        next: String = NEXT_CREATE,
        workspaceRoot: String? = null
    ): String {
        val who = "子代理「$conflictId」" + if (conflictTitle.isNullOrBlank()) "" else "（$conflictTitle）"
        return "写范围与在跑的${who}冲突：它占着 ${pathsText(theirs, workspaceRoot)}，" +
            "你申请的是 ${pathsText(mine, workspaceRoot)}。$next"
    }

    /** 流水线同层冲突：点出两个任务名、各自写范围与相交路径。 */
    fun layerConflictMessage(
        aId: String,
        aPaths: Set<String>?,
        bId: String,
        bPaths: Set<String>?,
        workspaceRoot: String? = null
    ): String {
        val overlap = overlapDetail(aPaths.orEmpty(), bPaths.orEmpty()).toSet().takeIf { it.isNotEmpty() }
            ?.let { "（相交路径：${pathsText(it, workspaceRoot)}）" }
            .orEmpty()
        return "同一层的「$aId」（${pathsText(aPaths, workspaceRoot)}）与「$bId」（${pathsText(bPaths, workspaceRoot)}）" +
            "写范围相交$overlap。$NEXT_LAYER"
    }

    /** 两段写范围相交的那部分；取更具体的那个路径（`docs` 与 `docs/a.md` 相交于后者）。 */
    fun overlapDetail(a: Set<String>, b: Set<String>): List<String> {
        if (a.isEmpty() || b.isEmpty()) return emptyList()
        if (WILDCARD in a || WILDCARD in b) return listOf(WILDCARD)
        return a.flatMap { x ->
            b.mapNotNull { y ->
                when {
                    covers(x, y) -> y
                    covers(y, x) -> x
                    else -> null
                }
            }
        }.distinct().sorted()
    }

    /** 写范围的可读文本：排序、相对工作区；null 表示未声明（不限制），空集表示只读。 */
    fun pathsText(paths: Set<String>?, workspaceRoot: String? = null): String {
        val set = paths ?: return "未声明（等于不限制）"
        if (set.isEmpty()) return "空集（只读）"
        if (WILDCARD in set) return "*（全部路径）"
        return set.map { relativeForm(it, workspaceRoot) }.sorted().joinToString("、")
    }

    /** 相对工作区的写法（更好读）；不在工作区之下或拿不到工作区时原样返回。 */
    fun relativeForm(raw: String, workspaceRoot: String? = null): String {
        val p = normalize(raw)
        if (p.isEmpty() || p.startsWith("..")) return raw.trim()
        val roots = listOfNotNull(
            workspaceRoot?.takeIf { it.isNotBlank() }?.let { normalize(it) }?.takeIf { it.isNotEmpty() },
            // 容器里工作区固定挂在 `~/workspace`：做了相对化的调用方没传 root 时按它剥一层。
            "workspace"
        ).distinct()
        val stripped = roots.firstOrNull { p == it || p.startsWith("$it/") }
            ?.let { p.removePrefix(it).removePrefix("/") }
        return stripped?.takeIf { it.isNotEmpty() } ?: p
    }

    /** 两个租约是否可能写到同一处；只读租约（空集）不冲突。 */
    internal fun overlaps(a: Set<String>, b: Set<String>): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (WILDCARD in a || WILDCARD in b) return true
        return a.any { x -> b.any { y -> covers(x, y) || covers(y, x) } }
    }

    /** 允许项与实际路径都归一化后比较：完全相等，或实际路径位于该目录之下。 */
    internal fun matches(allowed: String, path: String): Boolean = covers(allowed, path)

    private fun covers(allowed: String, path: String): Boolean {
        val p = normalize(path)
        if (p.isEmpty() || p.startsWith("..")) return false
        val a = normalize(allowed)
        // `"."` / `"/"` 归一化后为空串：表示工作区根，覆盖除越界以外的全部路径。
        if (a.isEmpty()) return true
        // `~/workspace/x` 与工作区相对路径 `x` 指同一处：两侧都试「去掉顶层 workspace/」的形态，
        // 否则模型按容器路径写法落盘会被租约误判成越界。
        val allowedForms = workspaceRelativeForms(a)
        return workspaceRelativeForms(p).any { pf -> allowedForms.any { af -> pf == af || pf.startsWith("$af/") } }
    }

    /** 同一路径的两种写法：原样，以及去掉容器家目录带来的顶层 `workspace/`。 */
    private fun workspaceRelativeForms(normalized: String): List<String> {
        val stripped = normalized.removePrefix("workspace/")
        return if (stripped == normalized) listOf(normalized) else listOf(normalized, stripped)
    }

    /**
     * 统一分隔符与重复斜杠，并**按段**消解 `.` 与 `..`。
     * 只做字符串裁剪时 `docs/../secret.txt` 会因前缀匹配被判定在 `docs` 之下，租约形同虚设。
     * 逃出顶层（结果以 `..` 开头）的路径原样保留，[covers] 随之判为不在范围内。
     */
    private fun normalize(raw: String): String {
        var s = raw.trim().replace('\\', '/')
        if (s.startsWith("~/")) s = s.removePrefix("~")
        val absolute = s.startsWith("/")
        val segments = ArrayList<String>()
        s.split('/').forEach { segment ->
            if (segment.isEmpty() || segment == ".") {
                // 当前目录：跳过
            } else if (segment == "..") {
                if (segments.isNotEmpty() && segments.last() != "..") {
                    segments.removeAt(segments.size - 1)
                } else if (!absolute) {
                    segments.add("..")
                }
            } else {
                segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }
}
