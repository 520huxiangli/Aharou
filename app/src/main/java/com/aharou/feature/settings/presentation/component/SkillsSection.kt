package com.aharou.feature.settings.presentation.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.agent.domain.skill.SkillScope
import com.aharou.feature.settings.presentation.SkillUiEntry
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive
import compose.icons.feathericons.Book
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Globe

/**
 * 技能二级页：与「工具授权」一致的折叠分组列表——「内置 / 当前项目 / 全局」三组各自可折叠，
 * 每行一个技能（图标 + 名称 + 描述），点击行进入详情。
 * 用户可管理的两组支持左滑删除；长按任一行弹操作单（搬运 / 导出 / 删除，内置技能只有导出）。
 * 装到哪个作用域由右上角「＋」里的选择决定（与市场安装共用）。
 */
@Composable
internal fun SkillsSection(
    projectName: String?,
    entries: List<SkillUiEntry>,
    onDelete: (SkillUiEntry) -> Unit,
    onMove: (String, SkillScope, SkillScope) -> Unit,
    onExport: (SkillUiEntry) -> Unit,
    onOpenDetail: (SkillUiEntry) -> Unit
) {
    val builtinSkills = entries.filter { it.builtin }
    val projectSkills = entries.filter { it.scope == SkillScope.PROJECT }
    val globalSkills = entries.filter { it.scope == SkillScope.GLOBAL }

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
                        FeatherIcons.Book,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = stringResource(R.string.skills_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.skills_empty_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        return
    }

    var builtinExpanded by rememberSaveable { mutableStateOf(true) }
    var projectExpanded by rememberSaveable { mutableStateOf(true) }
    var globalExpanded by rememberSaveable { mutableStateOf(true) }
    // 长按某一行弹出的操作单；null 表示没弹
    var actionEntry by remember { mutableStateOf<SkillUiEntry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        if (builtinSkills.isNotEmpty()) {
            CollapsibleGroupHeader(
                text = stringResource(R.string.skills_builtin),
                expanded = builtinExpanded,
                onToggle = { builtinExpanded = !builtinExpanded }
            )
            AnimatedVisibility(visible = builtinExpanded) {
                SettingsGroup {
                    builtinSkills.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SkillRow(
                            entry = entry,
                            onDelete = null,
                            onLongClick = { actionEntry = entry },
                            onClick = { onOpenDetail(entry) }
                        )
                    }
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
                if (projectSkills.isEmpty()) {
                    SkillEmptyHint(stringResource(R.string.skills_no_project_skills))
                } else {
                    projectSkills.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SkillRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onLongClick = { actionEntry = entry },
                            onClick = { onOpenDetail(entry) }
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
                if (globalSkills.isEmpty()) {
                    SkillEmptyHint(stringResource(R.string.skills_no_global_skills))
                } else {
                    globalSkills.forEachIndexed { index, entry ->
                        if (index > 0) SettingsDivider()
                        SkillRow(
                            entry = entry,
                            onDelete = { onDelete(entry) },
                            onLongClick = { actionEntry = entry },
                            onClick = { onOpenDetail(entry) }
                        )
                    }
                }
            }
        }
    }

    actionEntry?.let { entry ->
        SkillRowActionsSheet(
            entry = entry,
            onMove = { target ->
                actionEntry = null
                onMove(entry.name, entry.scope, target)
            },
            onExport = {
                actionEntry = null
                onExport(entry)
            },
            onDismiss = { actionEntry = null }
        )
    }
}

/**
 * 技能行：图标 + 名称/描述 + 右箭头。[onDelete] 为 null 表示不可删除（内置技能），
 * 此时连左滑手势都不接——免得滑出个按钮让人以为能删；长按仍可用（弹操作单，只给导出）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SkillRow(
    entry: SkillUiEntry,
    onDelete: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit
) {
    if (onDelete == null) {
        SkillRowContent(
            entry = entry,
            modifier = if (onLongClick != null) {
                Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
            } else {
                Modifier.clickable { onClick() }
            }
        )
        return
    }
    SwipeToDeleteRow(onDelete = onDelete, onClick = onClick, onLongClick = onLongClick) {
        SkillRowContent(entry, Modifier)
    }
}

/**
 * 长按技能行的操作单：搬动（全局 ⇄ 当前项目）与导出。
 * 删除仍走左滑那条路，不在这里重复；内置技能随 App 打包，既不能搬也不能删，只剩导出。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SkillRowActionsSheet(
    entry: SkillUiEntry,
    onMove: (SkillScope) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = Spacing.xs)
            )
            SettingsGroup {
                if (!entry.builtin) {
                    SettingsRow(
                        icon = if (entry.scope == SkillScope.GLOBAL) FeatherIcons.Folder else FeatherIcons.Globe,
                        title = stringResource(
                            if (entry.scope == SkillScope.GLOBAL) R.string.skills_move_to_project
                            else R.string.skills_move_to_global
                        ),
                        subtitle = stringResource(
                            if (entry.scope == SkillScope.GLOBAL) R.string.skills_move_to_project_desc
                            else R.string.skills_move_to_global_desc
                        ),
                        onClick = {
                            onMove(
                                if (entry.scope == SkillScope.GLOBAL) SkillScope.PROJECT
                                else SkillScope.GLOBAL
                            )
                        }
                    )
                    SettingsDivider()
                }
                SettingsRow(
                    icon = FeatherIcons.Archive,
                    title = stringResource(R.string.skills_export),
                    subtitle = stringResource(R.string.skills_export_desc),
                    onClick = onExport
                )
            }
        }
    }
}

/** 技能行内容（不含左滑容器与点击处理）。 */
@Composable
private fun SkillRowContent(
    entry: SkillUiEntry,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.semanticColors.cardSurface)
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
                imageVector = FeatherIcons.Book,
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
                if (entry.builtin) {
                    McpPill(
                        text = stringResource(R.string.skills_builtin),
                        textColor = MaterialTheme.colorScheme.outline,
                        backgroundColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
                    )
                } else {
                    McpPill(
                        text = stringResource(if (entry.disabled) R.string.common_disabled else R.string.common_enabled),
                        textColor = if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary,
                        backgroundColor = (if (entry.disabled) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.tertiary).copy(alpha = 0.12f)
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

/** 分组内空状态：一行灰字，与行内容对齐。 */
@Composable
private fun SkillEmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
    )
}
