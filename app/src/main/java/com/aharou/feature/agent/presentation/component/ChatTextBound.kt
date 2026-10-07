package com.aharou.feature.agent.presentation.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * 聊天文本的「排版有界」处理：长正文拆块，以及给「无断点长串」补断点。
 *
 * 单独成文件是因为 [AIChatPanel] / [ToolMessageComponents] 都受单文件行数棘轮约束，只能缩不能涨。
 */

/** 超过该长度（字符）的助手正文拆成多条有界 chunk。 */
internal const val CHUNK_SPLIT_THRESHOLD_CHARS = 2_000

/** 每条 chunk 的目标字符预算：正文按 markdown 块打包，单块超出预算（如巨大表格）时
 *  按行硬切兜底，保证任意 chunk 高度有界（≈0.5-0.7 屏）。 */
private const val CHUNK_BUDGET_CHARS = 1_200

/**
 * 长正文拆块：以行为单位识别三类 markdown 块——代码围栏（整段）、表格（连续 | 行，
 * 整表保持完整）、普通段落（以空行分隔），然后按字符预算贪心打包成 chunk。
 * 超预算的单块按行拆分为多个块，宁可打断表格也不让某条 item 无界长高。
 */
internal fun splitLongContent(text: String): List<String> {
    val lines = text.lines()
    val rawBlocks = ArrayList<String>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                val fence = if (trimmed.startsWith("```")) "```" else "~~~"
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && !lines[j].trimStart().startsWith(fence)) {
                    sb.append('\n').append(lines[j]); j++
                }
                if (j < lines.size) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            line.isBlank() -> i++
            trimmed.startsWith("|") -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && lines[j].trimStart().startsWith("|")) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
            else -> {
                val sb = StringBuilder(line)
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() &&
                    !lines[j].trimStart().startsWith("```") &&
                    !lines[j].trimStart().startsWith("~~~") &&
                    !lines[j].trimStart().startsWith("|")
                ) {
                    sb.append('\n').append(lines[j]); j++
                }
                rawBlocks.add(sb.toString()); i = j
            }
        }
    }
    if (rawBlocks.isEmpty()) return listOf(text)

    // 超预算单块（如巨型表格/巨型段落）按行切成预算内的小块，兜底保证有界。
    // 代码块（``` / ~~~）作为不可分割的语法单元必须保持完整，绝不能按行拆碎，
    // 否则围栏闭合被破坏，后续片段会退化为普通正文、丢失高亮与复制，HTML 更会被预处理器误清洗。
    val blocks = ArrayList<String>()
    for (block in rawBlocks) {
        val isCodeBlock = block.trimStart().let { it.startsWith("```") || it.startsWith("~~~") }
        if (block.length <= CHUNK_BUDGET_CHARS || isCodeBlock) {
            blocks.add(block)
        } else {
            val piece = StringBuilder()
            var weight = 0
            for (ln in block.lines()) {
                // 单行自身超预算（工具输出式的压缩 JSON / 无空格长 token）：按行切不动它，必须按字符硬切，
                // 否则整行会作为一个无界 item 交给文本排版，几万字符的单行足以拖死主线程。
                if (ln.length > CHUNK_BUDGET_CHARS) {
                    if (piece.isNotBlank()) { blocks.add(piece.toString()); piece.setLength(0) }
                    weight = 0
                    var start = 0
                    while (start < ln.length) {
                        val end = minOf(start + CHUNK_BUDGET_CHARS, ln.length)
                        blocks.add(ln.substring(start, end))
                        start = end
                    }
                    continue
                }
                if (weight > 0 && weight + 1 + ln.length > CHUNK_BUDGET_CHARS) {
                    blocks.add(piece.toString()); piece.setLength(0); weight = 0
                }
                piece.append(ln).append('\n'); weight += ln.length + 1
            }
            if (piece.isNotBlank()) blocks.add(piece.toString())
        }
    }

    val chunks = ArrayList<String>()
    val cur = StringBuilder()
    var weight = 0
    for (block in blocks) {
        val w = block.length + 2
        if (weight > 0 && weight + w > CHUNK_BUDGET_CHARS) {
            chunks.add(cur.toString()); cur.setLength(0); weight = 0
        }
        // 块间必须留空行：丢空行会让 markdown 语义粘连（段落 + 紧跟 --- 会被解析成 setext 标题，段落被夸成标题字号）。
        if (weight > 0) cur.append('\n')
        cur.append(block).append('\n'); weight += w
    }
    if (cur.isNotBlank()) chunks.add(cur.toString())
    return chunks
}

/**
 * 给超长「无断点连续段」补零宽空格（U+200B）断点。
 *
 * 工具结果（Bash / Shizuku 的原始输出）常是压缩 JSON 或长路径：整条既没有换行也没有空格。
 * Android 的断行器（minikin）在这种文本上要一路扫到行尾才能确定断点，开销接近 O(n²)——
 * 一条两万字符的单行结果就足以让主线程在首帧里跑满 CPU 并 ANR。
 *
 * 只处理连续超过 [maxRun] 个字符、期间既无空白也无换行的片段（CJK 本身逐字可断，不参与）。
 * ZWSP 渲染为零宽，显示效果与排版结果不变；复制按钮读的是原始 content，也不受影响。
 * 幂等：已有的 ZWSP 会被当成断点，不会重复插入。
 */
internal fun withBreakOpportunities(text: String, maxRun: Int = 48): String {
    if (text.length <= maxRun) return text
    val sb = StringBuilder(text.length + text.length / maxRun + 16)
    var run = 0
    for (ch in text) {
        if (ch == '\u200B' || ch.isWhitespace() || ch.code > 0x2E80) {
            run = 0
            sb.append(ch)
        } else {
            if (run == maxRun) {
                sb.append('\u200B')
                run = 0
            }
            sb.append(ch)
            run++
        }
    }
    return sb.toString()
}

/**
 * [withBreakOpportunities] 的组合期包装：同一段文本在一次组合里只处理一次。
 *
 * 包装成 @Composable 是为了让调用点保留「表达式直接塞进 `text = `」的写法——
 * 调用方都在单文件行数棘轮里，多一行都过不了门禁。
 */
@Composable
internal fun breakableText(text: String): String = remember(text) { withBreakOpportunities(text) }
