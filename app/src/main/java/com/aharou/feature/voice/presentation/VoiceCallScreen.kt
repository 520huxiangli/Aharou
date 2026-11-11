package com.aharou.feature.voice.presentation

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.soul.SoulIcon
import com.aharou.core.soul.SoulStore
import com.aharou.core.ui.ChatBackgroundLayer
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.feature.voice.call.VoiceCallSession
import com.aharou.feature.voice.data.CallBackgroundRepository

/**
 * 免手通话的全屏页：人设头像、当前状态、正在说的那句，和一个挂断键。
 *
 * 只在真有一轮对话时盖上来——唤醒常驻本身是后台在听，不该把主界面顶掉。
 * 说话中途点屏幕任意处是打断，与「挂断」不是一回事：前者继续听，后者收摊。
 */
@Composable
internal fun VoiceCallScreen(
    session: VoiceCallSession,
    background: CallBackgroundRepository.Config = CallBackgroundRepository.Config(),
) {
    val state by session.state.collectAsStateWithLifecycle()
    if (state == VoiceCallSession.State.Idle || state == VoiceCallSession.State.WakeListening) return
    val beingSaid by session.displayText.collectAsStateWithLifecycle()
    val error by session.error.collectAsStateWithLifecycle()
    val muted by session.muted.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val metadata = remember { SoulStore.load(context)?.metadata }
    val name = metadata?.name?.trim().orEmpty().ifBlank { stringResource(R.string.voice_call_screen_default_name) }
    val canInterrupt = state == VoiceCallSession.State.Speaking || state == VoiceCallSession.State.Thinking

    BackHandler { session.hangUp() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
        ChatBackgroundLayer(
            imageName = background.imagePath,
            blurDp = background.blurDp,
            dim = background.dim,
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(1f))
            SoulAvatar(metadata?.icon.orEmpty(), 104.dp)
            Spacer(Modifier.size(Spacing.lg))
            Text(
                text = name,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(Spacing.sm))
            Text(
                text = stringResource(
                    if (muted && state == VoiceCallSession.State.Listening) {
                        R.string.voice_call_screen_muted
                    } else {
                        statusTextRes(state)
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (beingSaid.isNotBlank()) {
                Spacer(Modifier.size(Spacing.lg))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(Radius.lg),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = beingSaid,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
                    )
                }
            }
            if (error != null) {
                Spacer(Modifier.size(Spacing.md))
                Text(
                    text = errorText(error.orEmpty()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.weight(1f))
            if (canInterrupt) {
                Text(
                    text = stringResource(R.string.voice_call_screen_tap_interrupt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(Spacing.md))
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxl),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(
                                if (muted) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.surfaceVariant
                            )
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { session.setMuted(!muted) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = stringResource(R.string.voice_call_screen_mute),
                            tint = if (muted) MaterialTheme.colorScheme.onError
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    Spacer(Modifier.size(Spacing.xs))
                    Text(
                        text = stringResource(R.string.voice_call_screen_mute),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { session.hangUp() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CallEnd,
                            contentDescription = stringResource(R.string.voice_call_screen_hangup),
                            tint = MaterialTheme.colorScheme.onError,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                    Spacer(Modifier.size(Spacing.xs))
                    Text(
                        text = stringResource(R.string.voice_call_screen_hangup),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(Spacing.xl))
        }
        }
    }
}

/** 头像用的是 SOUL.md 里那颗图标：图片就画图，短 emoji 就直接排字。 */
@Composable
private fun SoulAvatar(icon: String, size: Dp) {
    val circular = RoundedCornerShape(Radius.pill)
    val bitmap = remember(icon) {
        if (!SoulIcon.isDataUri(icon)) {
            null
        } else {
            runCatching {
                val raw = Base64.decode(icon.removePrefix(SoulIcon.DATA_URI_PREFIX), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(raw, 0, raw.size)
            }.getOrNull()
        }
    }
    when {
        bitmap != null -> Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(circular),
        )
        else -> Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = circular,
            modifier = Modifier.size(size),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Text(
                    text = icon.takeIf { it.isNotBlank() && it.length <= 4 } ?: "✨",
                    style = MaterialTheme.typography.displaySmall,
                )
            }
        }
    }
}

private fun statusTextRes(state: VoiceCallSession.State): Int = when (state) {
    VoiceCallSession.State.Preparing -> R.string.voice_call_state_preparing
    VoiceCallSession.State.Listening -> R.string.voice_call_state_listening
    VoiceCallSession.State.Thinking -> R.string.voice_call_state_thinking
    VoiceCallSession.State.Speaking -> R.string.voice_call_state_speaking
    VoiceCallSession.State.WakeListening -> R.string.voice_wake_on
    VoiceCallSession.State.Idle -> R.string.voice_call_state_idle
}

@Composable
private fun errorText(code: String): String = when (code) {
    "NO_WORKSPACE" -> stringResource(R.string.voice_call_screen_error_no_workspace)
    "NO_SESSION" -> stringResource(R.string.voice_call_screen_error_no_session)
    else -> code
}
