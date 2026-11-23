/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/screens/WorkflowDetailScreen.kt
 * 改动：
 * - 上游用 CustomScaffold + SpeedDial FAB 承担「加节点 / 编辑工作流 / 运行 / 日志 / 删除」六件事，
 *   这里把高频动作（保存、运行、加节点）摊在标题行，返回即保存；
 * - 校验结果从「弹窗报错」改成标题行的计数徽标 + 可点开的清单，保存照常成功（只诊断不阻断）；
 * - 节点/连线属性改用底栏，不再叠 AlertDialog；
 * - 上游的运行日志弹窗依赖它的执行记录模型，Aharou 的运行记录在 WorkflowStore 里按工作流落档，
 *   编辑器这里只回显本次运行结果，不重复造日志视图。
 */
package com.aharou.feature.plugin.workfloweditor

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.plugin.workflow.PlaceholderNode
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.ChevronLeft
import compose.icons.feathericons.Clock
import compose.icons.feathericons.Edit3
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Play
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Save

/**
 * 工作流编辑器。
 *
 * 进入即从 [com.aharou.feature.plugin.workflow.WorkflowStore] 载入；返回时先保存再退出，
 * 避免用户辛苦连好线却忘记点保存。
 */
@Composable
fun WorkflowEditorScreen(
    workflowId: String,
    onBack: () -> Unit,
    viewModel: WorkflowEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var addMenuOpen by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRuns by remember { mutableStateOf(false) }

    LaunchedEffect(workflowId) { viewModel.open(workflowId) }
    LaunchedEffect(showRuns) { if (showRuns) viewModel.loadRuns(workflowId) }

    val workflow = state.current
    val running = workflowId in state.runningIds
    val messageText = state.message?.let { workflowEditorMessageText(it) }
    LaunchedEffect(messageText) {
        if (messageText != null) {
            snackbarHostState.showSnackbar(messageText)
            viewModel.dismissMessage()
        }
    }

    val nodeKinds = listOf(
        WorkflowNodeKind.TRIGGER to stringResource(R.string.workflow_editor_node_trigger),
        WorkflowNodeKind.EXECUTE to stringResource(R.string.workflow_editor_node_execute),
        WorkflowNodeKind.CONDITION to stringResource(R.string.workflow_editor_node_condition),
        WorkflowNodeKind.LOGIC to stringResource(R.string.workflow_editor_node_logic),
        WorkflowNodeKind.EXTRACT to stringResource(R.string.workflow_editor_node_extract),
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            EditorTopBar(
                title = workflow?.name?.ifBlank { stringResource(R.string.workflow_editor_unnamed) }
                    ?: stringResource(R.string.workflow_editor_title),
                issueCount = state.issues.size,
                running = running,
                canRun = workflow != null,
                addMenuOpen = addMenuOpen,
                onBack = {
                    if (workflow == null) {
                        onBack()
                    } else {
                        viewModel.save { onBack() }
                    }
                },
                onSave = { viewModel.save() },
                onRun = { viewModel.trigger(workflowId) },
                onShowIssues = { showIssues = true },
                onToggleAddMenu = { addMenuOpen = !addMenuOpen },
                onDismissAddMenu = { addMenuOpen = false },
                overflowOpen = overflowOpen,
                onToggleOverflow = { overflowOpen = !overflowOpen },
                onDismissOverflow = { overflowOpen = false },
                onRename = {
                    overflowOpen = false
                    showRename = true
                },
                onShowRuns = {
                    overflowOpen = false
                    showRuns = true
                },
                nodeKinds = nodeKinds,
                onAddNode = { kind, label ->
                    addMenuOpen = false
                    viewModel.addNode(kind, label)
                },
            )

            when {
                state.loadingCurrent -> Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                workflow == null -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    Text(
                        text = stringResource(R.string.workflow_editor_not_found),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.workflow_editor_back))
                    }
                }
                else -> Box(modifier = Modifier.fillMaxSize()) {
                    WorkflowCanvas(
                        nodes = workflow.nodes,
                        connections = workflow.connections,
                        selectedNodeId = state.selectedNodeId,
                        selectedConnectionId = state.selectedConnectionId,
                        nodeStates = state.nodeStates,
                        onSelectNode = viewModel::selectNode,
                        onSelectConnection = viewModel::selectConnection,
                        onMoveNode = viewModel::moveNode,
                        onConnect = viewModel::addConnection,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (workflow.nodes.isEmpty()) {
                        Text(
                            text = stringResource(R.string.workflow_editor_canvas_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = Spacing.xxl),
                        )
                    }
                }
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.md),
        )
    }

    val selectedNode = workflow?.nodes?.firstOrNull { it.id == state.selectedNodeId }
    if (selectedNode != null && selectedNode !is PlaceholderNode) {
        WorkflowNodeEditorSheet(
            node = selectedNode,
            allNodes = workflow.nodes,
            toolNames = viewModel.toolNames,
            toolLookup = viewModel::toolOf,
            onDismiss = { viewModel.selectNode(null) },
            onSubmit = { updated ->
                viewModel.updateNode(updated)
                viewModel.selectNode(null)
            },
            onDelete = {
                viewModel.deleteNode(selectedNode.id)
                viewModel.selectNode(null)
            },
        )
    }

    val selectedConnection = workflow?.connections?.firstOrNull { it.id == state.selectedConnectionId }
    if (workflow != null && selectedConnection != null) {
        WorkflowConnectionEditorSheet(
            connection = selectedConnection,
            nodes = workflow.nodes,
            onDismiss = { viewModel.selectConnection(null) },
            onSubmit = { condition ->
                viewModel.updateConnectionCondition(selectedConnection.id, condition)
                viewModel.selectConnection(null)
            },
            onDelete = {
                viewModel.deleteConnection(selectedConnection.id)
                viewModel.selectConnection(null)
            },
        )
    }

    if (showRuns) {
        WorkflowRunsSheet(runs = state.runs, onDismiss = { showRuns = false })
    }

    if (showRename && workflow != null) {
        RenameWorkflowDialog(
            initialName = workflow.name,
            initialDescription = workflow.description,
            onDismiss = { showRename = false },
            onConfirm = { name, description ->
                showRename = false
                viewModel.renameWorkflow(name, description)
                viewModel.save()
            },
        )
    }

    if (showIssues) {
        AlertDialog(
            onDismissRequest = { showIssues = false },
            title = { Text(stringResource(R.string.workflow_editor_issues_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    if (state.issues.isEmpty()) {
                        Text(stringResource(R.string.workflow_editor_issues_empty))
                    } else {
                        state.issues.forEach { issue -> Text(issueText(issue)) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showIssues = false }) {
                    Text(stringResource(R.string.workflow_editor_close))
                }
            },
        )
    }
}

@Composable
private fun EditorTopBar(
    title: String,
    issueCount: Int,
    running: Boolean,
    canRun: Boolean,
    addMenuOpen: Boolean,
    overflowOpen: Boolean,
    nodeKinds: List<Pair<WorkflowNodeKind, String>>,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onRun: () -> Unit,
    onShowIssues: () -> Unit,
    onToggleAddMenu: () -> Unit,
    onDismissAddMenu: () -> Unit,
    onToggleOverflow: () -> Unit,
    onDismissOverflow: () -> Unit,
    onRename: () -> Unit,
    onShowRuns: () -> Unit,
    onAddNode: (WorkflowNodeKind, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    FeatherIcons.ChevronLeft,
                    contentDescription = stringResource(R.string.workflow_editor_back),
                    modifier = Modifier.size(22.dp),
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onSave) {
                Icon(
                    FeatherIcons.Save,
                    contentDescription = stringResource(R.string.workflow_editor_save),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRun, enabled = canRun && !running) {
                if (running) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else {
                    Icon(
                        FeatherIcons.Play,
                        contentDescription = stringResource(R.string.workflow_editor_run_once),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = onToggleAddMenu, enabled = canRun) {
                    Icon(
                        FeatherIcons.Plus,
                        contentDescription = stringResource(R.string.workflow_editor_add_node),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = addMenuOpen, onDismissRequest = onDismissAddMenu) {
                    nodeKinds.forEach { (kind, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = { onAddNode(kind, label) },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = onToggleOverflow, enabled = canRun) {
                    Icon(
                        FeatherIcons.MoreHorizontal,
                        contentDescription = stringResource(R.string.workflow_editor_more),
                        modifier = Modifier.size(20.dp),
                    )
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = onDismissOverflow) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workflow_editor_rename)) },
                        leadingIcon = {
                            Icon(FeatherIcons.Edit3, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        onClick = onRename,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.workflow_editor_runs_title)) },
                        leadingIcon = {
                            Icon(FeatherIcons.Clock, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        onClick = onShowRuns,
                    )
                }
            }
        }
        if (issueCount > 0) {
            Surface(
                modifier = Modifier
                    .padding(horizontal = Spacing.md)
                    .padding(bottom = Spacing.xs)
                    .fillMaxWidth()
                    .clickable(onClick = onShowIssues),
                shape = RoundedCornerShape(Radius.mdLarge),
                color = MaterialTheme.semanticColors.warningContainer,
                border = BorderStroke(1.dp, MaterialTheme.semanticColors.warning.copy(alpha = 0.4f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        FeatherIcons.AlertCircle,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.semanticColors.onWarningContainer,
                    )
                    Text(
                        text = stringResource(R.string.workflow_editor_issue_badge, issueCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.semanticColors.onWarningContainer,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = Spacing.sm),
                    )
                    Text(
                        text = stringResource(R.string.workflow_editor_issue_view),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.semanticColors.onWarningContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun issueText(issue: WorkflowIssue): String {
    val text = when (issue.code) {
        WorkflowIssueCode.NO_TRIGGER -> stringResource(R.string.workflow_editor_issue_no_trigger)
        WorkflowIssueCode.MULTIPLE_TRIGGERS -> stringResource(R.string.workflow_editor_issue_multiple_triggers)
        WorkflowIssueCode.DUPLICATE_NODE_ID -> stringResource(R.string.workflow_editor_issue_duplicate_id, issue.subject)
        WorkflowIssueCode.EMPTY_ACTION -> stringResource(R.string.workflow_editor_issue_empty_action, issue.subject)
        WorkflowIssueCode.DANGLING_CONNECTION -> stringResource(R.string.workflow_editor_issue_dangling_connection)
        WorkflowIssueCode.DANGLING_REFERENCE -> stringResource(R.string.workflow_editor_issue_dangling_reference, issue.subject)
        WorkflowIssueCode.CYCLE -> stringResource(R.string.workflow_editor_issue_cycle)
    }
    return "• $text"
}

/** 改名与描述：与上游 EditWorkflowDialog 对应，启用状态留在列表页的开关上，不在这里重复。 */
@Composable
private fun RenameWorkflowDialog(
    initialName: String,
    initialDescription: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var description by remember(initialDescription) { mutableStateOf(initialDescription) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workflow_editor_rename)) },
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
                Text(stringResource(R.string.workflow_editor_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.workflow_editor_cancel))
            }
        },
    )
}
