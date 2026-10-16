package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.memory.isCoreArchiveName

internal object MemoryPromptRenderer {
    const val MAX_INDEX_CHARS = 8_000

    /** 常驻正文（长期约定 / 红线类）总预算：超出的条目退回摘要索引，不与索引抢预算。 */
    private const val MAX_INLINE_CHARS = 3_000

    /** 单条常驻正文上限：超长截断并指明可用 memory read 取全文。 */
    private const val MAX_INLINE_ENTRY_CHARS = 900

    /** 最近改动窗口：窗口内动过的条目在索引里标出时间。 */
    private const val RECENT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    private const val GLOBAL_HEADER = "全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))："
    private const val PROJECT_HEADER = "项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))："
    private const val OMISSION_NOTICE = "部分记忆索引因 8000 字符总预算未注入；memory(action=list) 仍可查看完整清单，再用 memory(action=read, name=..., scope=...) 读取正文。"
    private const val SCALE_NOTICE = "当前项目记忆 %d 条，注入清单 %d 条；其余需用 memory search 取回"
    private const val INLINE_HEADER = "以下条目的正文已直接给出（长期约定与红线，不必再 memory read）："
    private const val TRUNCATED_SUFFIX = "\n…（正文过长已截断，完整内容用 memory(action=read, name=%s) 读取）"

    /**
     * 待确认（冲突未裁决）条目的索引标注。
     *
     * 这类条目是同一件事的两种说法并存、等用户拍板的中间态：既要出现在清单里（静默隐藏
     * 等于把分歧抹掉，用户再也看不到），又必须让模型看出它是有争议的内容——否则渲染成
     * 普通摘要行后，「未经确认的说法」会被当成既定事实直接照着做。
     */
    private const val PENDING_LABEL = "（待确认：与另一条说法冲突、尚未裁决，不得当作既定事实）"

    fun render(memories: List<Memory>): String {
        val inlined = mutableListOf<Memory>()
        val inlineText = buildString {
            for (memory in memories) {
                if (!shouldInline(memory)) continue
                val block = inlineBlock(memory)
                if (length + block.length > MAX_INLINE_CHARS) break
                if (isNotEmpty()) append("\n\n")
                append(block)
                inlined += memory
            }
        }
        val inlinedNames = inlined.mapTo(HashSet()) { it.name.lowercase() }
        val rest = memories.filterNot { it.name.lowercase() in inlinedNames }

        return listOf(
            if (inlineText.isEmpty()) "" else "$INLINE_HEADER\n\n$inlineText",
            renderIndex(rest)
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * 在已渲染的清单末尾附一行规模反馈：告诉模型清单是有硬上限的抽样。
     *
     * 与 [render] 分开、不并进 [render] 里，是因为 [render] 的输出长度被测试与
     * 8000 字符预算盯着，追加文字会越界；这行只在最终拼装 system prompt 时贴上去。
     * [totalInScope] 为当前作用域记忆总数，[injected] 为本次注入清单条数。
     */
    fun withScaleNotice(rendered: String, injected: Int, totalInScope: Int): String {
        if (rendered.isEmpty()) return rendered
        return "$rendered\n\n${SCALE_NOTICE.format(totalInScope, injected)}"
    }

    fun expandOnce(text: String, variable: String, content: String?): String {
        val first = text.indexOf(variable)
        if (first < 0) return text
        return text.substring(0, first) + content.orEmpty() +
            text.substring(first + variable.length).replace(variable, "")
    }

    /**
     * 是否内联正文。只认「长期约定」类（`type: identity`）且不是核心档案——
     * 核心档案（CORE / GLOBAL / OPS 等）的正文已由 AharouMemoryStore 那条通路注入，
     * 这里再给一次就是重复占用上下文。
     *
     * 待确认（pending）的条目一律不内联，identity 类也不例外：内联段是「已确认的既有事实」，
     * 把未裁决的冲突说法放进去，等于直接替用户拍板。它们退到索引里并带 [PENDING_LABEL]。
     */
    private fun shouldInline(memory: Memory): Boolean =
        memory.type == MemoryType.IDENTITY && !isCoreArchiveName(memory.name) && !memory.pending

    private fun inlineBlock(memory: Memory): String {
        val body = memory.content.trim()
        val shown = if (body.length > MAX_INLINE_ENTRY_CHARS) {
            body.take(MAX_INLINE_ENTRY_CHARS) + TRUNCATED_SUFFIX.format(memory.name)
        } else {
            body
        }
        return buildString {
            append("### ").append(memory.name)
            if (memory.description.isNotBlank()) append(" —— ").append(memory.description)
            if (shown.isNotEmpty()) append('\n').append(shown)
        }
    }

    private fun renderIndex(memories: List<Memory>): String {
        if (memories.isEmpty()) return ""
        val full = renderSelected(memories)
        if (full.length <= MAX_INDEX_CHARS) return full

        val selected = mutableSetOf<Int>()
        val candidates = memories.indices.sortedBy { if (isCore(memories[it])) 0 else 1 }
        val indexBudget = MAX_INDEX_CHARS - OMISSION_NOTICE.length - 2
        for (index in candidates) {
            selected.add(index)
            val content = renderSelected(memories.filterIndexed { i, _ -> i in selected })
            if (content.length > indexBudget) selected.remove(index)
        }
        val content = renderSelected(memories.filterIndexed { i, _ -> i in selected })
        return if (content.isEmpty()) OMISSION_NOTICE else "$content\n\n$OMISSION_NOTICE"
    }

    private fun renderSelected(memories: List<Memory>): String = buildString {
        for (scope in MemoryScope.entries) {
            val scoped = memories.filter { it.scope == scope }
            if (scoped.isEmpty()) continue
            if (isNotEmpty()) append("\n\n")
            append(if (scope == MemoryScope.GLOBAL) GLOBAL_HEADER else PROJECT_HEADER)
            scoped.forEach {
                append("\n- ${it.name}: ${it.description.ifBlank { "无" }}${pendingLabel(it)}${recencyLabel(it)}")
            }
        }
    }

    /** 待确认条目的索引标注；正文不在这里给，要看内容得用 memory(action=read, ...)。 */
    private fun pendingLabel(memory: Memory): String = if (memory.pending) PENDING_LABEL else ""

    /** 最近改动过的条目在索引里带时间标注：让「最近被纠正过什么」一眼可见，不必翻文件时间。 */
    private fun recencyLabel(memory: Memory): String {
        val modified = memory.file?.lastModified() ?: return ""
        if (modified <= 0L) return ""
        val age = System.currentTimeMillis() - modified
        if (age < 0L || age > RECENT_WINDOW_MILLIS) return ""
        val days = age / (24L * 60L * 60L * 1_000L)
        return when (days) {
            0L -> "（今天更新）"
            1L -> "（昨天更新）"
            else -> "（${days} 天前更新）"
        }
    }

    private fun isCore(memory: Memory): Boolean {
        if (memory.type == MemoryType.IDENTITY) return true
        val base = memory.name.substringBeforeLast('.')
        return base.isNotEmpty() && base.all { it.isUpperCase() || it.isDigit() || it == '_' }
    }
}
