/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowListScreen.kt
 * 改动：
 * - 上游用 Material Scaffold + SpeedDial FAB + 多选批量删除；这里换成设置页的分组卡片语言，
 *   一行一个工作流，动作直接摊开（运行 / 编辑 / 删除），不藏进长按菜单；
 * - 不支持批量选择：工作流数量是个位数，多选模式的复杂度换不来收益；
 * - 启用开关直接对调度生效，手动触发带防重入。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.ExecutionStatus
import com.aharou.feature.plugin.workflow.Workflow
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.GitBranch
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 工作流列表。
 *
 * 自带 FAB 与新建弹窗；返回/标题由插件页宿主提供，避免出现两条标题栏。
 */
@Composable
fun WorkflowListScreen(
    onOpenWorkflow: (String) -> Unit,
    viewModel: WorkflowEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showCreate by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val timeFormatter = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    // 新建回调不是 Composable，标签必须先在这里取好。
    val manualTriggerLabel = stringResource(R.string.workflow_editor_trigger_manual)
    val snackbarHostState = remember { SnackbarHostState() }
    val messageText = state.message?.let { workflowEditorMessageText(it) }

    // 启用/运行的结果都要有回显：开关点下去没反应比失败更糟。
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (state.loadingList && state.workflows.isEmpty()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (state.workflows.isEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = stringResource(R.string.workflow_editor_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.workflow_editor_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.md,
                    end = Spacing.md,
                    top = Spacing.xs,
                    // 给 FAB 留出让位，最后一张卡片不会被压住。
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                item { SettingsGroupHeader(stringResource(R.string.workflow_editor_list_header)) }
                items(state.workflows, key = { it.id }) { workflow ->
                    WorkflowCard(
                        workflow = workflow,
                        running = workflow.id in state.runningIds,
                        lastRunText = workflow.lastExecutionTime
                            ?.let { timeFormatter.format(Date(it)) }
                            ?: stringResource(R.string.workflow_editor_never_run),
                        onOpen = { onOpenWorkflow(workflow.id) },
                        onToggle = { enabled -> viewModel.setEnabled(workflow.id, enabled) },
                        onRun = { viewModel.trigger(workflow.id) },
                        onDelete = { pendingDeleteId = workflow.id },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showCreate = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Icon(FeatherIcons.Plus, contentDescription = stringResource(R.string.workflow_editor_new))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.md),
        )
    }

    if (showCreate) {
        CreateWorkflowDialog(
            onDismiss = { showCreate = false },
            onConfirm = { name, description ->
                showCreate = false
                viewModel.createWorkflow(
                    name = name,
                    description = description,
                    triggerName = manualTriggerLabel,
                ) { id -> onOpenWorkflow(id) }
            },
        )
    }

    val deleteTarget = state.workflows.firstOrNull { it.id == pendingDeleteId }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text(stringResource(R.string.workflow_editor_delete_workflow)) },
            text = { Text(stringResource(R.string.workflow_editor_delete_workflow_confirm, deleteTarget.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteWorkflow(deleteTarget.id)
                        pendingDeleteId = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.workflow_editor_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text(stringResource(R.string.workflow_editor_cancel))
                }
            },
        )
    }
}

@Composable
private fun WorkflowCard(
    workflow: Workflow,
    running: Boolean,
    lastRunText: String,
    onOpen: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
) {
    val statusColor = when (workflow.lastExecutionStatus) {
        ExecutionStatus.SUCCESS -> MaterialTheme.semanticColors.success
        ExecutionStatus.FAILED -> MaterialTheme.colorScheme.error
        ExecutionStatus.RUNNING -> MaterialTheme.semanticColors.info
        null -> MaterialTheme.semanticColors.subtleText
    }
    val subtitle = stringResource(
        R.string.workflow_editor_card_subtitle,
        workflow.sourcePluginId ?: stringResource(R.string.workflow_editor_source_manual),
        lastRunText,
    )

    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.GitBranch,
            title = workflow.name.ifBlank { stringResource(R.string.workflow_editor_unnamed) },
            subtitle = subtitle,
            onClick = onOpen,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EditorStatusDot(color = statusColor, modifier = Modifier.padding(end = Spacing.sm))
                    AppSwitch(checked = workflow.enabled, onCheckedChange = onToggle)
                }
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onRun, enabled = !running) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        FeatherIcons.Play,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                    )
                }
                Text(
                    text = stringResource(
                        if (running) R.string.workflow_editor_running else R.string.workflow_editor_run_once
                    ),
                    modifier = Modifier.padding(start = Spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(onClick = onOpen) {
                Icon(FeatherIcons.Edit2, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(
                    text = stringResource(R.string.workflow_editor_open),
                    modifier = Modifier.padding(start = Spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(onClick = onDelete) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.workflow_editor_delete),
                    modifier = Modifier.padding(start = Spacing.xs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun CreateWorkflowDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, description: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_editor_new)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.workflow_editor_name),
                    singleLine = true,
                )
                AppTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = stringResource(R.string.workflow_editor_description),
                    singleLine = false,
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), description.trim()) },
                enabled = name.isNotBlank(),
            ) {
                Text(
                    text = stringResource(R.string.workflow_editor_create),
                    fontWeight = FontWeight.Medium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workflow_editor_cancel))
            }
        },
    )
}
