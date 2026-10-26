package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamForm
import com.aharou.feature.agent.domain.subagent.AgentTeamSaveError
import com.aharou.feature.agent.domain.subagent.AgentTeamStageForm
import com.aharou.feature.agent.domain.subagent.AgentTeamTaskForm
import com.aharou.feature.agent.domain.subagent.toForm
import com.aharou.feature.settings.presentation.AgentTeamSaveState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/**
 * 团队预设编辑器：名称、作用域、阶段（可增删，阶段内任务也可增删）、团队公约。
 *
 * 保存后写回 `teams/<name>.md`，与手写的预设完全等价——解析器与序列化器成对，
 * 写完再读不会变形。
 *
 * @param initial 编辑目标；null 表示新建。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TeamEditorSheet(
    initial: AgentTeamEntry?,
    saveState: AgentTeamSaveState,
    onSave: (AgentTeamForm, String?) -> Unit,
    onSaved: () -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable { mutableStateOf(initial?.team?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(initial?.team?.description.orEmpty()) }
    var charter by rememberSaveable { mutableStateOf(initial?.team?.charter.orEmpty()) }
    var scopeIsGlobal by rememberSaveable {
        mutableStateOf(initial?.scope?.let { it == AgentDefinitionScope.GLOBAL } ?: true)
    }
    var stages by remember(initial) {
        mutableStateOf(
            initial?.let { entry -> entry.team.toForm(entry.scope).stages }
                ?: listOf(AgentTeamStageForm())
        )
    }

    LaunchedEffect(saveState) {
        if (saveState is AgentTeamSaveState.Saved) onSaved()
    }

    val canSave = name.isNotBlank() && stages.any { it.tasks.isNotEmpty() }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = stringResource(
                    if (initial == null) R.string.team_editor_title_new else R.string.team_editor_title_edit
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            (saveState as? AgentTeamSaveState.Failed)?.let { failed ->
                Text(
                    text = stringResource(failed.error.messageRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    AppTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = stringResource(R.string.common_name),
                        singleLine = true
                    )
                    AppTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = stringResource(R.string.subagent_editor_description),
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        Text(
                            text = stringResource(R.string.subagent_editor_scope),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 作用域就是文件所在目录：编辑时换目录等于搬文件，和子代理定义同一套处理。
                        FilterChip(
                            selected = !scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = false },
                            label = { Text(stringResource(R.string.subagent_scope_project)) }
                        )
                        FilterChip(
                            selected = scopeIsGlobal,
                            enabled = initial == null,
                            onClick = { scopeIsGlobal = true },
                            label = { Text(stringResource(R.string.subagent_scope_global)) }
                        )
                    }
                }
            }

            SettingsGroupHeader(text = stringResource(R.string.team_editor_stages))
            stages.forEachIndexed { stageIndex, stage ->
                StageEditor(
                    index = stageIndex,
                    stage = stage,
                    onChange = { updated ->
                        stages = stages.toMutableList().also { it[stageIndex] = updated }
                    },
                    onRemove = {
                        stages = stages.filterIndexed { index, _ -> index != stageIndex }
                    }
                )
            }
            TextButton(onClick = { stages = stages + AgentTeamStageForm() }) {
                Icon(imageVector = FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.team_editor_add_stage))
            }

            SettingsGroupHeader(text = stringResource(R.string.team_editor_charter))
            SettingsGroup {
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
                ) {
                    AppTextField(
                        value = charter,
                        onValueChange = { charter = it },
                        label = stringResource(R.string.team_editor_charter),
                        placeholder = stringResource(R.string.team_editor_charter_hint),
                        singleLine = false,
                        minLines = 3,
                        maxLines = 8
                    )
                }
            }

            Button(
                onClick = {
                    onSave(
                        AgentTeamForm(
                            name = name.trim(),
                            description = description.trim(),
                            scope = if (scopeIsGlobal) AgentDefinitionScope.GLOBAL else AgentDefinitionScope.PROJECT,
                            charter = charter.trim(),
                            stages = stages
                        ),
                        initial?.team?.name
                    )
                },
                enabled = canSave && saveState !is AgentTeamSaveState.Saving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (saveState is AgentTeamSaveState.Saving) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.common_save))
                }
            }
        }
    }
}

/** 一个阶段的编辑块：阶段名 + 任务列表 + 添加任务。 */
@Composable
private fun StageEditor(
    index: Int,
    stage: AgentTeamStageForm,
    onChange: (AgentTeamStageForm) -> Unit,
    onRemove: () -> Unit
) {
    SettingsGroup {
        Column(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppTextField(
                    value = stage.title,
                    onValueChange = { onChange(stage.copy(title = it)) },
                    label = stringResource(R.string.team_editor_stage_title),
                    placeholder = stringResource(R.string.team_editor_stage_title_hint, index + 1),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.team_editor_remove_stage),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            stage.tasks.forEachIndexed { taskIndex, task ->
                if (taskIndex > 0) SettingsDivider()
                TaskEditor(
                    task = task,
                    canRemove = stage.tasks.size > 1,
                    onChange = { updated ->
                        val tasks = stage.tasks.toMutableList().also { it[taskIndex] = updated }
                        onChange(stage.copy(tasks = tasks))
                    },
                    onRemove = {
                        onChange(stage.copy(tasks = stage.tasks.filterIndexed { i, _ -> i != taskIndex }))
                    }
                )
            }

            TextButton(onClick = { onChange(stage.copy(tasks = stage.tasks + AgentTeamTaskForm())) }) {
                Icon(imageVector = FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.team_editor_add_task))
            }
        }
    }
}

/** 一个任务的编辑块：角色名或部门 + 关键词，加任务说明与写入路径。 */
@Composable
private fun TaskEditor(
    task: AgentTeamTaskForm,
    canRemove: Boolean,
    onChange: (AgentTeamTaskForm) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        AppTextField(
            value = task.agent,
            onValueChange = { onChange(task.copy(agent = it)) },
            label = stringResource(R.string.team_editor_task_agent),
            placeholder = stringResource(R.string.team_editor_task_agent_hint),
            singleLine = true
        )
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            AppTextField(
                value = task.department,
                onValueChange = { onChange(task.copy(department = it)) },
                label = stringResource(R.string.team_editor_task_department),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = task.agentQuery,
                onValueChange = { onChange(task.copy(agentQuery = it)) },
                label = stringResource(R.string.team_editor_task_query),
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
        }
        AppTextField(
            value = task.prompt,
            onValueChange = { onChange(task.copy(prompt = it)) },
            label = stringResource(R.string.team_editor_task_prompt),
            singleLine = false,
            minLines = 2,
            maxLines = 5
        )
        AppTextField(
            value = task.writePaths,
            onValueChange = { onChange(task.copy(writePaths = it)) },
            label = stringResource(R.string.team_editor_task_write_paths),
            placeholder = stringResource(R.string.team_editor_task_write_paths_hint),
            singleLine = true
        )
        if (canRemove) {
            TextButton(onClick = onRemove) {
                Text(
                    text = stringResource(R.string.team_editor_remove_task),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun AgentTeamSaveError.messageRes(): Int = when (this) {
    AgentTeamSaveError.INVALID_NAME -> R.string.team_editor_error_invalid_name
    AgentTeamSaveError.EMPTY_STAGES -> R.string.team_editor_error_empty_stages
    AgentTeamSaveError.INCOMPLETE_TASK -> R.string.team_editor_error_incomplete_task
    AgentTeamSaveError.NAME_CONFLICT -> R.string.team_editor_error_name_conflict
    AgentTeamSaveError.IO_FAILED -> R.string.team_editor_error_io
}
