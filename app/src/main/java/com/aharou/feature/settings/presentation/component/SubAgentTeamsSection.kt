package com.aharou.feature.settings.presentation.component

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.settings.presentation.TeamSettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Users

/**
 * 设置 → 子代理 → 团队预设：新建/编辑预设、点行看阶段与成员、左滑删除。
 *
 * 表单保存后写回 teams 目录下的 .md，与手写完全等价；文件路径在详情里给出，方便直接改文本。
 */
@Composable
internal fun SubAgentTeamsSection(
    viewModel: TeamSettingsViewModel = hiltViewModel()
) {
    val teams by viewModel.teams.collectAsStateWithLifecycle()
    val saveState by viewModel.saveState.collectAsStateWithLifecycle()
    var detail by remember { mutableStateOf<AgentTeamEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<AgentTeamEntry?>(null) }
    var editorTarget by remember { mutableStateOf<EditorTarget?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 新建入口常驻：空列表时更需要它，所以不放在列表判定里面。
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Plus,
                    title = stringResource(R.string.team_new),
                    onClick = {
                        viewModel.resetSaveState()
                        editorTarget = EditorTarget(null)
                    }
                )
            }
        }

        if (teams.isEmpty()) {
            Text(
                text = stringResource(R.string.team_list_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl)
            )
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.lg)
                    .padding(bottom = Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                AgentDefinitionScope.entries.forEach { scope ->
                    val scoped = teams.filter { it.scope == scope }
                    if (scoped.isEmpty()) return@forEach
                    SettingsGroupHeader(
                        text = stringResource(
                            if (scope == AgentDefinitionScope.GLOBAL) R.string.team_scope_global
                            else R.string.team_scope_project
                        )
                    )
                    SettingsGroup {
                        scoped.forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider()
                            SwipeToDeleteRow(
                                onDelete = { pendingDelete = entry },
                                onClick = { detail = entry }
                            ) {
                                TeamRow(entry)
                            }
                        }
                    }
                }
            }
        }
    }

    detail?.let { entry ->
        TeamDetailSheet(
            entry = entry,
            onEdit = {
                viewModel.resetSaveState()
                editorTarget = EditorTarget(entry)
            },
            onDismiss = { detail = null }
        )
    }

    editorTarget?.let { target ->
        TeamEditorSheet(
            initial = target.entry,
            saveState = saveState,
            onSave = { form, originalName -> viewModel.save(form, originalName) },
            onSaved = {
                editorTarget = null
                viewModel.resetSaveState()
            },
            onDismiss = {
                editorTarget = null
                viewModel.resetSaveState()
            }
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(stringResource(R.string.team_delete_title)) },
            text = { Text(stringResource(R.string.team_delete_message, entry.team.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(entry)
                        pendingDelete = null
                    }
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 单个团队行：图标 + 名称/阶段摘要 + 右箭头。 */
@Composable
private fun TeamRow(entry: AgentTeamEntry) {
    val team = entry.team
    Row(
        modifier = Modifier
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
                imageVector = FeatherIcons.Users,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = team.name,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(R.string.team_stage_summary, team.stages.size, team.taskCount) +
                    " · " + team.stages.joinToString(" → ") { it.title },
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

/** 详情弹层：阶段与成员、预设文件路径、在对话里的触发方式。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TeamDetailSheet(entry: AgentTeamEntry, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val team = entry.team
    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = team.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (team.description.isNotBlank()) {
                Text(
                    text = team.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsGroup {
                team.stages.forEachIndexed { index, stage ->
                    if (index > 0) SettingsDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp)
                    ) {
                        Text(
                            text = "${index + 1}. ${stage.title}",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        stage.tasks.forEach { task ->
                            val member = task.agent
                                ?: listOfNotNull(task.department, task.agentQuery).joinToString("/")
                            Text(
                                text = "· $member：${task.prompt.lineSequence().first().take(60)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            team.filePath?.let { path ->
                SettingsGroup {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = 12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.team_detail_file),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            SettingsGroup {
                SettingsRow(
                    title = stringResource(R.string.team_editor_edit),
                    onClick = onEdit
                )
            }

            Text(
                text = stringResource(R.string.team_run_hint, team.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 编辑器目标：entry 为 null 表示新建，非 null 表示编辑已有预设。 */
private data class EditorTarget(val entry: AgentTeamEntry?)
