package com.aharou.core.util

/**
 * gitignore 风格路径匹配（适度实现，不做完整规范解析）：
 * - `*.ext`：匹配任意层级同名扩展名的文件/目录；
 * - `**` 通配前缀：任意层级开始；
 * - 多段模式（如 `build/*.log`）：在路径段序列的任意位置匹配；
 * - 前导 `/` 锚定：[anchored] 为 true 且无 `**/` 前缀时，模式必须从根路径段开始匹配；
 *   为 false 时保守地按非锚定处理（宁可多忽略，不因解析失败漏忽略）。
 * 匹配基于完整路径段序列，调用方需传入相对工作区根的路径段。
 */
object GitIgnoreMatcher {

    /** 任一模式命中即忽略。 */
    fun isIgnored(patterns: List<String>, parts: List<String>, anchored: Boolean = false): Boolean =
        patterns.any { matches(it, parts, anchored) }

    fun matches(pattern: String, parts: List<String>, anchored: Boolean = false): Boolean {
        var p = pattern.trim().trimEnd('/').trimStart('/')
        if (p.isEmpty()) return false
        // 双星通配前缀：任意层级开始（此时忽略 anchored，`**/` 本身就是非锚定语义）
        val recursive = p.startsWith("**/")
        if (recursive) p = p.removePrefix("**/")
        if (p.isEmpty()) return false
        // *.ext 文件模式：匹配任意层级的文件名
        if (p.startsWith("*.") && '/' !in p) {
            val ext = p.removePrefix("*.")
            return parts.any { it.endsWith(ext) }
        }
        val segs = p.split('/')
        if (segs.size > parts.size) return false
        val starts = if (anchored && !recursive) 0..0 else 0..(parts.size - segs.size)
        return starts.any { start ->
            segs.indices.all { i -> segMatch(segs[i], parts[start + i]) }
        }
    }

    /** 单段匹配：`**` 与 `*` 通配段、精确段。 */
    private fun segMatch(pattern: String, segment: String): Boolean {
        if (pattern == "**") return true
        if ('*' !in pattern) return pattern == segment
        // 手写转义：正则特殊字符加反斜杠，`*` 直接展开为 `.*`。
        // （Regex.escape 在 Kotlin 2.x 返回 \Q...\E 字面量形式，无法再通过 replace 展开通配符）
        val escaped = buildString {
            for (ch in pattern) {
                if (ch == '*') append(".*")
                else if (ch in ".+?()[]{}\\^$|") append('\\').append(ch)
                else append(ch)
            }
        }
        return Regex("^$escaped$").matches(segment)
    }
}
