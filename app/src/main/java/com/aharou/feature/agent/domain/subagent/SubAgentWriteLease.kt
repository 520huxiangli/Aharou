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

    private companion object {
        /** 兜底上限：子会话 id 不会复用，正常路径走 release；超限时整体清空避免无限累积。 */
        const val MAX_ENTRIES = 200
    }
}

/** 写路径租约的判定逻辑，纯函数形式供文件工具调用（工具侧无需再注入租约本体）。 */
internal object WriteLease {
    const val WILDCARD = "*"

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

    /** 允许项与实际路径都归一化后比较：完全相等，或实际路径位于该目录之下。 */
    internal fun matches(allowed: String, path: String): Boolean {
        val a = normalize(allowed)
        if (a.isEmpty()) return false
        val p = normalize(path)
        return p == a || p.startsWith("$a/")
    }

    private fun normalize(raw: String): String {
        var s = raw.trim().replace('\\', '/')
        if (s.startsWith("~/")) s = s.removePrefix("~")
        while (s.contains("//")) s = s.replace("//", "/")
        return s.trimEnd('/')
    }
}
