package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.AppThemePreset
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.isDynamicColorSupported
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.settings.data.repository.AppThemeMode
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

/**
 * 外观主题面板：明暗模式、配色方案与莫奈取色。
 *
 * 选择后不关闭弹窗，便于连续对比几套配色。
 *
 * @param selectedPresetId 当前配色方案 id，null 表示未选过（回退默认）。
 * @param dynamicColorEnabled 莫奈取色开关状态；开启时配色方案不可选。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSelectionSheet(
    selected: AppThemeMode,
    selectedPresetId: String?,
    dynamicColorEnabled: Boolean,
    backgroundImagePath: String?,
    backgroundAlpha: Float,
    onSelected: (AppThemeMode) -> Unit,
    onPresetSelected: (String) -> Unit,
    onDynamicColorChanged: (Boolean) -> Unit,
    onPickBackgroundImage: (android.net.Uri) -> Unit,
    onBackgroundAlphaChange: (Float) -> Unit,
    onRemoveBackgroundImage: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val activePreset = AppThemePreset.findById(selectedPresetId)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl)
        ) {
            SectionLabel(stringResource(R.string.theme_mode_section))

            AppThemeMode.entries.forEach { mode ->
                val isSelected = mode == selected
                Surface(
                    onClick = { onSelected(mode) },
                    color = Color.Transparent
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(mode.labelRes),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected) {
                            Icon(
                                imageVector = FeatherIcons.Check,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(Spacing.md))

            SectionLabel(stringResource(R.string.theme_preset_section))

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg)
                    .alpha(if (dynamicColorEnabled) 0.4f else 1f),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AppThemePreset.ALL_PRESETS.forEach { preset ->
                    PresetChip(
                        preset = preset,
                        darkTheme = isDarkTheme,
                        isSelected = preset.id == activePreset.id,
                        enabled = !dynamicColorEnabled,
                        onSelect = { onPresetSelected(preset.id) }
                    )
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.theme_dynamic_color),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            if (isDynamicColorSupported) {
                                R.string.theme_dynamic_color_desc
                            } else {
                                R.string.theme_dynamic_color_unsupported
                            }
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(Spacing.md))
                AppSwitch(
                    checked = dynamicColorEnabled && isDynamicColorSupported,
                    onCheckedChange = onDynamicColorChanged,
                    enabled = isDynamicColorSupported
                )
            }

            Spacer(Modifier.height(Spacing.sm))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(Modifier.height(Spacing.md))
            SectionLabel(stringResource(R.string.settings_background_image))
            BackgroundImageControls(
                imagePath = backgroundImagePath,
                alpha = backgroundAlpha,
                onPickImage = onPickBackgroundImage,
                onAlphaChange = onBackgroundAlphaChange,
                onRemove = onRemoveBackgroundImage
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.sm)
    )
}

/** 配色方案色块：外圈是该主题的页面底色，内圈是主色，让浅色/深色主题一眼可辨。 */
@Composable
private fun PresetChip(
    preset: AppThemePreset,
    darkTheme: Boolean,
    isSelected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    val (accentColor, canvasColor) = preset.previewColors(darkTheme)
    val shape = RoundedCornerShape(Radius.pill)

    Surface(
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onSelect)
            .border(
                width = if (isSelected) 1.5.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                shape = shape
            ),
        shape = shape,
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.sm, end = 14.dp, top = 7.dp, bottom = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(canvasColor)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Spacer(
                    modifier = Modifier
                        .size(11.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(preset.nameRes),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
