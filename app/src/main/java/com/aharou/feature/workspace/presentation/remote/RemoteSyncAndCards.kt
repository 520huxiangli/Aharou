package com.aharou.feature.workspace.presentation.remote

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import com.aharou.core.ui.AppSwitch
import androidx.compose.material3.Text
import com.aharou.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.core.theme.Radius
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.settingsLightMode
import com.aharou.feature.workspace.domain.model.RemoteConnection
import com.aharou.feature.workspace.domain.model.RemoteMount
import com.aharou.feature.workspace.domain.model.RemoteProtocol
import com.aharou.feature.workspace.domain.model.SyncConnectionState
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronRight
import compose.icons.feathericons.Folder
import compose.icons.feathericons.MoreHorizontal
import compose.icons.feathericons.Play
import compose.icons.feathericons.Server

/** 同步设置底部弹窗（右上角齿轮打开）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncSettingsSheet(
    useGitIgnore: Boolean,
    maxSyncBatchSize: Int,
    onUseGitIgnoreChange: (Boolean) -> Unit,
    onMaxSyncBatchSizeChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var maxBatchSizeText by remember(maxSyncBatchSize) { mutableStateOf(maxSyncBatchSize.toString()) }
    var editUseGitIgnore by remember(useGitIgnore) { mutableStateOf(useGitIgnore) }
    val context = androidx.compose.ui.platform.LocalContext.current

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.sync_settings_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            SettingsGroup {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg, vertical = 11.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.sync_follow_gitignore),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.sync_gitignore_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    AppSwitch(
                        checked = editUseGitIgnore,
                        onCheckedChange = { editUseGitIgnore = it }
                    )
                }
                SettingsDivider()
                Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)) {
                    Text(
                        text = stringResource(R.string.sync_max_batch_size),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(R.string.sync_batch_size_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    AppTextField(
                        value = maxBatchSizeText,
                        onValueChange = { maxBatchSizeText = it.filter { char -> char.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.sync_max_batch_count),
                        singleLine = true
                    )
                }
            }
            Spacer(modifier = Modifier.height(Spacing.md))
            Button(
                onClick = {
                    onUseGitIgnoreChange(editUseGitIgnore)
                    onMaxSyncBatchSizeChange(maxBatchSizeText.toIntOrNull() ?: 50)
                    android.widget.Toast.makeText(context, context.getString(R.string.sync_saved), android.widget.Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.sync_save_settings))
            }
        }
    }
}

/** 行内通用编辑箭头：与 MCP/设置列表一致的大于号，整行点击进入编辑。 */
@Composable
private fun EditRowButton(onEdit: () -> Unit) {
    IconButton(onClick = onEdit) {
        Icon(
            imageVector = FeatherIcons.ChevronRight,
            contentDescription = stringResource(R.string.common_edit),
            tint = MaterialTheme.semanticColors.subtleText,
            modifier = Modifier.size(18.dp)
        )
    }
}

/** 行内通用图标方块：40dp 圆角浅底容器 + 居中 22dp 线条图标。 */
@Composable
private fun RowIconBox(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(22.dp)
                .align(Alignment.Center)
        )
    }
}

/** 协议徽章：SFTP/FTP 用不同颜色区分（样式同容器镜像来源徽章）。 */
@Composable
private fun ProtocolBadge(protocol: RemoteProtocol) {
    val color = when (protocol) {
        RemoteProtocol.SFTP -> MaterialTheme.colorScheme.primary
        RemoteProtocol.FTP -> MaterialTheme.colorScheme.tertiary
    }
    Box(
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(Radius.pill))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = protocol.name,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

/** 连接通道行：服务器图标 + 名称（协议徽章）+ 地址副标题 + 右侧箭头，整行点击编辑，左滑删除。 */
@Composable
fun RemoteConnectionCard(
    conn: RemoteConnection,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit
) {
    SwipeToDeleteRow(
        onDelete = { onDelete(conn) },
        onClick = { onEdit(conn) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowIconBox(FeatherIcons.Server)
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conn.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    ProtocolBadge(conn.protocol)
                }
                Text(
                    text = "${conn.username}@${conn.host}:${conn.port}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            EditRowButton(onEdit = { onEdit(conn) })
        }
    }
}

/** 挂载行：图标方块 + 通道名 + 状态徽章 + 路径副标题 + 右侧编辑，底部连接/同步操作区，左滑删除。 */
@Composable
fun RemoteMountCard(
    mount: RemoteMount,
    isFailed: Boolean = false,
    onEdit: (RemoteMount) -> Unit,
    onDelete: (RemoteMount) -> Unit,
    onUpload: (RemoteMount) -> Unit,
    onUploadFull: (RemoteMount) -> Unit,
    onClearIndex: (RemoteMount) -> Unit,
    onDownload: (RemoteMount) -> Unit,
    onConnect: (RemoteMount) -> Unit,
    onDisconnect: (RemoteMount) -> Unit
) {
    var actionsExpanded by remember { mutableStateOf(false) }
    SwipeToDeleteRow(onDelete = { onDelete(mount) }) {
        Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RowIconBox(FeatherIcons.Folder)
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = mount.connection?.name ?: stringResource(R.string.sync_unknown_connection),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    val (statusText, statusColor) = when {
                        mount.connectionState == SyncConnectionState.RECONNECTING ->
                            stringResource(R.string.status_reconnecting) to MaterialTheme.semanticColors.warning
                        mount.isActive -> stringResource(R.string.status_connected) to MaterialTheme.semanticColors.success
                        isFailed -> stringResource(R.string.status_connection_failed) to MaterialTheme.colorScheme.error
                        else -> stringResource(R.string.status_disconnected) to MaterialTheme.semanticColors.warning
                    }
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Surface(
                        shape = RoundedCornerShape(Radius.pill),
                        color = statusColor.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.sync_path_mapping, mount.localMountPath, mount.remotePath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            EditRowButton(onEdit = { onEdit(mount) })
        }

            SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.sm, bottom = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (mount.isActive) {
                    OutlinedButton(
                        onClick = { onDisconnect(mount) },
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(stringResource(R.string.sync_disconnect), style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = { onUpload(mount) },
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.sync_upload_changes),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    OutlinedButton(
                        onClick = { onDownload(mount) },
                        modifier = Modifier
                            .weight(1f)
                            .height(32.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            stringResource(R.string.sync_download_all),
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1
                        )
                    }
                    Box {
                        IconButton(
                            onClick = { actionsExpanded = true },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = FeatherIcons.MoreHorizontal,
                                contentDescription = stringResource(R.string.common_more),
                                tint = MaterialTheme.semanticColors.subtleText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = actionsExpanded,
                            onDismissRequest = { actionsExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_upload_full)) },
                                onClick = {
                                    actionsExpanded = false
                                    onUploadFull(mount)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.sync_clear_index)) },
                                onClick = {
                                    actionsExpanded = false
                                    onClearIndex(mount)
                                }
                            )
                        }
                    }
                } else {
                    Button(
                        onClick = { onConnect(mount) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(FeatherIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(stringResource(R.string.sync_connect_and_sync))
                    }
                }
            }
        }
        }
}
