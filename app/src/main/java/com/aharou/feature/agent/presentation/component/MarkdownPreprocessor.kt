package com.aharou.feature.agent.presentation.component

import org.jsoup.parser.Parser
import java.util.Base64

/**
 * 在把文本交给 Markdown 渲染器前做的轻量预处理：
 *
 * 1. 抽取 LaTeX 数学（`$$...$$` 块级、`$...$` 行内），编码为特殊 scheme 的 Markdown 图片链接，
 *    交由 [MathImageTransformer] 用 jlatexmath 渲染。
 * 2. 把常见 HTML 标签映射为 Markdown 等价语法（渲染器本身会静默丢弃不认识的 HTML）。
 * 3. 解码 HTML 实体。
 *
 * 所有处理都跳过围栏代码块与行内代码，避免破坏代码样例里的 `$` / `<` 等字符。
 */
internal object MarkdownPreprocessor {

    // 进程级结果缓存：process 会在 item 每次滚入视口时被调（上层只有 remember(text)，滚出
    // 被 dispose 后 remember 归零）。含 HTML/数学定界符的文本要跑正则/HTML 转换，快速 fling 时
    // 反复重跑拖慢主线程。这里按原文做进程级 LRU，同一段只处理一次。
    private const val CACHE_MAX = 256
    private val cache = object : LinkedHashMap<String, String>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > CACHE_MAX
    }

    fun process(raw: String): String {
        if (raw.isEmpty()) return raw
        // 无需处理的快速路径：既没有 HTML 标签、没有 $ 或 \( / \[ 数学定界符，也没有 & 实体
        if (!raw.contains('<') && !raw.contains('$') && !raw.contains('\\') && !raw.contains('&')) return raw

        synchronized(cache) { cache[raw] }?.let { return it }

        val sb = StringBuilder(raw.length + 32)
        for (seg in splitPreservingCode(raw)) {
            if (seg.isCode) sb.append(seg.text) else sb.append(processNormal(seg.text))
        }
        val result = sb.toString()
        synchronized(cache) { cache[raw] = result }
        return result
    }

    private data class Segment(val isCode: Boolean, val text: String)

    /** 扫描文本，把围栏代码块与行内代码切成 isCode=true 的段原样保留，其余为普通文本段。 */
    private fun splitPreservingCode(text: String): List<Segment> {
        val result = ArrayList<Segment>()
        val normal = StringBuilder()
        val n = text.length
        var i = 0
        var atLineStart = true

        fun flushNormal() {
            if (normal.isNotEmpty()) {
                result.add(Segment(false, normal.toString()))
                normal.clear()
            }
        }

        while (i < n) {
            val c = text[i]

            // 围栏代码块：行首连续 >=3 个 ` 或 ~
            if (atLineStart && (c == '`' || c == '~')) {
                var j = i
                while (j < n && text[j] == c) j++
                if (j - i >= 3) {
                    val fenceLen = j - i
                    var lineEnd = j
                    while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
                    var end = n
                    var lineStart = if (lineEnd < n) lineEnd + 1 else n
                    var k = lineStart
                    while (k <= n) {
                        if (k == n || text[k] == '\n') {
                            var p = lineStart
                            while (p < k && text[p] == ' ') p++
                            var q = p
                            while (q < k && text[q] == c) q++
                            if (q - p >= fenceLen) {
                                var rest = q
                                while (rest < k && text[rest] == ' ') rest++
                                if (rest == k) { end = k; break }
                            }
                            lineStart = k + 1
                        }
                        k++
                    }
                    flushNormal()
                    result.add(Segment(true, text.substring(i, end)))
                    i = end
                    atLineStart = i < n && text[i] == '\n'
                    continue
                }
            }

            // 行内代码：同长度 ` 序列包裹，且不跨行
            if (c == '`') {
                var j = i
                while (j < n && text[j] == '`') j++
                val tickLen = j - i
                var k = j
                var found = -1
                while (k < n && text[k] != '\n') {
                    if (text[k] == '`') {
                        var m = k
                        while (m < n && text[m] == '`') m++
                        if (m - k == tickLen) { found = m; break }
                        k = m
                    } else k++
                }
                if (found >= 0) {
                    flushNormal()
                    result.add(Segment(true, text.substring(i, found)))
                    i = found
                    atLineStart = false
                    continue
                }
            }

            normal.append(c)
            atLineStart = c == '\n'
            i++
        }
        flushNormal()
        return result
    }

    private fun processNormal(input: String): String {
        var s = extractMath(input)
        s = convertHtml(s)
        s = Parser.unescapeEntities(s, false)
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // 数学公式
    // ---------------------------------------------------------------------------------------------

    private val BLOCK_MATH_DOLLAR = Regex("""\$\$(.+?)\$\$""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_MATH_BRACKET = Regex("""\\\[(.+?)\\\]""", RegexOption.DOT_MATCHES_ALL)

    // 行内：定界符内侧首尾非空白、内容不含换行与 $，规避 "$5 ... $10" 这类货币误命中（非空白锚点）
    private val INLINE_MATH_DOLLAR = Regex("""\$(?=\S)([^\n$]*?\S)\$""")
    // LaTeX 标准行内定界符 \( ... \)
    private val INLINE_MATH_PAREN = Regex("""\\\((.+?)\\\)""", RegexOption.DOT_MATCHES_ALL)

    private fun extractMath(input: String): String {
        var s = input
        if (s.contains("$$")) {
            s = BLOCK_MATH_DOLLAR.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\[")) {
            s = BLOCK_MATH_BRACKET.replace(s) { m ->
                "\n\n" + encodeMathLink(m.groupValues[1].trim(), block = true) + "\n\n"
            }
        }
        if (s.contains("\\(")) {
            s = INLINE_MATH_PAREN.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        if (s.contains('$')) {
            s = INLINE_MATH_DOLLAR.replace(s) { m ->
                encodeMathLink(m.groupValues[1].trim(), block = false)
            }
        }
        return s
    }

    // ---------------------------------------------------------------------------------------------
    // HTML -> Markdown
    // ---------------------------------------------------------------------------------------------

    private val DOTALL_IC = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val IC = setOf(RegexOption.IGNORE_CASE)

    private fun convertHtml(input: String): String {
        if (!input.contains('<')) return input
        var s = input
        s = convertTables(s)
        s = convertLists(s)
        s = convertBlockquotes(s)
        s = convertDetails(s)

        s = Regex("""<a\b[^>]*?href\s*=\s*["']([^"']*)["'][^>]*>(.*?)</a>""", DOTALL_IC)
            .replace(s) { "[${it.groupValues[2].trim()}](${it.groupValues[1].trim()})" }
        s = Regex("""<img\b[^>]*>""", IC).replace(s) { imgToMarkdown(it.value) }

        s = Regex("""<br\s*/?>""", IC).replace(s, "  \n")
        s = Regex("""<hr\s*/?>""", IC).replace(s, "\n\n---\n\n")
        s = Regex("""</p\s*>""", IC).replace(s, "\n\n")
        s = Regex("""<p\b[^>]*>""", IC).replace(s, "")

        s = wrapInline(s, "strong", "**")
        s = wrapInline(s, "b", "**")
        s = wrapInline(s, "em", "*")
        s = wrapInline(s, "i", "*")
        s = wrapInline(s, "del", "~~")
        s = wrapInline(s, "strike", "~~")
        s = wrapInline(s, "s", "~~")
        s = convertScripts(s)
        s = Regex("""<code\b[^>]*>(.*?)</code>""", DOTALL_IC).replace(s) { "`${it.groupValues[1]}`" }

        // 其余标签（sub/sup/u/mark/span/div/font 等）：去标签保留内容
        s = Regex("""</?[a-zA-Z][^>]*>""").replace(s, "")
        return s
    }

    /** 把 `<tag>` 与 `</tag>` 都替换成 marker，实现 `<b>x</b>` -> `**x**`。 */    private fun wrapInline(input: String, tag: String, marker: String): String {
        var s = Regex("""<$tag\b[^>]*>""", IC).replace(input, marker)
        s = Regex("""</$tag\s*>""", IC).replace(s, marker)
        return s
    }

    private val SUPERSCRIPT = mapOf(
        '0' to '\u2070', '1' to '\u00B9', '2' to '\u00B2', '3' to '\u00B3', '4' to '\u2074',
        '5' to '\u2075', '6' to '\u2076', '7' to '\u2077', '8' to '\u2078', '9' to '\u2079',
        '+' to '\u207A', '-' to '\u207B', '=' to '\u207C', '(' to '\u207D', ')' to '\u207E',
        'n' to '\u207F', 'i' to '\u2071',
    )
    private val SUBSCRIPT = mapOf(
        '0' to '\u2080', '1' to '\u2081', '2' to '\u2082', '3' to '\u2083', '4' to '\u2084',
        '5' to '\u2085', '6' to '\u2086', '7' to '\u2087', '8' to '\u2088', '9' to '\u2089',
        '+' to '\u208A', '-' to '\u208B', '=' to '\u208C', '(' to '\u208D', ')' to '\u208E',
        'a' to '\u2090', 'e' to '\u2091', 'o' to '\u2092', 'x' to '\u2093', 'n' to '\u2099',
    )

    /** `<sub>2</sub>`/`<sup>2</sup>` -> Unicode 上下标字符；内容含无法映射的字符时保留原文。 */
    private fun convertScripts(input: String): String {
        var s = Regex("""<sub\b[^>]*>(.*?)</sub>""", DOTALL_IC).replace(input) { toScript(it.groupValues[1], SUBSCRIPT) }
        s = Regex("""<sup\b[^>]*>(.*?)</sup>""", DOTALL_IC).replace(s) { toScript(it.groupValues[1], SUPERSCRIPT) }
        return s
    }

    private fun toScript(text: String, map: Map<Char, Char>): String {
        val mapped = StringBuilder(text.length)
        for (c in text) mapped.append(map[c] ?: return text)
        return mapped.toString()
    }

    private fun imgToMarkdown(tag: String): String {
        val src = Regex("""src\s*=\s*["']([^"']*)["']""", IC).find(tag)?.groupValues?.get(1) ?: return ""
        val alt = Regex("""alt\s*=\s*["']([^"']*)["']""", IC).find(tag)?.groupValues?.get(1) ?: ""
        return "![$alt]($src)"
    }

    private fun convertLists(input: String): String {
        var s = input
        var pass = 0
        val listRe = Regex("""<(ul|ol)\b[^>]*>(.*?)</\1>""", DOTALL_IC)
        // 多趟以铺平有限层级的嵌套列表
        while (pass < 5 && listRe.containsMatchIn(s)) {
            s = listRe.replace(s) { m ->
                val ordered = m.groupValues[1].equals("ol", ignoreCase = true)
                val items = Regex("""<li\b[^>]*>(.*?)</li>""", DOTALL_IC)
                    .findAll(m.groupValues[2])
                    .mapIndexed { idx, li ->
                        val content = li.groupValues[1].trim().replace(Regex("""\s*\n\s*"""), " ")
                        if (ordered) "${idx + 1}. $content" else "- $content"
                    }
                    .joinToString("\n")
                if (items.isEmpty()) "" else "\n\n$items\n\n"
            }
            pass++
        }
        return s
    }

    private fun convertBlockquotes(input: String): String {
        return Regex("""<blockquote\b[^>]*>(.*?)</blockquote>""", DOTALL_IC).replace(input) { m ->
            val inner = Regex("""</?[a-zA-Z][^>]*>""").replace(m.groupValues[1], "").trim()
            val quoted = inner.lineSequence()
                .joinToString("\n") { line -> "> ${line.trim()}" }
            "\n\n$quoted\n\n"
        }
    }

    private fun convertDetails(input: String): String {
        return Regex("""<details\b[^>]*>(.*?)</details>""", DOTALL_IC).replace(input) { m ->
            var body = m.groupValues[1]
            val summary = Regex("""<summary\b[^>]*>(.*?)</summary>""", DOTALL_IC).find(body)?.groupValues?.get(1)?.trim()
            body = Regex("""<summary\b[^>]*>.*?</summary>""", DOTALL_IC).replace(body, "").trim()
            val head = if (!summary.isNullOrEmpty()) "**$summary**\n\n" else ""
            "\n\n$head$body\n\n"
        }
    }

    private fun convertTables(input: String): String {
        return Regex("""<table\b[^>]*>(.*?)</table>""", DOTALL_IC).replace(input) { m ->
            val rows = Regex("""<tr\b[^>]*>(.*?)</tr>""", DOTALL_IC).findAll(m.groupValues[1])
                .map { tr ->
                    Regex("""<t[hd]\b[^>]*>(.*?)</t[hd]>""", DOTALL_IC).findAll(tr.groupValues[1])
                        .map { cell ->
                            Regex("""</?[a-zA-Z][^>]*>""").replace(cell.groupValues[1], "")
                                .trim().replace(Regex("""\s*\n\s*"""), " ")
                        }
                        .toList()
                }
                .filter { it.isNotEmpty() }
                .toList()
            if (rows.isEmpty()) return@replace ""
            val cols = rows.maxOf { it.size }
            val sb = StringBuilder("\n\n")
            fun renderRow(cells: List<String>) {
                sb.append("| ")
                for (c in 0 until cols) sb.append(cells.getOrElse(c) { "" }).append(" | ")
                sb.append('\n')
            }
            renderRow(rows.first())
            sb.append("| ").append("--- | ".repeat(cols)).append('\n')
            rows.drop(1).forEach { renderRow(it) }
            sb.append('\n')
            sb.toString()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 数学链接编解码（供 MathImageTransformer 使用）
    // ---------------------------------------------------------------------------------------------

    const val MATH_BLOCK_SCHEME = "aicode-math-block://"
    const val MATH_INLINE_SCHEME = "aicode-math-inline://"

    fun encodeMathLink(latex: String, block: Boolean): String {
        val enc = Base64.getUrlEncoder().withoutPadding().encodeToString(latex.toByteArray(Charsets.UTF_8))
        return "![](${if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME}$enc)"
    }

    /** 解析数学链接；非数学链接返回 null。返回 (latex, isBlock)。 */
    fun decodeMathLink(link: String): Pair<String, Boolean>? {
        val block = link.startsWith(MATH_BLOCK_SCHEME)
        val inline = link.startsWith(MATH_INLINE_SCHEME)
        if (!block && !inline) return null
        val enc = link.removePrefix(if (block) MATH_BLOCK_SCHEME else MATH_INLINE_SCHEME)
        return try {
            val latex = String(Base64.getUrlDecoder().decode(enc), Charsets.UTF_8)
            latex to block
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
