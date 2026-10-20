package com.aharou.feature.agent.domain.prompt

import com.aharou.core.util.SecretMasker
import com.aharou.core.util.TextSanitizer
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.memory.isCoreArchiveName

internal object MemoryPromptRenderer {
    const val MAX_INDEX_CHARS = 8_000

    /** 常驻正文（长期约定 / 红线类）总预算：超出的条目退回摘要索引，不与索引抢预算。 */
    private const val MAX_INLINE_CHARS = 3_000

    /** 单条常驻正文上限：超出的条目整条退回索引，索引行指明可用 memory read 取全文。 */
    private const val MAX_INLINE_ENTRY_CHARS = 900

    /** 最近改动窗口：窗口内动过的条目在索引里标出时间。 */
    private const val RECENT_WINDOW_MILLIS = 7L * 24L * 60L * 60L * 1_000L

    private const val GLOBAL_HEADER = "全局记忆 (跨项目个人偏好，需要详情时用 memory(action=read, name=xxx, scope=global))："
    private const val PROJECT_HEADER = "项目记忆 (当前项目专属，需要详情时用 memory(action=read, name=xxx, scope=project))："
    private const val OMISSION_NOTICE = "部分记忆索引因 8000 字符总预算未注入；memory(action=list) 仍可查看完整清单，再用 memory(action=read, name=..., scope=...) 读取正文。"
    private const val SCALE_NOTICE = "当前项目记忆 %d 条，注入清单 %d 条；其余需用 memory search 取回"
    private const val INLINE_HEADER = "以下条目的正文已直接给出（长期约定与红线，不必再 memory read）："

    /**
     * 正文超出单条上限、整条没内联的条目在索引里的标注。
     *
     * 让模型看出「不是没有正文，而是正文太长没塞进常驻段」：否则它会把摘要当全文用，
     * 而截断出的半截正文既读不懂又白占预算，还不如指出全文可取。
     */
    private const val LONG_BODY_LABEL = "（正文较长未内联，用 memory(action=read) 取全文）"

    /**
     * 待确认（冲突未裁决）条目的索引标注。
     *
     * 这类条目是同一件事的两种说法并存、等用户拍板的中间态：既要出现在清单里（静默隐藏
     * 等于把分歧抹掉，用户再也看不到），又必须让模型看出它是有争议的内容——否则渲染成
     * 普通摘要行后，「未经确认的说法」会被当成既定事实直接照着做。
     */
    private const val PENDING_LABEL = "（待确认：与另一条说法冲突、尚未裁决，不得当作既定事实）"

    fun render(memories: List<Memory>): String {
        // 超长条目的名字集合：只用来给索引行加「正文较长未内联」提示。
        val longBodies = memories.filter(::shouldInline)
            .filter { inlineBody(it).length > MAX_INLINE_ENTRY_CHARS }
            .mapTo(HashSet()) { it.name.lowercase() }
        val inlined = mutableListOf<Memory>()
        val inlineText = buildString {
            for (memory in memories) {
                if (!shouldInline(memory)) continue
                val body = inlineBody(memory)
                // 超长整条跳过而非截断：半截正文模型用不上，还会挤占常驻预算；
                // 索引行带上 LONG_BODY_LABEL，模型按需 read 即可拿到全文。
                if (body.length > MAX_INLINE_ENTRY_CHARS) continue
                val block = inlineBlock(memory, body)
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
            renderIndex(rest, longBodies)
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

    /** 注入前先消毒再掩码：消毒去不可见字符与伪协议标签，掩码把凭据换成可读形态。 */
    private fun inlineBody(memory: Memory): String =
        SecretMasker.mask(TextSanitizer.sanitize(memory.content.trim()))

    private fun inlineBlock(memory: Memory, body: String): String = buildString {
        append("### ").append(memory.name)
        if (memory.description.isNotBlank()) {
            append(" —— ").append(SecretMasker.mask(TextSanitizer.sanitize(memory.description)))
        }
        if (body.isNotEmpty()) append('\n').append(body)
    }

    private fun renderIndex(memories: List<Memory>, longBodies: Set<String>): String {
        if (memories.isEmpty()) return ""
        val full = renderSelected(memories, longBodies)
        if (full.length <= MAX_INDEX_CHARS) return full

        val selected = mutableSetOf<Int>()
        val candidates = memories.indices.sortedBy { if (isCore(memories[it])) 0 else 1 }
        val indexBudget = MAX_INDEX_CHARS - OMISSION_NOTICE.length - 2
        for (index in candidates) {
            selected.add(index)
            val content = renderSelected(memories.filterIndexed { i, _ -> i in selected }, longBodies)
            if (content.length > indexBudget) selected.remove(index)
        }
        val content = renderSelected(memories.filterIndexed { i, _ -> i in selected }, longBodies)
        return if (content.isEmpty()) OMISSION_NOTICE else "$content\n\n$OMISSION_NOTICE"
    }

    private fun renderSelected(memories: List<Memory>, longBodies: Set<String>): String = buildString {
        for (scope in MemoryScope.entries) {
            val scoped = memories.filter { it.scope == scope }
            if (scoped.isEmpty()) continue
            if (isNotEmpty()) append("\n\n")
            append(if (scope == MemoryScope.GLOBAL) GLOBAL_HEADER else PROJECT_HEADER)
            scoped.forEach {
                append("\n- ${it.name}: ${SecretMasker.mask(TextSanitizer.sanitize(it.description.ifBlank { "无" }))}${pendingLabel(it)}${longBodyLabel(it, longBodies)}${recencyLabel(it)}")
            }
        }
    }

    /** 待确认条目的索引标注；正文不在这里给，要看内容得用 memory(action=read, ...)。 */
    private fun pendingLabel(memory: Memory): String = if (memory.pending) PENDING_LABEL else ""

    /** 正文长度按掩码后的文本判断，与 [render] 内联时的口径一致，避免两处长度看法不同。 */
    private fun longBodyLabel(memory: Memory, longBodies: Set<String>): String =
        if (memory.name.lowercase() in longBodies) LONG_BODY_LABEL else ""

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
