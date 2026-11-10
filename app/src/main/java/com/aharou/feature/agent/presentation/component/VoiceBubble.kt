package com.aharou.feature.agent.presentation.component

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Pause
import compose.icons.feathericons.Play
import java.util.Random
import kotlinx.coroutines.delay

/** 波形的竖条数量：再多在窄屏上就糊成一片了。 */
private const val WAVE_BAR_COUNT = 26

/** 中文正常语速大约每秒 5 个字；只用来给语音条一个时长观感与进度节奏。 */
private const val CHARS_PER_SECOND = 5

/** 文本估出的语音时长（秒）。拿不到真实音频时长时的兜底。 */
internal fun estimateVoiceSeconds(text: String): Int =
    (text.count { !it.isWhitespace() } / CHARS_PER_SECOND).coerceIn(1, 600)

/**
 * 语音条：播放键 + 波形 + 时长 + 展开箭头。
 *
 * 展开区由调用方通过 [content] 传入（就是这条消息原本的正文渲染），
 * 所以展开只是文字区多出来一块，语音条本身始终保留。
 */
@Composable
internal fun VoiceBubble(
    text: String,
    isUser: Boolean,
    isPlaying: Boolean,
    expanded: Boolean,
    onTogglePlay: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val seconds = remember(text) { estimateVoiceSeconds(text) }
    var progress by remember(text) { mutableStateOf(0f) }
    // 播放进度按估算时长线性推进：真实音频的进度拿不到（系统 TTS 不暴露位置），
    // 这里只要求波形着色跟着走，不追求与音频毫秒级对齐。
    LaunchedEffect(isPlaying, text, seconds) {
        if (!isPlaying) {
            progress = 0f
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        val totalMs = seconds * 1000L
        while (true) {
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            progress = (elapsed.toFloat() / totalMs).coerceIn(0f, 1f)
            if (progress >= 1f) break
            delay(80)
        }
    }
    val container = if (isUser) chatUserBubbleColor() else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (isUser) chatUserBubbleTextColor() else MaterialTheme.colorScheme.onSurfaceVariant

    Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(Radius.pill))
                .background(container)
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(contentColor.copy(alpha = 0.12f))
                    .clickable(onClick = onTogglePlay)
                    .padding(Spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isPlaying) FeatherIcons.Pause else FeatherIcons.Play,
                    contentDescription = stringResource(
                        if (isPlaying) R.string.voice_bubble_pause else R.string.voice_bubble_play
                    ),
                    tint = contentColor,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Waveform(seed = text.hashCode(), progress = progress, color = contentColor)
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.voice_bubble_duration, seconds),
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
            )
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                contentDescription = stringResource(
                    if (expanded) R.string.voice_bubble_collapse else R.string.voice_bubble_expand
                ),
                tint = contentColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onToggleExpand)
                    .padding(2.dp)
                    .size(16.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(Spacing.xs))
            content()
        }
    }
}

/** 固定长度的伪波形：按消息文本生成，同一条消息每次重组的形状都一样。 */
@Composable
private fun Waveform(seed: Int, progress: Float, color: Color) {
    val bars = remember(seed) {
        val random = Random(seed.toLong())
        FloatArray(WAVE_BAR_COUNT) { 0.35f + random.nextFloat() * 0.65f }
    }
    val playedBars = progress * WAVE_BAR_COUNT
    Canvas(modifier = Modifier.width(110.dp).height(20.dp)) {
        val gap = 2.5.dp.toPx()
        val barWidth = (size.width - gap * (WAVE_BAR_COUNT - 1)) / WAVE_BAR_COUNT
        bars.forEachIndexed { index, ratio ->
            val barHeight = size.height * ratio
            drawRoundRect(
                color = if (index < playedBars) color else color.copy(alpha = 0.28f),
                topLeft = Offset(index * (barWidth + gap), (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
