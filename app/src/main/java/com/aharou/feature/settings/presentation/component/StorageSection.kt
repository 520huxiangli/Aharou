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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import com.aharou.core.ui.ChevronRotationStyle
import com.aharou.core.ui.ExpandableChevronIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.StorageUsagePalette
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.domain.model.CleanupKind
import com.aharou.feature.settings.domain.model.StorageCategory
import com.aharou.feature.settings.domain.model.StorageDetail
import com.aharou.feature.settings.domain.model.StorageDetailKey
import com.aharou.feature.settings.domain.model.StorageEntry
import com.aharou.feature.settings.domain.model.formatStorageSize
import com.aharou.feature.settings.presentation.StorageUiState
import com.aharou.feature.settings.presentation.StorageViewModel

/** 分类色点缩进宽度：色点 10dp + 与标题的间距，明细行据此与标题左对齐。 */
private val DetailIndent = 10.dp + Spacing.md
private val SelectionWidth = 48.dp

/** 连接 [StorageViewModel] 与 [StorageSection]：state 收集下沉到这里，统计逐项到达时只重组本页。 */
@Composable
internal fun StorageSectionHost(viewModel: StorageViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    StorageSection(
        state = state,
        onToggleExpand = viewModel::toggleExpanded,
        onToggleCleanup = viewModel::toggleCleanup,
        onClean = viewModel::clean
    )
}

/**
 * 存储空间页：总览、分类明细与选中项清理。
 *
 * 统计逐项到达（大目录慢），未算出的分类先占位显示「—」，不阻塞已算出的部分。
 */
@Composable
internal fun StorageSection(
    state: StorageUiState,
    onToggleExpand: (StorageCategory) -> Unit,
    onToggleCleanup: (CleanupKind) -> Unit,
    onClean: (List<CleanupKind>) -> Unit
) {
    var pendingCleanup by remember { mutableStateOf<List<CleanupKind>?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .weight(1f)
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
                    val details = entry?.details.orEmpty().filter {
                        category != StorageCategory.AiConfig ||
                            it.key == StorageDetailKey.TOOL_OUTPUT || it.key == StorageDetailKey.VISION_SESSIONS
                    }
                    val cleanupKind = when (category) {
                        StorageCategory.Logs -> CleanupKind.Logs
                        StorageCategory.Caches -> CleanupKind.Caches
                        StorageCategory.VoiceModels -> CleanupKind.VoiceModels
                        else -> null
                    }
                    CategoryRow(
                        category = category,
                        entry = entry,
                        expanded = category in state.expanded,
                        onToggle = { onToggleExpand(category) },
                        cleanupKind = cleanupKind,
                        expandable = details.isNotEmpty(),
                        selected = cleanupKind in state.selectedCleanup,
                        cleaning = state.cleaning,
                        onSelect = { cleanupKind?.let(onToggleCleanup) }
                    )
                    if (category in state.expanded) {
                        details.forEach { detail ->
                            val detailCleanup = when (detail.key) {
                                StorageDetailKey.TOOL_OUTPUT -> CleanupKind.ToolOutput
                                StorageDetailKey.VISION_SESSIONS -> CleanupKind.VisionSessions
                                else -> null
                            }
                            DetailRow(
                                detail = detail,
                                cleanupKind = detailCleanup,
                                selected = detailCleanup in state.selectedCleanup,
                                cleaning = state.cleaning,
                                onSelect = { detailCleanup?.let(onToggleCleanup) }
                            )
                        }
                    }
                }
            }
            FootNote(stringResource(R.string.storage_breakdown_note))

            state.freedBytes?.let { freed ->
                FootNote(stringResource(R.string.storage_freed, formatStorageSize(freed)))
            }
        }

        Surface(color = MaterialTheme.semanticColors.pageBackground) {
            Button(
                onClick = {
                    pendingCleanup = CleanupKind.entries.filter {
                        it in state.selectedCleanup && (state.cleanableSizes[it] ?: 0L) > 0L
                    }
                },
                enabled = state.selectedBytes > 0L && !state.cleaning,
                modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                shape = RoundedCornerShape(Radius.lg)
            ) {
                if (state.cleaning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.storage_clean_selected, formatStorageSize(state.selectedBytes)))
                }
            }
        }
    }

    pendingCleanup?.let { kinds ->
        AlertDialog(
            onDismissRequest = { pendingCleanup = null },
            title = { Text(stringResource(R.string.storage_clean_confirm_title)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    Text(stringResource(
                        R.string.storage_clean_confirm_selected,
                        formatStorageSize(kinds.sumOf { state.cleanableSizes[it] ?: 0L })
                    ))
                    kinds.forEach { kind ->
                        Column {
                            Text(stringResource(kind.labelRes), fontWeight = FontWeight.Medium)
                            Text(stringResource(kind.descRes), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text(stringResource(R.string.storage_clean_scope), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onClean(kinds)
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

@Composable
private fun OverviewCard(state: StorageUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
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
    onToggle: () -> Unit,
    cleanupKind: CleanupKind?,
    expandable: Boolean,
    selected: Boolean,
    cleaning: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (expandable) it.clickable(onClick = onToggle) else it }
            .heightIn(min = 56.dp)
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(category.color())
        )
        Spacer(Modifier.width(Spacing.md))
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(category.labelRes),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false)
            )
            if (expandable) {
                Spacer(Modifier.width(Spacing.xs))
                ExpandableChevronIcon(
                    expanded = expanded,
                    style = ChevronRotationStyle.RIGHT_DOWN,
                    size = 18.dp,
                    tint = MaterialTheme.semanticColors.subtleText
                )
            }
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = entry?.let { formatStorageSize(it.bytes) } ?: "—",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.semanticColors.subtleText
        )
        if (cleanupKind != null) {
            Checkbox(
                checked = selected && (entry?.bytes ?: 0L) > 0L,
                onCheckedChange = { onSelect() },
                enabled = (entry?.bytes ?: 0L) > 0L && !cleaning
            )
        } else {
            Spacer(Modifier.width(SelectionWidth))
        }
    }
}

@Composable
private fun DetailRow(
    detail: StorageDetail,
    cleanupKind: CleanupKind?,
    selected: Boolean,
    cleaning: Boolean,
    onSelect: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.lg + DetailIndent + if (detail.indent) Spacing.lg else 0.dp,
                end = Spacing.lg,
                top = Spacing.xs,
                bottom = Spacing.xs
            )
    ) {
        Row(
            modifier = Modifier.heightIn(min = SelectionWidth),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = cleanupKind?.let { stringResource(it.labelRes) } ?: detail.label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                maxLines = if (cleanupKind == null) 1 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = formatStorageSize(detail.bytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText
            )
            if (cleanupKind != null) {
                Checkbox(
                    checked = selected && detail.bytes > 0L,
                    onCheckedChange = { onSelect() },
                    enabled = detail.bytes > 0L && !cleaning
                )
            } else {
                Spacer(Modifier.width(SelectionWidth))
            }
        }
        val note = detail.note
        note?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.semanticColors.subtleText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
