package com.aharou.feature.voice.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 麦克风按钮：两种手势。
 *
 *  - **轻点**：开关语音通话（常驻，再点才关）。
 *  - **按住**：原来的语音输入，松手把识别结果填进输入框。
 *
 * 区分方法是按下时长：先起来的那次录音如果判定为轻点，会被 [onCancel] 作废，
 * 不会在输入框里留下半句脏数据；按住则从按下那刻就开始收音，开头几个字不丢。
 *
 * 与 [com.aharou.feature.agent.presentation.component.UploadIconButton] 同尺寸同图标风格，
 * 但用 [detectTapGestures] 而不是 IconButton：IconButton 只有点击、没有「按住—松开」语义。
 */
@Composable
internal fun VoiceMicButton(
    state: VoiceInputState,
    enabled: Boolean,
    callRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onToggleCall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recording = state is VoiceInputState.Recording
    val busy = state is VoiceInputState.Recognizing || state is VoiceInputState.Preparing
    val active = enabled && !busy

    Box(
        modifier = modifier
            .size(36.dp)
            .pointerInput(active) {
                if (!active) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onStart()
                        // 先按阈值等一等：阈值内松手 = 轻点，超过 = 按住
                        val quickTap = withTimeoutOrNull(CALL_TAP_MAX_MS) {
                            tryAwaitRelease()
                            true
                        } ?: false
                        if (quickTap) {
                            onCancel()
                            onToggleCall()
                        } else {
                            tryAwaitRelease()
                            onStop()
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        when {
            busy -> CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )

            else -> Icon(
                FeatherIcons.Mic,
                contentDescription = stringResource(
                    when {
                        callRunning -> R.string.voice_call_title
                        recording -> R.string.voice_input_recording
                        else -> R.string.voice_input_hold
                    }
                ),
                tint = when {
                    callRunning -> MaterialTheme.colorScheme.primary
                    recording -> MaterialTheme.colorScheme.error
                    enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                },
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 按下多久以内算「轻点」（切通话），超过就是「按住说话」。 */
private const val CALL_TAP_MAX_MS = 250L

/**
 * 录音期间的实时识别文本浮层：贴在输入栏上方，显示「边说边出」的当前结果。
 *
 * 只在本地流式模式下有内容（云端接口是批处理，录音期间拿不到半成品）。
 */
@Composable
internal fun LiveTranscript(state: VoiceInputState, modifier: Modifier = Modifier) {
    val recording = state as? VoiceInputState.Recording ?: return
    if (recording.liveText.isBlank()) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = recording.liveText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** 错误提示展示一段时间后自动清掉，不常驻输入栏。 */
@Composable
internal fun VoiceErrorAutoClear(state: VoiceInputState, onClear: () -> Unit) {
    LaunchedEffect(state) {
        if (state is VoiceInputState.Error) {
            delay(2_500)
            onClear()
        }
    }
}
