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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.CustomSkin
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.agent.data.repository.ChatAppearance
import com.aharou.feature.agent.data.repository.ChatAppearanceRepository
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Plus
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 外观主题页：明暗模式、配色方案、莫奈取色与背景图。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThemeSettingsSection(
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
    onRemoveBackgroundImage: () -> Unit
) {
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, ThemeSkinEntryPoint::class.java)
    }
    val repository = entryPoint.themeSettingsRepository()
    val chatRepository = entryPoint.chatAppearanceRepository()
    val customSkins by repository.customSkinsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val chatAppearance by chatRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = ChatAppearance())
    val activePreset = AppThemePreset.findById(selectedPresetId, customSkins)
    val scope = rememberCoroutineScope()
    var editorVisible by remember { mutableStateOf(false) }
    var editorTarget by remember { mutableStateOf<CustomSkin?>(null) }
    var chatSheetVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = Spacing.sm)
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
                    label = stringResource(preset.nameRes),
                    darkTheme = isDarkTheme,
                    isSelected = preset.id == activePreset.id,
                    enabled = !dynamicColorEnabled,
                    onSelect = { onPresetSelected(preset.id) }
                )
            }
            customSkins.forEach { custom ->
                val customPreset = remember(custom) { custom.toThemePreset() }
                val isCustomSelected = customPreset.id == activePreset.id
                PresetChip(
                    preset = customPreset,
                    label = custom.name.ifBlank { stringResource(R.string.skin_custom) },
                    darkTheme = isDarkTheme,
                    isSelected = isCustomSelected,
                    enabled = !dynamicColorEnabled,
                    // 已选中的自定义皮肤再点一次就进编辑器，省得在窄 chip 上再塞一个编辑图标。
                    onSelect = {
                        if (isCustomSelected) {
                            editorTarget = custom
                            editorVisible = true
                        } else {
                            onPresetSelected(customPreset.id)
                        }
                    }
                )
            }
            AddCustomSkinChip(
                enabled = !dynamicColorEnabled,
                onClick = {
                    editorTarget = null
                    editorVisible = true
                }
            )
        }

        Spacer(Modifier.height(Spacing.sm))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !dynamicColorEnabled) { chatSheetVisible = true }
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.chat_appearance_title),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = if (chatAppearance.userBubbleFollowsSkin && !chatAppearance.assistantBubbleEnabled) {
                    stringResource(R.string.chat_appearance_follow_skin)
                } else {
                    stringResource(R.string.skin_custom)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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

        if (editorVisible) {
            SkinEditorSheet(
                editing = editorTarget,
                onDismiss = { editorVisible = false },
                onSave = { skin ->
                    scope.launch { repository.upsertCustomSkin(skin) }
                    editorVisible = false
                },
                onDelete = { id ->
                    scope.launch { repository.deleteCustomSkin(id) }
                    editorVisible = false
                }
            )
        }

        if (chatSheetVisible) {
            ChatAppearanceSheet(
                current = chatAppearance,
                onDismiss = { chatSheetVisible = false },
                onSave = { next ->
                    scope.launch { chatRepository.update { next } }
                    chatSheetVisible = false
                }
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
    label: String,
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
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/** 「＋ 自定义皮肤」入口：点开就是取色编辑器。 */
@Composable
private fun AddCustomSkinChip(enabled: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(Radius.pill)
    Row(
        modifier = Modifier
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), shape)
            .padding(horizontal = Spacing.md, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = FeatherIcons.Plus,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.skin_custom_add),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 主题页直读仓库：这条链路不经过行数已满的 SettingsViewModel。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ThemeSkinEntryPoint {
    fun themeSettingsRepository(): ThemeSettingsRepository
    fun chatAppearanceRepository(): ChatAppearanceRepository
}
