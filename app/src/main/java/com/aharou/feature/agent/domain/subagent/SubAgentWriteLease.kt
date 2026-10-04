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

    /** 返回拒绝原因；null 表示放行。 */
    fun denialReason(writePaths: Set<String>?, path: String): String? {
        if (writePaths == null) return null
        if (WILDCARD in writePaths) return null
        if (writePaths.isEmpty()) {
            return "本子代理未声明可写路径（只读）"
        }
        if (writePaths.any { matches(it, path) }) return null
        return "路径不在本子代理声明的可写范围（允许：${writePaths.joinToString(", ")}）"
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
