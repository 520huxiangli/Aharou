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

/**
 * 工具详情面板里长输出的安全渲染：超过 [OUTPUT_COLLAPSE_THRESHOLD_CHARS] 的文本默认只渲染一段
 * 预览 + 折叠徽标，点「展开」后才按 [splitLongContent] 切成的块分批揭示。
 *
 * 起因（2026-10-13 ANR 实测）：面板此前把工具结果全文直接交给 `Text`，一条 81 万字符的结果
 * 在小屏幕点开面板的那一帧整段进 StaticLayout，主线程在 minikin 换行里跑满 5 秒被系统杀掉，
 * 现象就是「点小屏幕就闪退」。上游 OpenMinis 对同一场景的做法是「折叠 + 显式展开」
 * （LargeContentGuard）+ 工具详情页按 40 行分块懒揭示（LazyRevealToolText），这里按同一思路做，
 * 复用本仓库已有的 [splitLongContent] 与 [breakableText]。
 *
 * 展开态每次只多揭示一批，避免单次点击就把全文交给排版；已揭示部分随点击累积，与上游一致
 * （渲染成本由用户显式操作决定）。
 */
internal const val OUTPUT_COLLAPSE_THRESHOLD_CHARS = 32_000
private const val OUTPUT_COLLAPSE_PREVIEW_CHARS = 8_000
private const val OUTPUT_REVEAL_BATCH_CHUNKS = 8

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
        Text(
            text = breakableText(text.ifBlank { stringResource(R.string.computer_output_empty) }),
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
            modifier = modifier.fillMaxWidth(),
        )
        return
    }
    var expanded by remember(text) { mutableStateOf(false) }
    if (expanded) {
        RevealedOutputText(text, color, fontFamily, fontSize, lineHeight, modifier)
    } else {
        CollapsedOutputBadge(text, fontFamily, onExpand = { expanded = true }, modifier = modifier)
    }
}

/** 折叠态：预览前 [OUTPUT_COLLAPSE_PREVIEW_CHARS] 个字符 + 大小提示 + 展开入口。 */
@Composable
private fun CollapsedOutputBadge(
    text: String,
    fontFamily: FontFamily,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                0.5.dp,
                MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                RoundedCornerShape(12.dp),
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = breakableText(text.take(OUTPUT_COLLAPSE_PREVIEW_CHARS) + "…"),
            fontFamily = fontFamily,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.computer_output_collapsed, formatOutputSize(text.length)),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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

/** 展开态：分块渲染，点一次多揭示一批，始终不让全文一次性进排版。 */
@Composable
private fun RevealedOutputText(
    text: String,
    color: Color,
    fontFamily: FontFamily,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    modifier: Modifier = Modifier,
) {
    val chunks = remember(text) { splitLongContent(text) }
    val total = chunks.size
    var revealed by remember(text) { mutableStateOf(OUTPUT_REVEAL_BATCH_CHUNKS.coerceAtMost(total)) }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = breakableText(chunks.take(revealed).joinToString("\n")),
            fontFamily = fontFamily,
            fontSize = fontSize,
            lineHeight = lineHeight,
            color = color,
        )
        if (revealed < total) {
            Spacer(Modifier.height(8.dp))
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

/** 字符数折算成人读的大小提示；按 1 字符 ≈ 1 字节估算，够用即可。 */
private fun formatOutputSize(chars: Int): String = when {
    chars >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", chars / 1_000_000.0)
    chars >= 1_000 -> "${chars / 1_000} KB"
    else -> "$chars B"
}
