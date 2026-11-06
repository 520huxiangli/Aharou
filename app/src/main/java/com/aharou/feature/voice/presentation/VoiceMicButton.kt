package com.aharou.feature.voice.presentation

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 麦克风按钮：轻点开关语音通话，长按按住说话（识别成文字进输入框）。
 *
 * 手势没用 `detectTapGestures` 的 onLongPress：它在长按触发后会 cancel 掉 press 作用域，
 * 拿不到「松手」这一步，而按住说话恰恰要等手指离开才能收尾。这里直接等 down，
 * 用长按超时把两种手势分开，再各自等到抬手。
 */
@Composable
internal fun VoiceMicButton(
    enabled: Boolean,
    callRunning: Boolean,
    onToggleCall: () -> Unit,
    modifier: Modifier = Modifier,
    wakeRunning: Boolean = false,
    voiceInputActive: Boolean = false,
    onVoiceInputStart: () -> Unit = {},
    onVoiceInputEnd: () -> Unit = {},
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // null 表示长按超时；true 是抬手轻点；false 是手势被系统取消。
                    val tap = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        down.consume()
                        waitForUpOrCancellation() != null
                    }
                    when (tap) {
                        null -> {
                            down.consume()
                            onVoiceInputStart()
                            // 手指滑出按钮时同样收尾：已经说出来的字不该因为滑了一下就丢掉。
                            waitForUpOrCancellation()
                            onVoiceInputEnd()
                        }
                        true -> onToggleCall()
                        false -> Unit
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FeatherIcons.Mic,
            contentDescription = stringResource(
                when {
                    voiceInputActive -> R.string.voice_input_active_hint
                    wakeRunning -> R.string.voice_wake_mic_hint
                    callRunning -> R.string.voice_call_toggle_stop
                    else -> R.string.voice_call_toggle_start
                }
            ),
            tint = when {
                voiceInputActive || wakeRunning || callRunning -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
