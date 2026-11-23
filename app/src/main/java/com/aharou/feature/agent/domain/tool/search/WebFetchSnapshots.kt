package com.aharou.feature.agent.domain.tool.search

import java.security.MessageDigest

/**
 * webfetch 的正文快照，模型靠 `document_id` + `offset` 续读，所以只想留一轮：
 *
 * - 按轮次（会话 id + 本轮用户消息 id）隔离，同一会话进到新一轮时上一轮的快照当场清掉，
 *   不让它退化成跨轮的隐形长文本缓存；
 * - 全局最多留 [MAX_DOCUMENTS] 份、总共不超过 [MAX_TOTAL_CHARS] 字符，超了按最久未用淘汰，
 *   并发会话同时抓大页面也不会把内存顶爆；
 * - id 是「URL + 会话内序号」的 SHA-256 摘要，不含 URL 原文，也不存在同名文件/路径的问题。
 */
internal object WebFetchSnapshots {

    /** 同时保留的快照份数上限。 */
    private const val MAX_DOCUMENTS = 8

    /** 全部快照的字符数总和上限（约等于 4 份满额快照）。 */
    private const val MAX_TOTAL_CHARS = 1_600_000

    /**
     * 单份快照保留的最大字符数。源页面再长也只留前段并以 [Captured.sourceTruncated] 标记，
     * 让模型知道「后面还有但拿不到」，而不是以为读到末尾了。
     */
    const val MAX_SNAPSHOT_CHARS = 400_000

    /** 一个簇装不下时向前扩展的上限：防病态文本（比如几十万个组合记号）把单次返回撑爆。 */
    private const val MAX_CLUSTER_EXTENSION = 1024

    /** 一次抓取落库后的引用信息，足够拼出给模型的续读提示，不必把正文再复制一份。 */
    data class Captured(
        val id: String,
        val totalChars: Int,
        val sourceTruncated: Boolean
    )

    sealed interface Slice {
        data class Ok(
            val doc: Captured,
            val start: Int,
            val end: Int,
            val text: String
        ) : Slice

        /** 快照属于别的轮次或已被淘汰。 */
        data object Expired : Slice

        /** 根本没有这个 id（模型抄错、或者进程重启过）。 */
        data object Unknown : Slice

        /** offset 超过了快照长度。 */
        data class OutOfRange(val totalChars: Int) : Slice
    }

    private class Entry(
        val doc: Captured,
        val sessionKey: String,
        val turnKey: String,
        val text: String
    ) {
        var lastAccess: Long = 0L
    }

    private val entries = LinkedHashMap<String, Entry>()

    /**
     * 刚清掉的 id。光是删掉不够：模型拿着上一轮的 document_id 来续读时会得到「找不到，可能抄错了」，
     * 它会去怀疑自己的参数而不是换一次 url。留个最近失效名单，才能回一句准确的「上一轮已清理」。
     */
    private val retired = LinkedHashSet<String>()
    private val lock = Any()
    private var sequence: Long = 0L

    /** 落一份快照，返回给模型的 id 与长度信息。 */
    fun capture(raw: String, sessionKey: String, turnKey: String, url: String): Captured {
        return synchronized(lock) {
            dropPreviousTurns(sessionKey, turnKey)
            val text = if (raw.length > MAX_SNAPSHOT_CHARS) {
                raw.substring(0, safeEnd(raw, 0, MAX_SNAPSHOT_CHARS))
            } else {
                raw
            }
            val doc = Captured(idFor(url), text.length, text.length < raw.length)
            entries[doc.id] = Entry(doc, sessionKey, turnKey, text).also { it.lastAccess = ++sequence }
            evict()
            doc
        }
    }

    /** 从快照里切一段；不抓网、不碰网络。 */
    fun slice(id: String, sessionKey: String, turnKey: String, offset: Int, maxChars: Int): Slice {
        return synchronized(lock) {
            // 本轮已经开始：同一会话上一轮的快照在这次调用里就清掉，不等到下一次抓取
            dropPreviousTurns(sessionKey, turnKey)
            val entry = entries[id] ?: return if (id in retired) Slice.Expired else Slice.Unknown
            if (entry.turnKey != turnKey) {
                // 同一会话的旧轮次留不住，顺手删掉；别的会话的交给它自己的下一轮清理。
                if (entry.sessionKey == sessionKey) retire(id)
                return Slice.Expired
            }
            if (offset > entry.text.length) return Slice.OutOfRange(entry.text.length)
            entry.lastAccess = ++sequence
            val start = safeStart(entry.text, offset)
            var end = safeEnd(entry.text, start, maxChars)
            // 起点为了不切碎字符往回过：保证本次一定越过调用方给的 offset，
            // 否则 next_offset 原地不动，模型会一直续读同一段。
            if (end <= offset && offset < entry.text.length) end = safeEnd(entry.text, offset, maxChars)
            Slice.Ok(entry.doc, start, end, entry.text.substring(start, end))
        }
    }

    private fun dropPreviousTurns(sessionKey: String, turnKey: String) {
        val stale = entries.filterValues { it.sessionKey == sessionKey && it.turnKey != turnKey }.keys.toList()
        stale.forEach { retire(it) }
    }

    private fun retire(id: String) {
        entries.remove(id)
        retired.add(id)
        // 失效名单只为了分清「抄错」与「已过期」，不必无限长
        while (retired.size > MAX_DOCUMENTS * 4) retired.remove(retired.first())
    }

    private fun evict() {
        var totalChars = entries.values.sumOf { it.text.length }
        while (entries.size > MAX_DOCUMENTS || totalChars > MAX_TOTAL_CHARS) {
            val oldest = entries.entries.minByOrNull { it.value.lastAccess } ?: return
            totalChars -= oldest.value.text.length
            retire(oldest.key)
        }
    }

    private fun idFor(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$url#${++sequence}".toByteArray(Charsets.UTF_8))
        return "wf-" + digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** 切片起点：偏移落进代理对或组合字符序列中间时回退到序列起点。 */
    private fun safeStart(text: String, offset: Int): Int {
        var start = offset.coerceIn(0, text.length)
        var moved = true
        while (moved && start > 0 && start < text.length) {
            moved = false
            if (text[start].isLowSurrogate() && text[start - 1].isHighSurrogate()) {
                start--
                moved = true
                continue
            }
            if (bindsToPrevious(text, start)) {
                start--
                moved = true
            }
        }
        return start
    }

    /** 切片终点：不把代理对、组合记号和 ZWJ 表情序列切两半，边界不安全时向回让位。 */
    private fun safeEnd(text: String, start: Int, maxChars: Int): Int {
        val hardEnd = (start + maxChars).coerceAtMost(text.length)
        if (hardEnd <= start) return hardEnd
        var end = hardEnd
        while (end > start && unsafeToCutAt(text, end)) end--
        if (end > start) return end
        // 回退到零进展（预算连一个簇都装不下）：向前吃满整个簇，保证这次至少能推进一段
        var forward = hardEnd
        val limit = minOf(text.length, hardEnd + MAX_CLUSTER_EXTENSION)
        while (forward < limit && unsafeToCutAt(text, forward)) forward++
        return forward.coerceAtLeast(minOf(start + 1, text.length))
    }

    /** [index] 处切下去会把一个字符切成两半。 */
    private fun unsafeToCutAt(text: String, index: Int): Boolean {
        if (index <= 0) return false
        if (index < text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) return true
        return index < text.length && bindsToPrevious(text, index)
    }

    /** [index] 处的字符是否与它前面那段属于同一个可见字符。 */
    private fun bindsToPrevious(text: String, index: Int): Boolean {
        val c = text[index]
        if (c == '\u200D') return true
        if (text[index - 1] == '\u200D') return true
        if (c == '\uFE0F' || c == '\uFE0E') return true
        return when (Character.getType(c)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt() -> true
            else -> false
        }
    }
}
