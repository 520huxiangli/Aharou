package com.aharou.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.BatchDeleteConfirmDialog
import com.aharou.core.ui.SelectionAction
import com.aharou.core.ui.SelectionActionBar
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.ui.rememberListSelectionState
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.settings.presentation.SubAgentUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.CheckSquare
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.Users

/**
 * 子代理二级页：与「技能」一致的折叠分组列表——「当前项目 / 全局」两组各自可折叠，
 * 每行一个子代理（图标 + 名称 + 描述 + 模型标签），左滑删除，点击行进入详情。
 * 新建入口在顶栏的「＋」，启用开关与编辑入口在详情页。
 */
@Composable
internal fun SubAgentsSection(
    projectName: String?,
    entries: List<SubAgentUiEntry>,
    onDelete: (SubAgentUiEntry) -> Unit,
    onOpenDetail: (SubAgentUiEntry) -> Unit
) {
    val projectAgents = entries.filter { it.scope == AgentDefinitionScope.PROJECT }
    val globalAgents = entries.filter { it.scope == AgentDefinitionScope.GLOBAL }

    if (entries.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.xl, vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(Radius.lg)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        FeatherIcons.Users,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.subagents_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.subagents_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }

    val settingsViewModel = rememberSettingsViewModel()
    val selection = rememberListSelectionState()
    // 选中 id 带作用域：同名子代理可能项目与全局各一份
    val selectionIds = entries.map(::subAgentSelectionId)
    LaunchedEffect(selectionIds) { selection.retain(selectionIds) }
    var showBatchDelete by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    // 长按的可能是列表深处的行，进多选后滚回顶部，否则看不到操作栏
    LaunchedEffect(selection.selectionMode) {
        if (selection.selectionMode) scrollState.animateScrollTo(0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (selection.selectionMode) {
            SelectionActionBar(
                selectedCount = selection.selectedCount,
                allSelected = selection.isAllSelected(selectionIds),
                onToggleSelectAll = { selection.toggleSelectAll(selectionIds) },
                onExit = { selection.exit() },
                actions = listOf(
                    SelectionAction(
                        icon = FeatherIcons.Trash2,
                        label = stringResource(R.string.common_delete),
                        onClick = { showBatchDelete = true },
                        destructive = true
                    )
                )
            )
        } else {
            // 长按也能进多选，但不是可发现的入口；再给一个显式按钮，与其它列表一致
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { selection.enterSelectionMode() }) {
                    Icon(
                        imageVector = FeatherIcons.CheckSquare,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(stringResource(R.string.provider_models_select))
                }
            }
        }
        CollapsibleGroupHeader(
            text = if (projectName != null) {
                stringResource(R.string.perm_current_project, projectName)
            } else {
                stringResource(R.string.perm_current_project_none)
            },
            expanded = projectExpanded,
            onToggle = { projectExpanded = !projectExpanded }
        )
        AnimatedVisibility(visible = projectExpanded) {
            SettingsGroup {
                if (projectAgents.isEmpty()) {
                    SubAgentEmptyHint(stringResource(R.string.subagents_no_project))
                } else {
                    projectAgents.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) },
                            selectionMode = selection.selectionMode,
                            selected = selection.isSelected(subAgentSelectionId(entry)),
                            onToggleSelect = { selection.toggle(subAgentSelectionId(entry)) },
                            onLongClick = {
                                selection.enterSelectionMode()
                                selection.select(subAgentSelectionId(entry))
                            }
                        )
                    }
                }
            }
        }

        CollapsibleGroupHeader(
            text = stringResource(R.string.perm_global),
            expanded = globalExpanded,
            onToggle = { globalExpanded = !globalExpanded }
        )
        AnimatedVisibility(visible = globalExpanded) {
            SettingsGroup {
                if (globalAgents.isEmpty()) {
                    SubAgentEmptyHint(stringResource(R.string.subagents_no_global))
                } else {
                    globalAgents.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SubAgentRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onClick = { onOpenDetail(entry) },
                            selectionMode = selection.selectionMode,
                            selected = selection.isSelected(subAgentSelectionId(entry)),
                            onToggleSelect = { selection.toggle(subAgentSelectionId(entry)) },
                            onLongClick = {
                                selection.enterSelectionMode()
                                selection.select(subAgentSelectionId(entry))
                            }
                        )
                    }
                }
            }
        }
    }

    if (showBatchDelete) {
        val selectedEntries = entries.filter { subAgentSelectionId(it) in selection.selectedIds }
        BatchDeleteConfirmDialog(
            title = stringResource(R.string.common_delete),
            message = stringResource(R.string.common_batch_delete_confirm, selectedEntries.size),
            onConfirm = {
                showBatchDelete = false
                selection.exit()
                // 删除接口按作用域定位目录，跨作用域的选中必须分组提交
                selectedEntries.groupBy { it.scope }.forEach { (scope, group) ->
                    settingsViewModel.deleteSubAgents(group.map { it.name }, scope)
                }
            },
            onDismiss = { showBatchDelete = false }
        )
    }
}

/** 选中 id：「作用域:名称」——同名子代理可能项目与全局各一份。 */
private fun subAgentSelectionId(entry: SubAgentUiEntry): String = "${entry.scope.name}:${entry.name}"

/** 单个子代理行：图标 + 名称/描述 + 模型标签 + 右箭头；左滑删除，点击行进入详情，长按进多选。 */
@Composable
private fun SubAgentRow(
    entry: SubAgentUiEntry,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    onLongClick: (() -> Unit)? = null
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface

    SwipeToDeleteRow(
        onDelete = onDelete,
        onClick = onClick,
        onLongClick = onLongClick,
        selectionMode = selectionMode,
        selected = selected,
        onToggleSelect = onToggleSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(rowBackground)
                .padding(start = Spacing.lg, end = Spacing.xs, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = FeatherIcons.Users,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(Spacing.md))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    entry.model?.let { model ->
                        McpPill(
                            text = model,
                            textColor = MaterialTheme.colorScheme.tertiary,
                            backgroundColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
                        )
                    }
                    // 只在禁用时挂标签：默认启用是常态，每行都挂一个反而护不住重点。
                    if (entry.disabled) {
                        McpPill(
                            text = stringResource(R.string.common_disabled),
                            textColor = MaterialTheme.colorScheme.outline,
                            backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                        )
                    }
                }
                Text(
                    text = entry.description.ifBlank { stringResource(R.string.mcp_no_description) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(Spacing.sm))

            Icon(
                imageVector = FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.semanticColors.subtleText,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun SubAgentEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
