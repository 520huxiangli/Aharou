package com.aharou.feature.workspace.presentation.component

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.domain.model.WorkspaceType
import com.aharou.feature.workspace.presentation.WorkspaceViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Folder
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronUp
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2
import androidx.compose.ui.res.stringResource
import android.widget.Toast
import com.aharou.R

@Composable
private fun WorkspaceRow(
    workspace: Workspace,
    selected: Boolean,
    canDelete: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit
) {
    val external = workspace.type == WorkspaceType.EXTERNAL_LOCAL
    val disabled = !workspace.available
    val displayPath = when (workspace.type) {
        WorkspaceType.INTERNAL -> "projects/${workspace.name}"
        WorkspaceType.EXTERNAL_LOCAL, WorkspaceType.REMOTE -> workspace.path
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (external) FeatherIcons.HardDrive else FeatherIcons.Folder,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = workspace.name,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = displayPath,
                style = MaterialTheme.typography.bodySmall,
                color = when {
                    disabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    selected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (external && disabled) {
                Text(
                    text = stringResource(R.string.workspace_external_unavailable_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .clickable(onClick = onRename),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                FeatherIcons.Edit2,
                contentDescription = stringResource(R.string.common_rename),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        if (canDelete) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    FeatherIcons.Trash2,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/** 新建 / 重命名 / 删除三个确认弹窗：侧栏顶部区块与其它入口共用。 */
@Composable
private fun WorkspaceConfirmDialogs(
    workspaces: List<Workspace>,
    current: Workspace?,
    createTitle: String,
    showCreate: Boolean,
    onShowCreateChange: (Boolean) -> Unit,
    onCreate: (String) -> Unit,
    pendingDelete: Workspace?,
    onPendingDeleteChange: (Workspace?) -> Unit,
    onDelete: (Workspace) -> Unit,
    pendingRename: Workspace?,
    onPendingRenameChange: (Workspace?) -> Unit,
    onRename: (Workspace, String) -> Unit,
    deleteExternalWorkspaceSessions: Boolean,
    onStopCurrentSessions: () -> Unit
) {
    if (showCreate) {
        CreateWorkspaceDialog(
            title = createTitle,
            existingNames = workspaces.map { it.name },
            onConfirm = {
                onCreate(it)
                onShowCreateChange(false)
            },
            onDismiss = { onShowCreateChange(false) }
        )
    }

    pendingDelete?.let { ws ->
        AlertDialog(
            onDismissRequest = { onPendingDeleteChange(null) },
            title = { Text(stringResource(R.string.workspace_delete)) },
            text = {
                Text(
                    if (ws.type == WorkspaceType.EXTERNAL_LOCAL) {
                        stringResource(
                            if (deleteExternalWorkspaceSessions) R.string.workspace_external_remove_confirm_sessions
                            else R.string.workspace_external_remove_confirm,
                            ws.name
                        )
                    } else {
                        stringResource(R.string.workspace_delete_confirm, ws.name)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (ws.name == current?.name) onStopCurrentSessions()
                    onDelete(ws)
                    onPendingDeleteChange(null)
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { onPendingDeleteChange(null) }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    pendingRename?.let { ws ->
        RenameWorkspaceDialog(
            workspace = ws,
            existingNames = workspaces.map { it.name },
            onConfirm = { newName ->
                // 改的正是当前工作区时，先按切换工作区的同一套处理停掉会话与终端，
                // 否则旧 proot 里还绑着旧路径。
                if (ws.name == current?.name) onStopCurrentSessions()
                onRename(ws, newName)
                onPendingRenameChange(null)
            },
            onDismiss = { onPendingRenameChange(null) }
        )
    }
}

@Composable
private fun CreateWorkspaceDialog(
    title: String,
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !duplicate

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = stringResource(R.string.common_name),
                    placeholder = stringResource(R.string.workspace_name_hint),
                    isError = duplicate,
                    colors = dialogTextFieldColors()
                )
                if (duplicate) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.workspace_name_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

@Composable
private fun RenameWorkspaceDialog(
    workspace: Workspace,
    existingNames: List<String>,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(workspace.name) }
    val trimmed = name.trim()
    val duplicate = existingNames.any { it != workspace.name && it.equals(trimmed, ignoreCase = true) }
    val canConfirm = trimmed.isNotEmpty() && !duplicate && trimmed != workspace.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_rename_title)) },
        text = {
            Column {
                AppTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = stringResource(R.string.common_name),
                    placeholder = stringResource(R.string.workspace_name_hint),
                    isError = duplicate,
                    colors = dialogTextFieldColors()
                )
                if (duplicate) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.workspace_name_exists),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (workspace.type == WorkspaceType.EXTERNAL_LOCAL) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        stringResource(R.string.workspace_rename_external_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = canConfirm, onClick = { onConfirm(trimmed) }) {
                Text(stringResource(R.string.common_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/**
 * 侧栏顶部的工作区切换：折叠时一行显示当前工作区，展开后就是完整列表。
 *
 * 条目与确认弹窗和底部弹层共用，只是把常驻位置换到侧栏顶部，不必每次先点图标。
 */
@Composable
fun WorkspaceDrawerSection(
    viewModel: WorkspaceViewModel,
    onStopCurrentSessions: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val isLocalMode by viewModel.isLocalMode.collectAsStateWithLifecycle()
    val addError by viewModel.addError.collectAsStateWithLifecycle()
    val externalWarningDismissed by viewModel.externalWarningDismissed.collectAsStateWithLifecycle()
    val deleteExternalWorkspaceSessions by viewModel.deleteExternalWorkspaceSessions.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Workspace?>(null) }
    var pendingRename by remember { mutableStateOf<Workspace?>(null) }
    var showExternalWarning by remember { mutableStateOf(false) }
    var dontShowExternalWarningAgain by remember { mutableStateOf(false) }
    val createWorkspaceLabel = stringResource(
        if (isLocalMode) R.string.workspace_new_internal else R.string.workspace_new_workspace
    )

    LaunchedEffect(Unit) { viewModel.refreshAvailability() }

    // 添加外部本地工作区失败提示：消费后清除，避免重复弹 Toast
    LaunchedEffect(addError) {
        if (addError != null) {
            Toast.makeText(context, addError, Toast.LENGTH_LONG).show()
            viewModel.consumeAddError()
        }
    }

    // 系统目录选择器：选设备上的目录注册为外部本地工作区
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.addExternalWorkspace(uri) { created ->
                if (created != null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.workspace_external_added, created.name),
                        Toast.LENGTH_LONG
                    ).show()
                    viewModel.selectWorkspace(created.name)
                    expanded = false
                }
            }
        }
    }

    Surface(
        color = MaterialTheme.semanticColors.cardSurface,
        shape = RoundedCornerShape(Radius.mdLarge),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    FeatherIcons.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = stringResource(R.string.common_workspace),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (expanded) FeatherIcons.ChevronUp else FeatherIcons.ChevronDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            if (expanded) {
                workspaces.forEach { ws ->
                    WorkspaceRow(
                        workspace = ws,
                        selected = ws.name == current?.name,
                        canDelete = workspaces.size > 1,
                        onClick = {
                            viewModel.selectWorkspace(ws.name)
                            expanded = false
                        },
                        onDelete = { pendingDelete = ws },
                        onRename = { pendingRename = ws }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLocalMode) {
                        TextButton(onClick = {
                            if (externalWarningDismissed) folderLauncher.launch(null)
                            else showExternalWarning = true
                        }) {
                            Icon(FeatherIcons.HardDrive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(Spacing.xs))
                            Text(
                                stringResource(R.string.workspace_add_external),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    TextButton(onClick = { showCreateDialog = true }) {
                        Icon(FeatherIcons.Plus, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(Spacing.xs))
                        Text(createWorkspaceLabel, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }

    WorkspaceConfirmDialogs(
        workspaces = workspaces,
        current = current,
        createTitle = createWorkspaceLabel,
        showCreate = showCreateDialog,
        onShowCreateChange = { showCreateDialog = it },
        onCreate = { name ->
            viewModel.createWorkspace(name) { created ->
                if (created != null) {
                    viewModel.selectWorkspace(created.name)
                    expanded = false
                }
            }
        },
        pendingDelete = pendingDelete,
        onPendingDeleteChange = { pendingDelete = it },
        onDelete = { viewModel.deleteWorkspace(it.name) },
        pendingRename = pendingRename,
        onPendingRenameChange = { pendingRename = it },
        onRename = { ws, newName ->
            viewModel.renameWorkspace(ws.name, newName) { renamed ->
                if (renamed == null) {
                    // 具体原因由 repository 给出（名称非法 / 重名 / 目录状态 / 迁移失败），
                    // 拿不到时才退回笼统文案。
                    val message = viewModel.renameError()
                        ?: context.getString(R.string.workspace_rename_failed)
                    viewModel.consumeRenameError()
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            }
        },
        deleteExternalWorkspaceSessions = deleteExternalWorkspaceSessions,
        onStopCurrentSessions = onStopCurrentSessions
    )

    if (showExternalWarning) {
        AlertDialog(
            onDismissRequest = { showExternalWarning = false },
            title = { Text(stringResource(R.string.workspace_external_warning_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.workspace_external_warning_message))
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dontShowExternalWarningAgain = !dontShowExternalWarningAgain },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = dontShowExternalWarningAgain,
                            onCheckedChange = { dontShowExternalWarningAgain = it }
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text(stringResource(R.string.workspace_external_warning_dont_show_again))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (dontShowExternalWarningAgain) viewModel.setExternalWarningDismissed(true)
                    showExternalWarning = false
                    folderLauncher.launch(null)
                }) { Text(stringResource(R.string.workspace_external_warning_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { showExternalWarning = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
