package com.aharou.feature.backup.presentation

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SegmentedTabs
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.ui.dialogTextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Spacing
import com.aharou.feature.backup.domain.BackupOptions
import com.aharou.feature.backup.domain.ProviderConflict
import com.aharou.feature.backup.domain.ProviderConflictStrategy
import com.aharou.feature.backup.domain.WorkspaceBackupMeta
import com.aharou.feature.backup.domain.remote.BackupTarget
import com.aharou.feature.backup.domain.remote.BackupTargetType
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsEmptyState
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Cloud
import compose.icons.feathericons.Download
import compose.icons.feathericons.Edit2
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Server
import compose.icons.feathericons.Upload
import compose.icons.feathericons.Zap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.stringResource
import com.aharou.R

@Composable
internal fun BackupSection(viewModel: BackupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var password by remember { mutableStateOf("") }
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var pendingExportPassword by remember { mutableStateOf("") }
    var pendingExportOptions by remember { mutableStateOf(BackupOptions()) }
    var tab by remember { mutableStateOf(0) }
    var showTargetSheet by remember { mutableStateOf(false) }
    var editingTarget by remember { mutableStateOf<BackupTarget?>(null) }
    var networkAction by remember { mutableStateOf<NetworkAction?>(null) }
    val exportOptions by viewModel.exportOptions.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val networkState by viewModel.networkState.collectAsStateWithLifecycle()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            val pw = pendingExportPassword
            val opts = pendingExportOptions
            scope.launch {
                val os = withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri) }
                if (os != null) {
                    viewModel.export(pw, opts, os)
                } else {
                    Toast.makeText(context, context.getString(R.string.backup_write_failed, uri.toString()), Toast.LENGTH_LONG).show()
                    viewModel.reset()
                }
            }
        } else {
            viewModel.reset()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pendingAction = null
            return@rememberLauncherForActivityResult
        }
        pendingImportUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SegmentedTabs(
            selected = tab,
            labels = listOf(
                stringResource(R.string.backup_net_tab_local),
                stringResource(R.string.backup_net_tab_network)
            ),
            onSelect = { tab = it }
        )
        Spacer(Modifier.height(Spacing.sm))

        if (tab == 0) {
        // 导出数据范围：开关即持久化
        SettingsGroupHeader(text = stringResource(R.string.backup_select_data))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.common_ai_providers),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.providers,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(providers = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_remote),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.remoteConnections,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(remoteConnections = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_chat_history),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.chatHistory,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(chatHistory = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_mcp),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.mcpServers,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(mcpServers = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_permissions),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.permissionRules,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(permissionRules = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_app_settings),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.appSettings,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(appSettings = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_workspace),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.workspaceFiles,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(workspaceFiles = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_memory),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.memoryAndSoul,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(memoryAndSoul = it)) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.backup_data_project_memory),
                trailing = {
                    AppSwitch(
                        checked = exportOptions.projectMemory,
                        onCheckedChange = { viewModel.updateExportOptions(exportOptions.copy(projectMemory = it)) }
                    )
                }
            )
            WorkspaceBackupHint()
        }

        SettingsGroupHeader(text = stringResource(R.string.backup_actions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.backup_export_title),
                subtitle = stringResource(R.string.backup_export_subtitle),
                enabled = state !is BackupState.Working,
                onClick = { pendingAction = PendingAction.ExportPassword }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Upload,
                title = stringResource(R.string.backup_import_title),
                subtitle = stringResource(R.string.backup_import_subtitle),
                enabled = state !is BackupState.Working,
                onClick = {
                    pendingAction = PendingAction.Import
                    importLauncher.launch(arrayOf("application/octet-stream", "application/gzip", "*/*"))
                }
            )
        }
        } else {
            NetworkBackupContent(
                targets = targets,
                onAdd = {
                    editingTarget = null
                    showTargetSheet = true
                },
                onEdit = {
                    editingTarget = it
                    showTargetSheet = true
                },
                onBrowse = { viewModel.loadRemoteBackups(it.id) },
                onTest = { viewModel.testTarget(it.id) },
                onDelete = { viewModel.deleteTarget(it.id) }
            )
        }
    }

    if (showTargetSheet) {
        BackupTargetSheet(
            initial = editingTarget,
            onDismiss = {
                showTargetSheet = false
                editingTarget = null
            },
            onSave = {
                viewModel.saveTarget(it)
                showTargetSheet = false
                editingTarget = null
            }
        )
    }

    if (networkAction != null) {
        val pendingAction = networkAction
        // 上传强制加密：快照里是解密后的 API Key 与 SSH 凭据，空口令等于把它们明文传到远端。
        // 恢复侧仍允许留空——未加密的旧备份要靠它才能还原。
        val requiresPassword = pendingAction is NetworkAction.Upload
        PasswordDialog(
            title = stringResource(R.string.backup_net_password_action),
            subtitle = stringResource(
                if (requiresPassword) R.string.backup_net_upload_password_hint
                else R.string.backup_password_optional_hint
            ),
            confirmText = stringResource(R.string.common_ok),
            confirmEnabled = !requiresPassword || password.isNotEmpty(),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                val action = networkAction
                val pw = password
                password = ""
                networkAction = null
                when (action) {
                    is NetworkAction.Upload -> viewModel.uploadToTarget(action.targetId, pw)
                    is NetworkAction.Restore -> viewModel.restoreFromRemote(action.targetId, action.name, pw)
                    null -> {}
                }
            },
            onDismiss = {
                password = ""
                networkAction = null
            }
        )
    }

    LaunchedEffect(networkState) {
        val notice = networkState as? NetworkBackupState.Notice ?: return@LaunchedEffect
        Toast.makeText(context, context.getString(notice.messageRes), Toast.LENGTH_LONG).show()
        viewModel.dismissNetworkState()
    }

    val remoteList = networkState as? NetworkBackupState.RemoteList
    if (remoteList != null) {
        RemoteBackupDialog(
            targetName = remoteList.targetName,
            items = remoteList.items,
            busy = false,
            onUpload = { networkAction = NetworkAction.Upload(remoteList.targetId) },
            onRefresh = { viewModel.loadRemoteBackups(remoteList.targetId) },
            onRestore = { networkAction = NetworkAction.Restore(remoteList.targetId, it.name) },
            onDelete = { viewModel.deleteRemoteBackup(remoteList.targetId, it.name) },
            onDismiss = { viewModel.dismissNetworkState() }
        )
    }

    // 导出：输口令（可留空）
    if (pendingAction == PendingAction.ExportPassword) {
        PasswordDialog(
            title = stringResource(R.string.backup_set_password),
            subtitle = stringResource(R.string.backup_password_hint),
            confirmText = stringResource(R.string.backup_export_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                pendingExportPassword = password
                pendingExportOptions = exportOptions
                password = ""
                pendingAction = null
                exportLauncher.launch("aharou-backup-${System.currentTimeMillis()}.tar.gz")
            },
            onDismiss = {
                password = ""
                pendingAction = null
            }
        )
    }

    // 导入口令弹窗（SAF 选完文件后弹出）
    if (pendingAction == PendingAction.Import && pendingImportUri != null) {
        PasswordDialog(
            title = stringResource(R.string.backup_password_input),
            subtitle = stringResource(R.string.backup_password_optional_hint),
            confirmText = stringResource(R.string.backup_import_btn),
            password = password,
            onPasswordChange = { password = it },
            onConfirm = {
                val pw = password
                val uri = pendingImportUri
                password = ""
                pendingAction = null
                pendingImportUri = null
                if (uri != null) viewModel.import(uri, pw)
            },
            onDismiss = {
                password = ""
                pendingAction = null
                pendingImportUri = null
            }
        )
    }

    // 导出完成 → 提示并复位
    LaunchedEffect(state) {
        if (state is BackupState.ExportDone) {
            Toast.makeText(context, context.getString(R.string.backup_exported), Toast.LENGTH_SHORT).show()
            viewModel.reset()
        }
    }

    if (state is BackupState.Working || networkState is NetworkBackupState.Working) {
        ProgressDialog()
    }

    when (state) {
        is BackupState.Error -> ResultDialog(
            title = stringResource(R.string.backup_operation_failed),
            message = (state as BackupState.Error).message,
            onDismiss = { viewModel.reset() }
        )
        is BackupState.ImportSuccess -> ResultDialog(
            title = stringResource(R.string.backup_import_done),
            message = buildImportSummary(context, (state as BackupState.ImportSuccess).stats),
            onDismiss = { viewModel.reset() }
        )
        is BackupState.WorkspaceSelection -> WorkspaceRestoreDialog(
            workspaces = (state as BackupState.WorkspaceSelection).workspaces,
            providerConflicts = (state as BackupState.WorkspaceSelection).providerConflicts,
            onConfirm = { selected, strategy -> viewModel.confirmImportSelection(selected, strategy) },
            onDismiss = { viewModel.cancelImportSelection() }
        )
        else -> {}
    }
}

private enum class PendingAction { ExportPassword, Import }

@Composable
private fun WorkspaceBackupHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(Spacing.xs))
        Text(
            text = stringResource(R.string.backup_workspace_git_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkspaceRestoreDialog(
    workspaces: List<WorkspaceBackupMeta>,
    providerConflicts: List<ProviderConflict>,
    onConfirm: (Set<String>, ProviderConflictStrategy) -> Unit,
    onDismiss: () -> Unit
) {
    var checked by remember { mutableStateOf(workspaces.associate { it.name to true }) }
    var strategy by remember { mutableStateOf(ProviderConflictStrategy.OVERWRITE) }
    val anyChecked = checked.values.any { it }
    val allChecked = workspaces.isNotEmpty() && checked.values.all { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_workspaces_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (workspaces.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.backup_restore_workspaces_warning),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Checkbox(
                            checked = allChecked,
                            onCheckedChange = { v -> checked = workspaces.associate { it.name to v } }
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_workspaces_select_all),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    workspaces.forEach { ws ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Checkbox(
                                checked = checked[ws.name] ?: false,
                                onCheckedChange = { v -> checked = checked + (ws.name to v) }
                            )
                            Text(
                                text = ws.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(R.string.backup_restore_workspaces_files, ws.fileCount),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                if (providerConflicts.isNotEmpty()) {
                    if (workspaces.isNotEmpty()) Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = stringResource(R.string.backup_conflict_title),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    providerConflicts.forEach { conflict ->
                        Text(
                            text = "· ${conflict.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(Spacing.sm))
                    ProviderConflictOption(ProviderConflictStrategy.KEEP_LOCAL, strategy) { strategy = it }
                    ProviderConflictOption(ProviderConflictStrategy.OVERWRITE, strategy) { strategy = it }
                    ProviderConflictOption(ProviderConflictStrategy.KEEP_BOTH, strategy) { strategy = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(checked.filterValues { it }.keys, strategy) },
                enabled = workspaces.isEmpty() || anyChecked
            ) {
                Text(stringResource(R.string.backup_restore_workspaces_confirm))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun ProviderConflictOption(
    value: ProviderConflictStrategy,
    selected: ProviderConflictStrategy,
    onSelect: (ProviderConflictStrategy) -> Unit
) {
    val label = when (value) {
        ProviderConflictStrategy.KEEP_LOCAL -> stringResource(R.string.backup_conflict_keep_local)
        ProviderConflictStrategy.OVERWRITE -> stringResource(R.string.backup_conflict_overwrite)
        ProviderConflictStrategy.KEEP_BOTH -> stringResource(R.string.backup_conflict_keep_both)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun PasswordDialog(
    title: String,
    subtitle: String,
    confirmText: String,
    confirmEnabled: Boolean = true,
    password: String,
    onPasswordChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(Spacing.sm))
                AppTextField(
                    value = password,
                    onValueChange = onPasswordChange,
                    label = stringResource(R.string.backup_password_label),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = dialogTextFieldColors()
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun ProgressDialog() {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.backup_processing)) },
        text = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.backup_processing_data))
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun ResultDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_got_it)) } }
    )
}

private fun buildImportSummary(context: android.content.Context, stats: com.aharou.feature.backup.domain.RestoreStats): String = buildString {
    appendLine(context.getString(R.string.backup_restored_data))
    if (stats.providers > 0) appendLine(context.getString(R.string.backup_stat_providers, stats.providers))
    if (stats.remoteConnections > 0) appendLine(context.getString(R.string.backup_stat_remote_connections, stats.remoteConnections))
    if (stats.remoteMounts > 0) appendLine(context.getString(R.string.backup_stat_remote_mounts, stats.remoteMounts))
    if (stats.chatSessions > 0) appendLine(context.getString(R.string.backup_stat_chat_sessions, stats.chatSessions))
    if (stats.agentMessages > 0) appendLine(context.getString(R.string.backup_stat_chat_messages, stats.agentMessages))
    if (stats.todoItems > 0) appendLine(context.getString(R.string.backup_stat_todo_items, stats.todoItems))
    if (stats.mcpServers > 0) appendLine(context.getString(R.string.backup_stat_mcp_servers, stats.mcpServers))
    if (stats.globalPermissionRules > 0) appendLine(context.getString(R.string.backup_stat_permission_rules, stats.globalPermissionRules))
    if (stats.workspaceFiles > 0) appendLine(context.getString(R.string.backup_stat_workspace_files, stats.workspaceFiles))
    if (stats.memoryFiles > 0) appendLine(context.getString(R.string.backup_stat_memory_files, stats.memoryFiles))
    if (stats.projectMemoryFiles > 0) appendLine(context.getString(R.string.backup_stat_project_memory_files, stats.projectMemoryFiles))
    if (stats.appSettingsRestored) append(context.getString(R.string.backup_settings_covered))
}

/** 网络 tab 的内容：目标列表（左滑删除）+ 添加入口。 */
@Composable
private fun NetworkBackupContent(
    targets: List<BackupTarget>,
    onAdd: () -> Unit,
    onEdit: (BackupTarget) -> Unit,
    onBrowse: (BackupTarget) -> Unit,
    onTest: (BackupTarget) -> Unit,
    onDelete: (BackupTarget) -> Unit
) {
    SettingsGroupHeader(text = stringResource(R.string.backup_net_targets_header))
    SettingsGroup {
        if (targets.isEmpty()) {
            // 空态改用统一组件（图标底板 + 标题），与凭据页、技能页保持同一套样式。
            SettingsEmptyState(
                icon = FeatherIcons.Cloud,
                title = stringResource(R.string.backup_net_empty)
            )
            SettingsDivider()
        } else {
            targets.forEachIndexed { index, target ->
                if (index > 0) SettingsDivider()
                SwipeToDeleteRow(onDelete = { onDelete(target) }) {
                    SettingsRow(
                        icon = if (target.type == BackupTargetType.S3) FeatherIcons.Cloud else FeatherIcons.Server,
                        title = target.name,
                        subtitle = targetSubtitle(target),
                        onClick = { onBrowse(target) },
                        trailing = {
                            IconButton(onClick = { onTest(target) }) {
                                Icon(FeatherIcons.Zap, contentDescription = stringResource(R.string.backup_net_test))
                            }
                            IconButton(onClick = { onEdit(target) }) {
                                Icon(FeatherIcons.Edit2, contentDescription = stringResource(R.string.common_edit))
                            }
                        }
                    )
                }
            }
            SettingsDivider()
        }
        SettingsRow(
            icon = FeatherIcons.Plus,
            title = stringResource(R.string.backup_net_add_target),
            onClick = onAdd
        )
    }
}

/** 目标副标题：WebDAV 显示服务地址，S3 显示桶名与区域（纯数据，不是需要翻译的文案）。 */
private fun targetSubtitle(target: BackupTarget): String = when (target.type) {
    BackupTargetType.WEBDAV -> target.endpoint
    BackupTargetType.S3 -> listOf(target.bucket, target.region.ifBlank { "us-east-1" })
        .filter { it.isNotBlank() }
        .joinToString(" · ")
}

/** 网络 tab 里需要口令确认的两类动作。 */
private sealed interface NetworkAction {
    data class Upload(val targetId: String) : NetworkAction
    data class Restore(val targetId: String, val name: String) : NetworkAction
}
