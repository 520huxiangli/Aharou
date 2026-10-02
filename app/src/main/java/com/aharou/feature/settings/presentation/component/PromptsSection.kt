package com.aharou.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.prompt.PromptFragment
import com.aharou.feature.agent.domain.prompt.PromptFragmentSource
import com.aharou.feature.settings.presentation.PromptsUiState
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.FileText
import compose.icons.feathericons.Plus
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * 编辑目标：区分「新建」与「编辑既有编号」。
 *
 * - [number] = null：新建一条编号片段
 * - 非 null：编辑该编号最终生效的片段
 */
internal data class PromptEditTarget(val number: Int? = null)

/**
 * 提示词页：顶部是「完全禁用内置提示词」开关，下面是按编号列出的生效片段。
 *
 * 每行一张卡片（图标 + 编号·名称 + 摘要 + 来源徽章），长按行内容可拖拽调整顺序，
 * 点击进入详情预览（右上角再进编辑），来源可写时左滑删除。
 */
@Composable
internal fun PromptsSection(
    state: PromptsUiState,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit,
    onToggleBuiltinDisabled: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsGroup(
            modifier = Modifier
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm)
        ) {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.prompts_disable_builtin),
                subtitle = stringResource(R.string.prompts_disable_builtin_hint),
                trailing = {
                    AppSwitch(checked = state.builtinDisabled, onCheckedChange = onToggleBuiltinDisabled)
                }
            )
        }

        if (state.fragments.isEmpty()) {
            Text(
                text = stringResource(R.string.prompts_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        } else {
            PromptList(
                fragments = state.fragments,
                onOpenFragment = onOpenFragment,
                onDeleteFragment = onDeleteFragment,
                onReorder = onReorder
            )
        }
    }
}

/**
 * 片段列表。拖拽时只更新本地顺序（不落盘），松手才把最终顺序交给 [onReorder] 持久化，
 * 避免拖动过程中反复写盘 + 重读导致列表抽搐。
 */
@Composable
private fun PromptList(
    fragments: List<PromptFragment>,
    onOpenFragment: (PromptFragment) -> Unit,
    onDeleteFragment: (Int) -> Unit,
    onReorder: (List<PromptFragment>) -> Unit
) {
    var localFragments by remember { mutableStateOf(fragments) }
    LaunchedEffect(fragments) { localFragments = fragments }

    val listState = rememberLazyListState()
    val reorderableState = rememberReorderableLazyListState(listState) { from, to ->
        localFragments = localFragments.toMutableList().apply { add(to.index, removeAt(from.index)) }
    }
    val hapticFeedback = LocalHapticFeedback.current

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        contentPadding = PaddingValues(top = Spacing.xs),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(
            items = localFragments,
            key = { index, fragment ->
                "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            }
        ) { index, fragment ->
            val rowKey = "${fragment.stableReorderKey}:${localFragments.take(index).count { it.stableReorderKey == fragment.stableReorderKey }}"
            ReorderableItem(state = reorderableState, key = rowKey) { isDragging ->
                val dragScale by animateFloatAsState(
                    targetValue = if (isDragging) 0.97f else 1f,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragScale"
                )
                val dragElevation by animateDpAsState(
                    targetValue = if (isDragging) 8.dp else 0.dp,
                    animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
                    label = "promptDragElevation"
                )
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    shadowElevation = dragElevation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            scaleX = dragScale
                            scaleY = dragScale
                        }
                ) {
                    PromptRow(
                        fragment = fragment,
                        onClick = { onOpenFragment(fragment) },
                        onDelete = { onDeleteFragment(fragment.number) },
                        deleteEnabled = fragment.editable,
                        dragModifier = Modifier.longPressDraggableHandle(
                            onDragStarted = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            },
                            onDragStopped = {
                                hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                // 立即重编号（槽化），与落盘后的编号一致，避免 key 变化引起列表跳动
                                val numbers = localFragments.map { it.number }.sorted()
                                val renumbered = localFragments.mapIndexed { index, item ->
                                    item.copy(number = numbers[index])
                                }
                                localFragments = renumbered
                                onReorder(renumbered)
                            }
                        )
                    )
                }
            }
        }
    }
}

/**
 * 右上角「+」弹层：添加片段入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PromptsAddSheet(
    onDismiss: () -> Unit,
    onAddFragment: () -> Unit
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            SettingsGroupHeader(text = stringResource(R.string.prompts_add_sheet_title))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.prompts_add_prompt),
                    subtitle = stringResource(R.string.prompts_add_prompt_hint),
                    onClick = onAddFragment
                )
            }
        }
    }
}

/** 单条片段行：图标 + 编号·名称 + 摘要 + 来源徽章 + 箭头；长按内容区拖拽排序，左滑删除。 */
@Composable
private fun PromptRow(
    fragment: PromptFragment,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    deleteEnabled: Boolean,
    dragModifier: Modifier
) {
    val sortDescription = stringResource(R.string.prompts_sort_long_press)
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = Spacing.lg, end = Spacing.xs, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .then(dragModifier)
                    .semantics { contentDescription = sortDescription },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.FileText,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "%02d · %s".format(fragment.number, fragment.title),
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (fragment.description.isNotBlank()) {
                        Text(
                            text = fragment.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(Spacing.sm))
            SourceBadge(fragment.source)
            Spacer(modifier = Modifier.width(Spacing.xs))
            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }

    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, deleteEnabled = deleteEnabled) { row() }
}

/** 来源徽章：一眼看出该编号的内容来自哪一层。 */
@Composable
private fun SourceBadge(source: PromptFragmentSource) {
    val color = when (source) {
        PromptFragmentSource.PROJECT -> MaterialTheme.colorScheme.primary
        PromptFragmentSource.GLOBAL -> MaterialTheme.colorScheme.tertiary
        PromptFragmentSource.LOCAL -> MaterialTheme.colorScheme.secondary
        PromptFragmentSource.BUILTIN -> MaterialTheme.semanticColors.subtleText
    }
    Text(
        text = stringResource(
            when (source) {
                PromptFragmentSource.PROJECT -> R.string.prompts_source_project
                PromptFragmentSource.GLOBAL -> R.string.prompts_source_global
                PromptFragmentSource.LOCAL -> R.string.prompts_source_local
                PromptFragmentSource.BUILTIN -> R.string.prompts_source_builtin
            }
        ),
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}
