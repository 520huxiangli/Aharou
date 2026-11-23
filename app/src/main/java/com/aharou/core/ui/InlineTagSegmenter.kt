/*
 * 行为参考 AAswordman/Operit（ui/common/markdown/StreamMarkdownRenderer.kt 的 XML_BLOCK 切分与
 * util/WaifuMessageProcessor.kt 的 isRenderablePluginXmlContent 闭合判定，LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发。
 *
 * 与上游的区别：上游在字符流处理器里增量产出 XML 块；这里是纯文本一次性切分，
 * 目的只是把「已注册的标签」从 markdown 正文里摘出来，其余内容仍原样交给 markdown 渲染。
 */

package com.aharou.core.ui

/** 内联标签切分结果：普通文本段，或一个完整的已注册标签块。 */
sealed interface InlineTagSegment {
    data class Text(val text: String) : InlineTagSegment

    /**
     * [raw] 是含首尾标签的原文，渲染失败时原样回显；
     * [innerText] 是标签之间的内容，[attributes] 是解析后的属性表。
     */
    data class Tag(
        val tag: String,
        val attributes: Map<String, String>,
        val attributesRaw: String,
        val innerText: String,
        val raw: String
    ) : InlineTagSegment
}

/**
 * 把一段 markdown 正文按「已注册的标签名」切成文本段与标签段。
 *
 * 三条边界：
 * - 流式安全：只有成对闭合（或自闭合）的标签才成段，未闭合的 `<tag ...>` 原样留在文本里，
 *   否则每帧半个标签都会让用户正在看的正文消失；
 * - 非注册标签不动：`<b>`、`<details>` 这类尖括号按普通文本处理，仍然交给 markdown（可能被当内联 HTML）；
 * - 围栏代码块内一律不算标签：用户贴的示例代码不能被渲染成组件。
 */
object InlineTagSegmenter {
    private val TAG_NAME = Regex("^[a-z0-9_]{1,32}$")

    /** CommonMark 围栏：行首最多三个空格，三个及以上反引号或波浪线。 */
    private val FENCE_OPEN = Regex("^ {0,3}(`{3,}|~{3,})")

    private val ATTRIBUTE = Regex(
        """([A-Za-z_:][-A-Za-z0-9_:.]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'<>]+))"""
    )

    fun segment(text: String, tags: Set<String>): List<InlineTagSegment> {
        if (text.isEmpty()) return emptyList()
        val names = tags.asSequence()
            .map { it.trim().lowercase() }
            .filter { TAG_NAME.matches(it) }
            .distinct()
            // 长名优先：同时注册了 voice 与 voice_bubble 时不能让短名前缀抢先匹配。
            .sortedByDescending { it.length }
            .toList()
        if (names.isEmpty() || !text.contains('<')) return listOf(InlineTagSegment.Text(text))

        val fences = fencedRanges(text)
        val segments = mutableListOf<InlineTagSegment>()
        val buffer = StringBuilder()
        var fenceIndex = 0
        var i = 0
        while (i < text.length) {
            while (fenceIndex < fences.size && i > fences[fenceIndex].last) fenceIndex++
            val fence = fences.getOrNull(fenceIndex)
            if (fence != null && i >= fence.first) {
                buffer.append(text, i, fence.last + 1)
                i = fence.last + 1
                continue
            }
            if (text[i] != '<') {
                buffer.append(text[i])
                i++
                continue
            }
            val match = matchTag(text, i, names)
            if (match == null) {
                buffer.append(text[i])
                i++
                continue
            }
            flush(segments, buffer)
            segments += match.segment
            i = match.endExclusive
        }
        flush(segments, buffer)
        return normalize(segments)
    }

    private class TagMatch(val segment: InlineTagSegment.Tag, val endExclusive: Int)

    private fun matchTag(text: String, start: Int, names: List<String>): TagMatch? {
        // 闭合标签本身不参与切分，交给外层文本；避免把 `</voice_bubble>` 当新块。
        if (start + 1 >= text.length || text[start + 1] == '/') return null
        val name = names.firstOrNull { name ->
            text.regionMatches(start + 1, name, 0, name.length, ignoreCase = true) &&
                isNameBoundary(text, start + 1 + name.length)
        } ?: return null
        val nameEnd = start + 1 + name.length
        val gt = findOpeningEnd(text, nameEnd) ?: return null
        val rawAttrs = text.substring(nameEnd, gt)
        val trimmedAttrs = rawAttrs.trimEnd()
        val selfClosing = trimmedAttrs.endsWith("/")
        val attributesRaw = if (selfClosing) trimmedAttrs.dropLast(1).trim() else trimmedAttrs.trim()
        val attributes = parseAttributes(attributesRaw)
        if (selfClosing) {
            return TagMatch(
                InlineTagSegment.Tag(name, attributes, attributesRaw, innerText = "", raw = text.substring(start, gt + 1)),
                endExclusive = gt + 1
            )
        }
        val close = findClosing(text, name, gt + 1) ?: return null
        return TagMatch(
            InlineTagSegment.Tag(
                tag = name,
                attributes = attributes,
                attributesRaw = attributesRaw,
                innerText = text.substring(gt + 1, close.first),
                raw = text.substring(start, close.last + 1)
            ),
            endExclusive = close.last + 1
        )
    }

    private fun isNameBoundary(text: String, index: Int): Boolean =
        index >= text.length || text[index].isWhitespace() || text[index] == '>' || text[index] == '/'

    /** 找开标签结尾的 `>`；引号内的 `>` 不算结尾，遇到 `<`（说明是别的尖括号）或到文本末尾就放弃。 */
    private fun findOpeningEnd(text: String, from: Int): Int? {
        var i = from
        var quote: Char? = null
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return i
                c == '<' -> return null
            }
            i++
        }
        return null
    }

    /** 找 `</name>` 的区间；标签名大小写不敏感，闭合标签内允许空白。 */
    private fun findClosing(text: String, name: String, from: Int): IntRange? {
        var searchFrom = from
        while (true) {
            val close = text.indexOf("</", searchFrom)
            if (close < 0) return null
            val nameStart = close + 2
            if (nameStart + name.length <= text.length &&
                text.regionMatches(nameStart, name, 0, name.length, ignoreCase = true)
            ) {
                var i = nameStart + name.length
                while (i < text.length && text[i].isWhitespace()) i++
                if (i < text.length && text[i] == '>') return close..i
            }
            searchFrom = close + 2
        }
    }

    private fun parseAttributes(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val attributes = LinkedHashMap<String, String>()
        ATTRIBUTE.findAll(raw).forEach { match ->
            val value = match.groupValues[2].ifEmpty {
                match.groupValues[3].ifEmpty { match.groupValues[4] }
            }
            attributes[match.groupValues[1]] = value
        }
        return attributes
    }

    /** 围栏代码块的字符区间（含围栏行）；未闭合的围栏一直延续到文本末尾，与 markdown 的渲染口径一致。 */
    private fun fencedRanges(text: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var fenceChar: Char? = null
        var fenceLength = 0
        var openStart = 0
        var lineStart = 0
        while (lineStart <= text.length) {
            val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
            val line = text.substring(lineStart, lineEnd)
            if (fenceChar == null) {
                val match = FENCE_OPEN.find(line)
                if (match != null) {
                    val marker = match.groupValues[1]
                    fenceChar = marker[0]
                    fenceLength = marker.length
                    openStart = lineStart
                }
            } else {
                val trimmed = line.trim()
                if (trimmed.length >= fenceLength && trimmed.all { it == fenceChar }) {
                    ranges += openStart..lineEnd.coerceAtMost(text.length - 1)
                    fenceChar = null
                }
            }
            if (lineEnd >= text.length) break
            lineStart = lineEnd + 1
        }
        if (fenceChar != null) ranges += openStart..(text.length - 1)
        return ranges
    }

    private fun flush(target: MutableList<InlineTagSegment>, buffer: StringBuilder) {
        if (buffer.isEmpty()) return
        target += InlineTagSegment.Text(buffer.toString())
        buffer.clear()
    }

    /**
     * 标签独占一行时，前后只剩换行的文本段直接丢掉：否则组件上下会各多出一个空段落，
     * 与「标签就是要占据这一行」的预期不符。段内的普通换行保持原样。
     */
    private fun normalize(segments: List<InlineTagSegment>): List<InlineTagSegment> {
        if (segments.none { it is InlineTagSegment.Tag }) return segments
        val result = mutableListOf<InlineTagSegment>()
        segments.forEachIndexed { index, segment ->
            if (segment !is InlineTagSegment.Text) {
                result += segment
                return@forEachIndexed
            }
            val nextToTag =
                segments.getOrNull(index - 1) is InlineTagSegment.Tag ||
                    segments.getOrNull(index + 1) is InlineTagSegment.Tag
            val value = if (nextToTag) segment.text.trim('\n') else segment.text
            if (value.isEmpty()) return@forEachIndexed
            val previous = result.lastOrNull()
            if (previous is InlineTagSegment.Text) {
                result[result.lastIndex] = InlineTagSegment.Text(previous.text + value)
            } else {
                result += InlineTagSegment.Text(value)
            }
        }
        return result
    }
}
