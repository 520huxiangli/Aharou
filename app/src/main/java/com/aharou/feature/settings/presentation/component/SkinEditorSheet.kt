package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.AppSkinTokens
import com.aharou.core.theme.CustomSkin
import com.aharou.core.theme.CustomSkinJson
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.colorFromHsl
import com.aharou.core.theme.colorToHsl
import com.aharou.core.theme.customSkinTokens
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * 自定义皮肤编辑器：拖滑块调主色（可选再调一个辅色），下方实时预览整页观感。
 *
 * 预览不走当前主题，而是用这套皮肤自己算出来的颜色直接画——所见即保存后的样子。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SkinEditorSheet(
    editing: CustomSkin?,
    onDismiss: () -> Unit,
    onSave: (CustomSkin) -> Unit,
    onDelete: ((String) -> Unit)? = null
) {
    val clipboard = LocalClipboardManager.current

    val initialPrimary = remember(editing) {
        colorToHsl(editing?.primaryColor() ?: Color(0xFF4F8CFF))
    }
    val initialAccent = remember(editing) { editing?.accentColor()?.let { colorToHsl(it) } }

    var hue by remember { mutableFloatStateOf(initialPrimary.first) }
    var saturation by remember { mutableFloatStateOf(initialPrimary.second) }
    var lightness by remember { mutableFloatStateOf(initialPrimary.third) }
    var accentEnabled by remember { mutableStateOf(initialAccent != null) }
    var accentHue by remember { mutableFloatStateOf(initialAccent?.first ?: 300f) }
    var accentSaturation by remember { mutableFloatStateOf(initialAccent?.second ?: 0.6f) }
    var accentLightness by remember { mutableFloatStateOf(initialAccent?.third ?: 0.6f) }
    var name by remember { mutableStateOf(editing?.name.orEmpty()) }
    var importText by remember { mutableStateOf("") }
    var importFailed by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    val primaryColor = colorFromHsl(hue, saturation, lightness)
    val accentColor =
        if (accentEnabled) colorFromHsl(accentHue, accentSaturation, accentLightness) else null
    val skin = remember(primaryColor, accentColor) { customSkinTokens(primaryColor, accentColor) }

    fun draft(): CustomSkin = CustomSkin(
        id = editing?.id ?: CustomSkin().id,
        name = name.trim(),
        primaryArgb = primaryColor.toArgb().toLong() and 0xFFFFFFFFL,
        accentArgb = accentColor?.let { it.toArgb().toLong() and 0xFFFFFFFFL }
    )

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
                text = stringResource(R.string.skin_editor_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            SkinPreview(skin)

            HslSliders(
                label = stringResource(R.string.skin_editor_primary),
                hue = hue,
                saturation = saturation,
                lightness = lightness,
                onHueChange = { hue = it; copied = false },
                onSaturationChange = { saturation = it; copied = false },
                onLightnessChange = { lightness = it; copied = false }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skin_editor_accent),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                AppSwitch(checked = accentEnabled, onCheckedChange = { accentEnabled = it })
            }
            if (accentEnabled) {
                HslSliders(
                    label = stringResource(R.string.skin_editor_accent),
                    hue = accentHue,
                    saturation = accentSaturation,
                    lightness = accentLightness,
                    onHueChange = { accentHue = it },
                    onSaturationChange = { accentSaturation = it },
                    onLightnessChange = { accentLightness = it }
                )
            }

            AppTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = stringResource(R.string.skin_editor_name_hint)
            )

            if (copied) {
                Text(
                    text = stringResource(R.string.skin_editor_copied),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(CustomSkinJson.encodeToString(draft())))
                    copied = true
                }) {
                    Text(stringResource(R.string.skin_editor_copy_json))
                }
                TextButton(onClick = { showImport = true }) {
                    Text(stringResource(R.string.skin_editor_import_json))
                }
                Spacer(Modifier.weight(1f))
                onDelete?.let { remove ->
                    TextButton(onClick = { editing?.let { remove(it.id) } }) {
                        Text(
                            text = stringResource(R.string.skin_editor_delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                TextButton(onClick = { onSave(draft()) }) {
                    Text(
                        text = stringResource(R.string.skin_editor_save),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    if (showImport) {
        AlertDialog(
            onDismissRequest = { showImport = false; importFailed = false },
            title = { Text(stringResource(R.string.skin_editor_import_json)) },
            text = {
                Column {
                    AppTextField(
                        value = importText,
                        onValueChange = { importText = it; importFailed = false },
                        placeholder = stringResource(R.string.skin_editor_import_hint),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 6,
                        isError = importFailed
                    )
                    if (importFailed) {
                        Text(
                            text = stringResource(R.string.skin_editor_import_failed),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = Spacing.xs)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val parsed = runCatching {
                        CustomSkinJson.decodeFromString<CustomSkin>(importText.trim())
                    }.getOrNull()
                    if (parsed == null) {
                        importFailed = true
                    } else {
                        val hsl = colorToHsl(parsed.primaryColor())
                        hue = hsl.first
                        saturation = hsl.second
                        lightness = hsl.third
                        parsed.accentColor()?.let { accent ->
                            val accentHsl = colorToHsl(accent)
                            accentEnabled = true
                            accentHue = accentHsl.first
                            accentSaturation = accentHsl.second
                            accentLightness = accentHsl.third
                        }
                        name = parsed.name
                        importText = ""
                        importFailed = false
                        showImport = false
                    }
                }) {
                    Text(stringResource(R.string.skin_editor_import_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importFailed = false }) {
                    Text(stringResource(R.string.skin_editor_cancel))
                }
            }
        )
    }
}

/** 用这套皮肤自己的颜色画一小块界面，让用户直接看到保存后的样子。 */
@Composable
private fun SkinPreview(skin: AppSkinTokens) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(skin.pageBackground)
            .padding(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Brush.linearGradient(listOf(skin.gradientStart, skin.gradientEnd)))
            )
            Spacer(Modifier.width(Spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                StatusDot(skin.primary, skin.card)
                StatusDot(skin.success, skin.card)
                StatusDot(skin.warning, skin.card)
                StatusDot(skin.danger, skin.card)
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(skin.card)
                .border(1.dp, skin.border, RoundedCornerShape(12.dp))
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Bar(skin.text, 0.66f, 8.dp)
            Bar(skin.textSecondary, 1f, 6.dp)
            Bar(skin.textDisabled, 1f, 6.dp)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(skin.primary)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Bar(skin.onPrimary, 0.7f, 6.dp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(skin.primarySoft)
                        .padding(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Bar(skin.primary, 0.5f, 6.dp)
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(THREE_DP))
                        .background(skin.successSoft)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    StatusDot(skin.success, skin.successSoft)
                }
            }
        }
    }
}

private val THREE_DP = 6.dp

@Composable
private fun Bar(color: Color, widthFraction: Float, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.85f))
    )
}

@Composable
private fun StatusDot(color: Color, ringColor: Color) {
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, ringColor, CircleShape)
    )
}

@Composable
internal fun HslSliders(
    label: String,
    hue: Float,
    saturation: Float,
    lightness: Float,
    onHueChange: (Float) -> Unit,
    onSaturationChange: (Float) -> Unit,
    onLightnessChange: (Float) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(colorFromHsl(hue, saturation, lightness))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }
        Slider(value = hue, onValueChange = onHueChange, valueRange = 0f..360f)
        Slider(value = saturation, onValueChange = onSaturationChange, valueRange = 0f..1f)
        Slider(value = lightness, onValueChange = onLightnessChange, valueRange = 0f..1f)
    }
}
