package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.colorFromHsl
import com.aharou.core.theme.colorToHsl
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.data.repository.ChatAppearance
import kotlin.math.roundToInt

/**
 * 聊天气泡外观：我的气泡与助手气泡分开设，默认都跟随皮肤。
 *
 * 助手气泡默认关闭——对话区本来是扁平文档流，助手正文直接铺在页面上，
 * 打开才给它套一层药丸，和用户消息对称。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAppearanceSheet(
    current: ChatAppearance,
    onDismiss: () -> Unit,
    onSave: (ChatAppearance) -> Unit
) {
    // MaterialTheme 只能在 Composable 上下文读，先取出来再交给 remember。
    val skinUserBubble = MaterialTheme.colorScheme.primaryContainer
    val skinAssistantBubble = MaterialTheme.colorScheme.surfaceVariant
    val initialUser = remember(current.userBubbleColor, skinUserBubble) {
        colorToHsl(current.userBubbleColor?.let { Color(it) } ?: skinUserBubble)
    }
    val initialAssistant = remember(current.assistantBubbleColor, skinAssistantBubble) {
        colorToHsl(current.assistantBubbleColor?.let { Color(it) } ?: skinAssistantBubble)
    }

    var userFollows by remember { mutableStateOf(current.userBubbleFollowsSkin) }
    var userHue by remember { mutableFloatStateOf(initialUser.first) }
    var userSaturation by remember { mutableFloatStateOf(initialUser.second) }
    var userLightness by remember { mutableFloatStateOf(initialUser.third) }
    var userAlpha by remember { mutableFloatStateOf(current.userBubbleAlpha) }

    var assistantEnabled by remember { mutableStateOf(current.assistantBubbleEnabled) }
    var assistantFollows by remember { mutableStateOf(current.assistantBubbleFollowsSkin) }
    var assistantHue by remember { mutableFloatStateOf(initialAssistant.first) }
    var assistantSaturation by remember { mutableFloatStateOf(initialAssistant.second) }
    var assistantLightness by remember { mutableFloatStateOf(initialAssistant.third) }
    var assistantAlpha by remember { mutableFloatStateOf(current.assistantBubbleAlpha) }

    var cornerDp by remember { mutableFloatStateOf(current.bubbleCornerDp.toFloat()) }

    val userColor = colorFromHsl(userHue, userSaturation, userLightness)
    val assistantColor = colorFromHsl(assistantHue, assistantSaturation, assistantLightness)
    val corner = cornerDp.roundToInt()

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.chat_appearance_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            BubblePreview(
                userColor = userColor.copy(alpha = userAlpha),
                assistantEnabled = assistantEnabled,
                assistantColor = assistantColor.copy(alpha = assistantAlpha),
                cornerDp = corner
            )

            SectionTitle(stringResource(R.string.chat_appearance_user))
            FollowSkinRow(
                checked = userFollows,
                onCheckedChange = { userFollows = it }
            )
            if (!userFollows) {
                HslSliders(
                    label = stringResource(R.string.chat_appearance_user),
                    hue = userHue,
                    saturation = userSaturation,
                    lightness = userLightness,
                    onHueChange = { userHue = it },
                    onSaturationChange = { userSaturation = it },
                    onLightnessChange = { userLightness = it }
                )
                AlphaSlider(value = userAlpha, onValueChange = { userAlpha = it })
            }

            SectionTitle(stringResource(R.string.chat_appearance_assistant))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_appearance_assistant_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(Spacing.sm))
                AppSwitch(checked = assistantEnabled, onCheckedChange = { assistantEnabled = it })
            }
            if (assistantEnabled) {
                FollowSkinRow(
                    checked = assistantFollows,
                    onCheckedChange = { assistantFollows = it }
                )
                if (!assistantFollows) {
                    HslSliders(
                        label = stringResource(R.string.chat_appearance_assistant),
                        hue = assistantHue,
                        saturation = assistantSaturation,
                        lightness = assistantLightness,
                        onHueChange = { assistantHue = it },
                        onSaturationChange = { assistantSaturation = it },
                        onLightnessChange = { assistantLightness = it }
                    )
                    AlphaSlider(value = assistantAlpha, onValueChange = { assistantAlpha = it })
                }
            }

            SectionTitle(stringResource(R.string.chat_appearance_corner) + " · ${corner}dp")
            Slider(value = cornerDp, onValueChange = { cornerDp = it }, valueRange = 0f..28f)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.skin_editor_cancel))
                }
                TextButton(onClick = {
                    onSave(
                        ChatAppearance(
                            userBubbleFollowsSkin = userFollows,
                            userBubbleColor = userColor.toArgb().toLong() and 0xFFFFFFFFL,
                            userBubbleAlpha = userAlpha,
                            assistantBubbleEnabled = assistantEnabled,
                            assistantBubbleFollowsSkin = assistantFollows,
                            assistantBubbleColor = assistantColor.toArgb().toLong() and 0xFFFFFFFFL,
                            assistantBubbleAlpha = assistantAlpha,
                            bubbleCornerDp = corner
                        )
                    )
                }) {
                    Text(
                        text = stringResource(R.string.skin_editor_save),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun FollowSkinRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.chat_appearance_follow_skin),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        AppSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun AlphaSlider(value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Text(
            text = "${stringResource(R.string.chat_appearance_opacity)} · ${
                (value * 100).roundToInt()
            }%",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Slider(value = value, onValueChange = onValueChange, valueRange = 0.2f..1f)
    }
}

/** 预览：一条我的气泡（右）与一条助手气泡（左，打开时才有）。 */
@Composable
private fun BubblePreview(
    userColor: Color,
    assistantEnabled: Boolean,
    assistantColor: Color,
    cornerDp: Int
) {
    val shape = RoundedCornerShape(cornerDp.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (assistantEnabled) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(assistantColor)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                PreviewBar(contentColor = assistantColor)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Box(
                modifier = Modifier
                    .clip(shape)
                    .background(userColor)
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                PreviewBar(contentColor = userColor)
            }
        }
    }
}

/** 用与底色对比的色画两条小短条表示文字。 */
@Composable
private fun PreviewBar(contentColor: Color) {
    val barColor =
        if (contentColor.luminance() > 0.5f) Color.Black.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.6f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(barColor)
        )
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(barColor.copy(alpha = 0.6f))
        )
    }
}
