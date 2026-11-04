package com.aharou.feature.agent.domain.prompt

/**
 * 项目规则注入的字符预算与截断策略。
 *
 * 原来全局与工作区两层各封 32k，合计能到 64k，一份超长 `AGENTS.md` 就能把系统提示挤爆；
 * 截断又是 `take(n)` 硬切字符，常把围栏代码块和 Markdown 表格切一半，模型拿到的是坏掉的规则。
 * 改成「两层合计封顶 + 按行截断」后抽到这里：`SystemPromptProvider` 有 1000 行门禁，塞进去会超。
 */
internal object RuleBudget {

    /** 全局 + 工作区两层的合计上限（字符）。 */
    const val TOTAL_CHARS = 48_000

    /**
     * 单层预留份额：全局再长也吃不掉工作区那一份。
     * 工作区规则更具体（数据红线、禁改路径通常写在这里），不能被一份超长全局规则挤成空段。
     */
    const val LAYER_CHARS = TOTAL_CHARS / 2

    /** 单文件读取硬上限：远高于合计预算，保证可见的截断始终走按行逻辑，而不是读的时候就切断。 */
    const val FILE_READ_CHARS = TOTAL_CHARS * 2

    /**
     * 按行累计到 [limit] 字符，超限时补一句截断说明。
     *
     * 不用 `take(limit)`：那会切在任意字符上，把代码块、Markdown 表格、多字节字符切一半。
     * 但单行就超预算（压缩成一行的 AGENTS.md、一整个超长段落）时按行累计一个字都留不下，
     * 整份规则会退化成只剩一句说明，那比按字符截断更糟——此时退回 `take(limit)`。
     */
    fun truncateByLines(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val sb = StringBuilder(limit.coerceAtLeast(0) + 64)
        var fenceOpen = false
        for (line in text.lineSequence()) {
            if (sb.length + line.length + 1 > limit) break
            if (line.trimStart().startsWith("```")) fenceOpen = !fenceOpen
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        val kept = sb.length
        if (kept == 0) return text.take(limit)
        // 截断落在围栏代码块内时补一个收尾围栏：否则后面注入的段落会被模型当成代码前文
        if (fenceOpen) sb.append("\n```")
        sb.append("\n…（规则过长，已截断，仅保留前 ").append(kept).append(" 字符；完整内容请用文件工具读取）")
        return sb.toString()
    }
}
