package com.aharou.feature.agent.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import java.util.Locale

/*
 * 工具输出/长文本的三段渲染护栏（阈值集中在本段，改数量级只改这里）。
 *
 * 为什么是 32k：长任务（跑测试、装依赖、抓整站、一次 grep 整个仓库）单条工具输出几十万字符
 * 是常态，而 32k 字符已经相当于十几屏 12sp 等宽文本，远超人工阅读量——超过它基本只剩
 * 「机器生成、人来抽查尾部」的用途，不值得为它付出整段排版的代价。阈值取在这个量级，
 * 正常输出完全走原来的单 Text 路径，只有超长输出才多出「折叠 + 显式展开」的交互成本。
 *
 * 三段分别是：
 *   1) ≤ 32k：原样交一个 Text（单行仍按 OUTPUT_INLINE_LINE_MAX_CHARS 封顶）；
 *   2) > 32k：只渲染**尾部**一段纯文本预览 + 折叠徽标，全文不进排版；
 *   3) 点「展开」后按块揭示，每块一个独立 Text，绝不把全文拼回一个字符串。
 *
 * 起因（2026-10-13 ANR 实测）：面板此前把工具结果全文直接交给 `Text`，一条 81 万字符的结果
 * 在小屏幕点开面板的那一帧整段进 StaticLayout，主线程在 minikin 换行里跑满 5 秒被系统杀掉，
 * 现象就是「点小屏幕就闪退」。上游 OpenMinis 对同一场景的做法是「折叠 + 显式展开」
 * （LargeContentGuard）+ 工具详情页分块懒揭示（LazyRevealToolText）+ 每行封顶
 * （ToolTextTail），这里按同一数量级实现，并复用本仓库已有的 [splitLongContent] 思路。
 */

/** 超过该长度的文本不再整段交给 Text。 */
internal const val OUTPUT_COLLAPSE_THRESHOLD_CHARS = 32_000

/**
 * 折叠预览只回看尾部这么多个字符。工具结果的有效信息（报错、汇总、退出码、失败用例）
 * 几乎总在末尾，而只扫固定窗口意味着预览的开销与全文多大完全无关。
 */
private const val OUTPUT_PREVIEW_SCAN_CHARS = 8_000

/** 折叠预览最多显示这么多行：再多也没有阅读意义，全文靠展开。 */
private const val OUTPUT_PREVIEW_LINES = 24

/** 预览里单行的字符上限：一行 200KB 的压缩 JSON 交给 Text，排版成本同样与全文等大。 */
private const val OUTPUT_PREVIEW_LINE_MAX_CHARS = 400

/**
 * 正常路径（≤ 阈值）里单行的字符上限。工具输出偶有无换行的压缩 JSON：只要有一行特别长，
 * Text 的测量成本就与那一行等长，与总长度无关，所以这档也要封顶。
 */
private const val OUTPUT_INLINE_LINE_MAX_CHARS = 4_000

/** 展开态每块的字符上限（含换行）。每块一个独立 Text，单块排版成本有界。 */
private const val OUTPUT_REVEAL_CHUNK_MAX_CHARS = 4_000

/** 展开态每块的行数上限，与上游 40 行/块一致。 */
private const val OUTPUT_REVEAL_CHUNK_LINES = 40

/** 展开后首批揭示的块数（≈ 8k 字符，一屏多）；之后每次点击再揭示一批，节奏由用户决定。 */
private const val OUTPUT_REVEAL_INITIAL_CHUNKS = 2
private const val OUTPUT_REVEAL_BATCH_CHUNKS = 2

@Composable
internal fun ExpandableOutputText(
    text: String,
    color: Color,
    fontFamily: FontFamily = FontFamily.Default,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 18.sp,
    modifier: Modifier = Modifier,
) {
    if (text.length <= OUTPUT_COLLAPSE_THRESHOLD_CHARS) {
        val display = text.ifBlank { stringResource(R.string.computer_output_empty) }
        val bounded = remember(display) { capLineLengths(display, OUTPUT_INLINE_LINE_MAX_CHARS) }
        Text(
            text = breakableText(bounded),
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    if (expanded) {
        RevealedOutputText(text, color, fontFamily, fontSize, lineHeight, modifier)
    } else {
        CollapsedOutputBadge(
            text = text,
            color = color,
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            onExpand = { expanded = true },
            modifier = modifier,
        )
    }
}

/** 折叠态：尾部预览 + 大小提示 + 展开入口。预览本身也封顶（行数 × 单行字符），成本恒定。 */
@Composable
private fun CollapsedOutputBadge(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = MaterialTheme.semanticColors
    val shape = RoundedCornerShape(Radius.mdLarge)
    val preview = remember(text) {
        tailPreview(
            text = text,
            maxLines = OUTPUT_PREVIEW_LINES,
            maxLineChars = OUTPUT_PREVIEW_LINE_MAX_CHARS,
            scanChars = OUTPUT_PREVIEW_SCAN_CHARS,
        )
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(semantic.mutedSurface)
            .border(0.5.dp, semantic.subtleBorder, shape)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text(
            text = breakableText(preview),
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color.copy(alpha = 0.85f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.computer_output_collapsed, formatOutputSize(text.length)),
                fontSize = 11.sp,
                color = semantic.subtleText,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.computer_output_expand),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { onExpand() }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * 展开态：按块揭示，每块独立 Text。块边界只算出下标区间（[boundedTextChunks]），
 * 揭示时才 substring，既不复制全文，也不会出现「点够次数后整段又进一次排版」。
 */
@Composable
private fun RevealedOutputText(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    val chunks = remember(text) {
        boundedTextChunks(
            text = text,
            maxCharsPerChunk = OUTPUT_REVEAL_CHUNK_MAX_CHARS,
            maxLinesPerChunk = OUTPUT_REVEAL_CHUNK_LINES,
        )
    }
    val total = chunks.size
    var revealed by remember(text) {
        mutableStateOf(OUTPUT_REVEAL_INITIAL_CHUNKS.coerceAtMost(total))
    }
    Column(modifier = modifier.fillMaxWidth()) {
        chunks.take(revealed).forEach { range ->
            Text(
                text = breakableText(text.substring(range.first, range.last + 1)),
                fontFamily = fontFamily,
                fontSize = fontSize,
                lineHeight = lineHeight,
                color = color,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (revealed < total) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(R.string.computer_output_load_more, total - revealed),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable { revealed = (revealed + OUTPUT_REVEAL_BATCH_CHUNKS).coerceAtMost(total) }
                    .padding(vertical = 4.dp),
            )
        }
    }
}

/**
 * 把 [text] 切成「每块最多 [maxLinesPerChunk] 行或 [maxCharsPerChunk] 字符」的下标区间
 * （左闭右开，区间非空）。只记录边界、不复制文本，所以对几十万字符的输入也是 O(n) 扫描、
 * 常数内存；超长单行（无换行可断）在这一层被硬切，并避开 UTF-16 代理对。
 */
internal fun boundedTextChunks(
    text: String,
    maxCharsPerChunk: Int,
    maxLinesPerChunk: Int,
): List<IntRange> {
    if (text.isEmpty()) return emptyList()
    require(maxCharsPerChunk > 0 && maxLinesPerChunk > 0)
    val ranges = ArrayList<IntRange>(text.length / maxCharsPerChunk + 1)
    var start = 0
    while (start < text.length) {
        val hardLimit = minOf(text.length, start + maxCharsPerChunk)
        var end = hardLimit
        var lines = 0
        var i = start
        while (i < hardLimit) {
            if (text[i] == '\n' && ++lines == maxLinesPerChunk) {
                end = i + 1
                break
            }
            i++
        }
        // 落在硬上限里且不在行尾：退回本块内最后一个换行，避免把一行劈成两半；
        // 整块都没有换行（压缩 JSON / 无空格长 token）时才按字符硬切。
        if (end < text.length && text[end - 1] != '\n') {
            var newline = end - 1
            while (newline > start && text[newline] != '\n') newline--
            end = if (newline > start) newline + 1 else surrogateSafeCut(text, end)
        }
        ranges.add(start until end)
        start = end
    }
    return ranges
}

/** 行首的代理对不能被切掉一半，否则后半块开头会渲染成乱码方块。 */
private fun surrogateSafeCut(text: String, at: Int): Int =
    if (at in 1 until text.length && text[at - 1].isHighSurrogate()) at + 1 else at

/**
 * 尾部预览：只读最后 [scanChars] 个字符，取最后 [maxLines] 行，每行超过 [maxLineChars] 时
 * 保留行尾并补前置省略号（报错信息在行尾）。被窗口截断的首行同样补省略号。
 */
private fun tailPreview(text: String, maxLines: Int, maxLineChars: Int, scanChars: Int): String {
    var start = (text.length - scanChars).coerceAtLeast(0)
    if (start in 1 until text.length && text[start - 1].isHighSurrogate()) start++
    val windowStart = start > 0
    val lines = text.substring(start).split('\n')
    val firstIndex = (lines.size - maxLines).coerceAtLeast(0)
    val sb = StringBuilder()
    for (index in firstIndex until lines.size) {
        if (sb.isNotEmpty()) sb.append('\n')
        val raw = lines[index].trimEnd('\r')
        val cutAt = raw.length - maxLineChars
        if (cutAt > 0) {
            var from = cutAt
            if (raw[from - 1].isHighSurrogate()) from++
            sb.append('…').append(raw, from, raw.length)
        } else {
            if (index == firstIndex && windowStart) sb.append('…')
            sb.append(raw)
        }
    }
    return sb.toString()
}

/**
 * 每行超过 [maxChars] 的部分截断并补行尾省略号；没有任何行超限时原样返回（不复制字符串）。
 * 用于正常路径与占位预览，防止「一条几万字符的单行」把 Text 的测量拖成 O(n) 主线程长任务。
 */
internal fun capLineLengths(text: String, maxChars: Int): String {
    if (text.length <= maxChars) return text
    var lineStart = 0
    var needsCut = false
    var i = 0
    while (i <= text.length) {
        if (i == text.length || text[i] == '\n') {
            if (i - lineStart > maxChars) {
                needsCut = true
                break
            }
            lineStart = i + 1
        }
        i++
    }
    if (!needsCut) return text
    val sb = StringBuilder(text.length)
    lineStart = 0
    i = 0
    while (i <= text.length) {
        if (i == text.length || text[i] == '\n') {
            val over = i - lineStart > maxChars
            sb.append(text, lineStart, if (over) lineStart + maxChars else i)
            if (over) sb.append('…')
            if (i < text.length) sb.append('\n')
            lineStart = i + 1
        }
        i++
    }
    return sb.toString()
}

/** 字符数折算成人读的大小提示；按 1 字符 ≈ 1 字节估算，够用即可。 */
internal fun formatOutputSize(chars: Int): String = when {
    chars >= 1_000_000 -> String.format(Locale.US, "%.1f MB", chars / 1_000_000.0)
    chars >= 1_000 -> "${chars / 1_000} KB"
    else -> "$chars B"
}
