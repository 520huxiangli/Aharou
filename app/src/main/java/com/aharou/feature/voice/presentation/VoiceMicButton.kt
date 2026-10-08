package com.aharou.feature.voice.presentation

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Mic

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

