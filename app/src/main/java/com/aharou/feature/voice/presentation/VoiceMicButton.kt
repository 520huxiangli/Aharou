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

/**
 * 麦克风按钮：语音通话开关。
 *
 * **点一下开始**（免手通话，一路听你说），**再点一下停**。
 * 唤醒词常驻监听时它不挂断：点一下直接进入聆听，用完自动回到听唤醒词。
 *
 * 不做「按住说话」——一个按钮只干一件事。两种手势并存时用户猜不到：
 * 曾经把「按住录音、轻点切通话」塞在同一个按钮上，被一眼看穿地误解了，已按要求撤掉。
 *
 * 与 [com.aharou.feature.agent.presentation.component.UploadIconButton] 同尺寸同图标风格。
 */
@Composable
internal fun VoiceMicButton(
    enabled: Boolean,
    callRunning: Boolean,
    onToggleCall: () -> Unit,
    modifier: Modifier = Modifier,
    wakeRunning: Boolean = false,
) {
    Box(
        modifier = modifier
            .size(36.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onToggleCall() })
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            FeatherIcons.Mic,
            contentDescription = stringResource(
                when {
                    wakeRunning -> R.string.voice_wake_mic_hint
                    callRunning -> R.string.voice_call_toggle_stop
                    else -> R.string.voice_call_toggle_start
                }
            ),
            tint = when {
                wakeRunning || callRunning -> MaterialTheme.colorScheme.primary
                enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            },
            modifier = Modifier.size(20.dp),
        )
    }
}

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
