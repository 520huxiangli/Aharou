package com.aharou.feature.voice.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic
import kotlin.math.PI
import kotlin.math.sin

/**
 * 按住说话时贴在输入框上方的条：状态字 + 波形 + 边说边出的识别文本。
 *
 * 只做展示：长按期间手指压在麦克风上，这里不放手势按钮，松手即结束，取消靠松手前的文字自行删改。
 */
@Composable
internal fun VoiceInputPanel(
    state: VoiceInputState,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state !is VoiceInputState.Idle,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.mdLarge),
            color = MaterialTheme.semanticColors.cardSurface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        FeatherIcons.Mic,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = statusText(state),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                VoiceWaveform(
                    level = (state as? VoiceInputState.Listening)?.level ?: 0f,
                    active = state is VoiceInputState.Listening,
                )
                val recognized = (state as? VoiceInputState.Listening)?.text.orEmpty()
                if (recognized.isNotBlank()) {
                    Text(
                        text = recognized,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(state: VoiceInputState): String = when (state) {
    VoiceInputState.Idle -> ""
    VoiceInputState.Preparing -> stringResource(R.string.voice_input_preparing)
    VoiceInputState.Finishing -> stringResource(R.string.voice_input_finishing)
    is VoiceInputState.Listening -> stringResource(R.string.voice_input_listening)
    is VoiceInputState.Failed -> stringResource(state.messageRes)
}

/**
 * 波形：横向条，高度随当前振幅起伏，相位随动让它在静音时也有呼吸感。
 * 振幅来自 PCM 峰值，不做频谱分析——这里只要「在收音」的视觉反馈，不需要真实频谱。
 */
@Composable
private fun VoiceWaveform(
    level: Float,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val animatedLevel by animateFloatAsState(level, tween(120), label = "voice-input-level")
    val transition = rememberInfiniteTransition(label = "voice-input-wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "voice-input-phase",
    )
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().height(24.dp)) {
        val barCount = 46
        val gap = 2.dp.toPx()
        val barWidth = ((size.width - gap * (barCount - 1)) / barCount).coerceAtLeast(1f)
        val amplitude = if (active) 0.18f + animatedLevel * 0.82f else 0.08f
        repeat(barCount) { index ->
            // sin 只有 Double 重载，算完再转回 Float，避免整条链路被提升成 Double。
            val wave = ((sin((phase + index * 0.45f).toDouble()) + 1.0) / 2.0).toFloat()
            val height = (size.height * amplitude * (0.35f + 0.65f * wave)).coerceIn(2.dp.toPx(), size.height)
            drawRoundRect(
                color = color.copy(alpha = 0.5f + 0.5f * wave),
                topLeft = Offset(index * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
        }
    }
}
