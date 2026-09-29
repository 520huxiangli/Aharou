package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.StorageUsagePalette
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.domain.model.CleanupKind
import com.aharou.feature.settings.domain.model.StorageCategory
import com.aharou.feature.settings.domain.model.StorageDetail
import com.aharou.feature.settings.domain.model.StorageEntry
import com.aharou.feature.settings.domain.model.formatStorageSize
import com.aharou.feature.settings.presentation.StorageUiState
import com.aharou.feature.settings.presentation.StorageViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight

/** 分类色点缩进宽度：色点 10dp + 与标题的间距，明细行据此与标题左对齐。 */
private val DetailIndent = 10.dp + Spacing.md

/** 连接 [StorageViewModel] 与 [StorageSection]：state 收集下沉到这里，统计逐项到达时只重组本页。 */
@Composable
internal fun StorageSectionHost(viewModel: StorageViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageSection(
        state = state,
        onToggleExpand = viewModel::toggleExpanded,
        onClean = { kind -> if (kind == null) viewModel.clean() else viewModel.clean(kind) }
    )
}

/**
 * 存储空间页：总览（App 占用、构成条、设备可用）+ 分类明细 + 可释放空间清理。
 *
 * 统计逐项到达（大目录慢），未算出的分类先占位显示「—」，不阻塞已算出的部分。
 */
@Composable
internal fun StorageSection(
    state: StorageUiState,
    onToggleExpand: (StorageCategory) -> Unit,
    onClean: (CleanupKind?) -> Unit
) {
    // null 表示「一键清理」，非空表示单项；有值即弹确认框。
    var pendingCleanup by remember { mutableStateOf<CleanupOption?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Spacer(Modifier.height(Spacing.sm))
        OverviewCard(state)

        SettingsGroupHeader(text = stringResource(R.string.storage_section_breakdown))
        SettingsGroup {
            StorageCategory.entries.forEachIndexed { index, category ->
                if (index > 0) SettingsDivider()
                val entry = state.entries[category]
                CategoryRow(
                    category = category,
                    entry = entry,
                    expanded = category in state.expanded,
                    onToggle = { onToggleExpand(category) }
                )
                if (category in state.expanded) {
                    entry?.details?.forEach { DetailRow(it) }
                }
            }
        }
        FootNote(stringResource(R.string.storage_breakdown_note))

        SettingsGroupHeader(text = stringResource(R.string.storage_section_reclaimable))
        SettingsGroup {
            CleanupKind.entries.forEachIndexed { index, kind ->
                if (index > 0) SettingsDivider()
                CleanupRow(
                    label = stringResource(kind.labelRes),
                    description = stringResource(kind.descRes),
                    bytes = state.cleanableSizes[kind] ?: 0L,
                    working = state.cleaning,
                    onClean = { pendingCleanup = CleanupOption(kind) }
                )
            }
            SettingsDivider()
            CleanupRow(
                label = stringResource(R.string.storage_clean_all),
                description = stringResource(R.string.storage_clean_all_desc),
                bytes = state.reclaimableBytes,
                working = state.cleaning,
                emphasize = true,
                onClean = { pendingCleanup = CleanupOption(null) }
            )
        }
        state.freedBytes?.let { freed ->
            FootNote(stringResource(R.string.storage_freed, formatStorageSize(freed)))
        }
    }

    pendingCleanup?.let { option ->
        val kind = option.kind
        AlertDialog(
            onDismissRequest = { pendingCleanup = null },
            title = { Text(stringResource(R.string.storage_clean_confirm_title)) },
            text = {
                Text(
                    if (kind == null) {
                        stringResource(
                            R.string.storage_clean_confirm_all,
                            formatStorageSize(state.reclaimableBytes)
                        )
                    } else {
                        stringResource(
                            R.string.storage_clean_confirm_single,
                            stringResource(kind.labelRes),
                            formatStorageSize(state.cleanableSizes[kind] ?: 0L)
                        )
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onClean(kind)
                    pendingCleanup = null
                }) { Text(stringResource(R.string.storage_clean)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingCleanup = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 待确认的清理目标；[kind] 为 null 表示一键清理全部。 */
private data class CleanupOption(val kind: CleanupKind?)

@Composable
private fun OverviewCard(state: StorageUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.semanticColors.cardSurface
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            Text(
                text = stringResource(R.string.storage_total_title),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                color = MaterialTheme.semanticColors.subtleText
            )
            Spacer(Modifier.height(Spacing.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatStorageSize(state.totalBytes),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (state.scanning) {
                    Spacer(Modifier.width(Spacing.sm))
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(
                        text = stringResource(R.string.storage_scanning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
            }
            Spacer(Modifier.height(Spacing.md))
            StackedBar(state.entries)
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = stringResource(
                    R.string.storage_device_free,
                    formatStorageSize(state.deviceSpace.availableBytes),
                    formatStorageSize(state.deviceSpace.totalBytes)
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
        }
    }
}

/** 各分类占比条：整条宽度代表 App 总占用，按分类颜色分段。 */
@Composable
private fun StackedBar(entries: Map<StorageCategory, StorageEntry>) {
    val total = entries.values.sumOf { it.bytes }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(10.dp)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.semanticColors.capsuleSurface)
    ) {
        if (total <= 0L) return@Box
        Row(modifier = Modifier.fillMaxSize()) {
            StorageCategory.entries.forEach { category ->
                val bytes = entries[category]?.bytes ?: 0L
                if (bytes > 0L) {
                    Box(
                        modifier = Modifier
                            .weight(bytes.toFloat() / total)
                            .fillMaxHeight()
                            .background(category.color())
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryRow(
    category: StorageCategory,
    entry: StorageEntry?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val expandable = !entry?.details.isNullOrEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (expandable) it.clickable(onClick = onToggle) else it }
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(category.color())
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = stringResource(category.labelRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = entry?.let { formatStorageSize(it.bytes) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        if (expandable) {
            Spacer(Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier
                    .size(18.dp)
                    .rotate(if (expanded) 90f else 0f)
            )
        }
    }
}

@Composable
private fun DetailRow(detail: StorageDetail) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.lg + DetailIndent + if (detail.indent) Spacing.lg else 0.dp,
                end = Spacing.lg,
                bottom = 10.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = detail.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            detail.note?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = formatStorageSize(detail.bytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.semanticColors.subtleText
        )
    }
}

@Composable
private fun CleanupRow(
    label: String,
    description: String,
    bytes: Long,
    working: Boolean,
    emphasize: Boolean = false,
    onClean: () -> Unit
) {
    val enabled = bytes > 0L && !working
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Spacing.lg, end = Spacing.sm, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (emphasize) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = formatStorageSize(bytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        TextButton(onClick = onClean, enabled = enabled) {
            if (working) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.storage_clean))
            }
        }
    }
}

/** 分组下方的灰色小字说明。 */
@Composable
private fun FootNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.semanticColors.subtleText,
        modifier = Modifier.padding(start = Spacing.md, end = Spacing.md, top = Spacing.sm)
    )
}

private fun StorageCategory.color(): Color = when (this) {
    StorageCategory.Chat -> StorageUsagePalette.Chat
    StorageCategory.Container -> StorageUsagePalette.Container
    StorageCategory.ContainerImages -> StorageUsagePalette.ContainerImages
    StorageCategory.Workspaces -> StorageUsagePalette.Workspaces
    StorageCategory.AiConfig -> StorageUsagePalette.AiConfig
    StorageCategory.VoiceModels -> StorageUsagePalette.VoiceModels
    StorageCategory.Checkpoints -> StorageUsagePalette.Checkpoints
    StorageCategory.Logs -> StorageUsagePalette.Logs
    StorageCategory.Caches -> StorageUsagePalette.Caches
    StorageCategory.OtherData -> StorageUsagePalette.OtherData
    StorageCategory.Apk -> StorageUsagePalette.Apk
}
