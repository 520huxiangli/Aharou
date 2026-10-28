package com.aharou.feature.agent.domain.prompt

import com.aharou.core.util.SecretMasker
import com.aharou.core.util.TextSanitizer
import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import com.aharou.feature.agent.domain.memory.isCoreArchiveName
import com.aharou.feature.agent.domain.memory.isProtectedIdentityName

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
    private const val OMISSION_NOTICE = "部分记忆索引因 $MAX_INDEX_CHARS 字符总预算未注入；memory(action=list) 仍可查看完整清单，再用 memory(action=read, name=..., scope=...) 读取正文。"
    private const val SCALE_NOTICE = "记忆库共 %d 条（含仅归档的事件、已过期与陈旧条目），本轮注入清单 %d 条；其余需用 memory search 取回"
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
        val plan = planInline(memories)
        val inlineText = plan.blocks.joinToString("\n\n")
        val inlinedNames = plan.inlined.mapTo(HashSet()) { it.name.lowercase() }
        val rest = memories.filterNot { it.name.lowercase() in inlinedNames }

        return listOf(
            if (inlineText.isEmpty()) "" else "$INLINE_HEADER\n\n$inlineText",
            renderIndex(rest, longBodies)
        ).filter { it.isNotEmpty() }.joinToString("\n\n")
    }

    /**
     * 内联与索引的唯一切分实现：[render] 与 [inlineBudgetReport] 都走这里，两处口径因此不可能漂移。
     *
     * 逐条尝试：单条超长整条跳过而非截断（半截正文模型用不上，还会挤占常驻预算；索引行带上
     * [LONG_BODY_LABEL]，模型按需 read 即可拿到全文）；累计撑不下也跳过这条继续试后面的——
     * 直接 break 会把排在它后面、本来塞得下的短条目一起退回索引，而顺序由相关度决定，
     * 模型看到的内联条目会无缘无故变来变去。
     */
    private fun planInline(memories: List<Memory>): InlinePlan {
        val blocks = mutableListOf<String>()
        val inlined = mutableListOf<Memory>()
        val squeezed = mutableListOf<SqueezedInline>()
        // 保护名单（行为红线）先占位：它们是安全网，一旦被知识类条目挤出去就等于没定。
        // 不改总量预算，只调整「谁先塞」的顺序。
        val ordered = memories.sortedByDescending { isProtectedIdentityName(it.name) }
        // 作用域上限：全局与项目共用 3000，一方写太多会把另一方挤到一条不剩。
        // 单个作用域最多占 2/3，保证另一方至少还有 1/3 可用（全占满也行，但不靠运气）。
        val scopeCap = MAX_INLINE_CHARS * 2 / 3
        var globalChars = 0
        var projectChars = 0
        // 累计长度按拼接后的实际字符数算（含块间空行），与原先 buildString 的 length 同一口径。
        var length = 0
        for (memory in ordered) {
            if (!shouldInline(memory)) continue
            val body = inlineBody(memory)
            if (body.length > MAX_INLINE_ENTRY_CHARS) {
                squeezed += SqueezedInline(memory, InlineSkipReason.TOO_LONG, body.length)
                continue
            }
            val block = inlineBlock(memory, body)
            if (length + block.length > MAX_INLINE_CHARS ||
                (if (memory.scope == MemoryScope.GLOBAL) globalChars else projectChars) + block.length > scopeCap
            ) {
                squeezed += SqueezedInline(memory, InlineSkipReason.BUDGET_FULL, body.length)
                continue
            }
            if (memory.scope == MemoryScope.GLOBAL) globalChars += block.length else projectChars += block.length
            if (blocks.isNotEmpty()) length += 2
            length += block.length
            blocks += block
            inlined += memory
        }
        return InlinePlan(blocks, inlined, squeezed)
    }

    /**
     * 盘点常驻正文预算：哪些 identity 条目真的进了常驻段，哪些被预算挤回索引。
     *
     * 只读用途（memory action=stats 展示），不参与注入渲染；判定与两个上限全部复用
     * [planInline] 与 [MAX_INLINE_CHARS] / [MAX_INLINE_ENTRY_CHARS]，不在这里另立一套数字。
     * 传入的清单应当与注入侧同源（`listMemoriesForPrompt`），否则「常驻几条」对不上。
     */
    fun inlineBudgetReport(memories: List<Memory>): InlineBudgetReport {
        val plan = planInline(memories)
        return InlineBudgetReport(
            identityTotal = memories.count { it.type == MemoryType.IDENTITY },
            inlined = plan.inlined.map { it.name },
            squeezed = plan.squeezed.map { entry ->
                entry.memory.name to when (entry.reason) {
                    InlineSkipReason.TOO_LONG ->
                        "单条超限（正文 ${entry.bodyChars} 字符 > 单条上限 $MAX_INLINE_ENTRY_CHARS）"
                    InlineSkipReason.BUDGET_FULL ->
                        "总量超限（常驻总预算 $MAX_INLINE_CHARS 字符已被排在前面的条目占完）"
                }
            },
            notBudgeted = memories
                .filter { it.type == MemoryType.IDENTITY && !shouldInline(it) }
                .map { memory ->
                    memory.name to if (memory.pending) {
                        "待确认，按规则不内联（非预算原因）"
                    } else {
                        "核心档案，正文另走 AharouMemoryStore 通路"
                    }
                },
            singleEntryLimit = MAX_INLINE_ENTRY_CHARS,
            totalLimit = MAX_INLINE_CHARS,
        )
    }

    /** 常驻正文排布的结论：[blocks] 与 [inlined] 一一对应，[squeezed] 是被预算挡在外面的条目。 */
    private data class InlinePlan(
        val blocks: List<String>,
        val inlined: List<Memory>,
        val squeezed: List<SqueezedInline>,
    )

    private class SqueezedInline(
        val memory: Memory,
        val reason: InlineSkipReason,
        val bodyChars: Int,
    )

    private enum class InlineSkipReason { TOO_LONG, BUDGET_FULL }

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
        // name 来自 frontmatter 自由文本（容器内可写），与正文同属不可信输入，口径不能不一样。
        append("### ").append(SecretMasker.mask(TextSanitizer.sanitize(memory.name)))
        if (memory.description.isNotBlank()) {
            append(" —— ").append(SecretMasker.mask(TextSanitizer.sanitize(memory.description)))
        }
        if (body.isNotEmpty()) append('\n').append(body)
    }

    /**
     * 索引行显示的摘要：description 为空时用正文首行兜底。
     *
     * 写入时漏一个字段就让整条记忆永久隐形，代价太大；首行截断已经足够让人判断要不要 read。
     */
    private fun indexDescription(memory: Memory): String {
        if (memory.description.isNotBlank()) return memory.description
        val line = memory.content.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return "无"
        return if (line.length <= 60) line else line.take(60) + "…"
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
                append("\n- ${SecretMasker.mask(TextSanitizer.sanitize(it.name))}: ${SecretMasker.mask(TextSanitizer.sanitize(indexDescription(it)))}${pendingLabel(it)}${longBodyLabel(it, longBodies)}${recencyLabel(it)}")
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

/**
 * 常驻正文预算的盘点结果（只读，供 memory action=stats 展示）。
 *
 * @param identityTotal 清单里 identity 类条目的总数。
 * @param inlined 真正进了常驻段的条目名。
 * @param squeezed 被预算挤回索引的条目：名称 → 已含具体数字的原因。
 * @param notBudgeted identity 类但按规则不走常驻预算的条目（待确认 / 核心档案）：名称 → 原因。
 * @param singleEntryLimit 单条常驻正文上限。
 * @param totalLimit 常驻正文总预算。
 */
internal data class InlineBudgetReport(
    val identityTotal: Int,
    val inlined: List<String>,
    val squeezed: List<Pair<String, String>>,
    val notBudgeted: List<Pair<String, String>>,
    val singleEntryLimit: Int,
    val totalLimit: Int,
)
