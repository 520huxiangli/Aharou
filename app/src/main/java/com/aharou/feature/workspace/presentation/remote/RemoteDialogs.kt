package com.aharou.feature.workspace.presentation.remote

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.rememberModalBottomSheetState
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.workspace.domain.UriPathResolver
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.aharou.core.ui.AppTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.feature.settings.presentation.component.rememberSheetFlingFix
import com.aharou.feature.agent.domain.container.SshLoginKey
import com.aharou.feature.workspace.domain.model.RemoteConnection
import com.aharou.feature.workspace.domain.model.RemoteMount
import com.aharou.feature.workspace.domain.model.RemoteProtocol
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.R
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Folder

/** 弹窗内统一输入框：使用全局 AppTextField 组件。 */
@Composable
private fun SheetOutlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        labelComposable = label,
        singleLine = singleLine,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        placeholderComposable = placeholder,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation
    )
}

/** 弹窗顶部居中标题：与容器镜像弹窗一致的结构。 */
@Composable
private fun SheetTitle(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(modifier = Modifier.size(36.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.size(36.dp))
    }
}

/** 紧凑分段选择器：样式与 MCP 编辑弹窗的分段控件一致。 */
@Composable
private fun CompactSegments(
    selected: Int,
    onSelect: (Int) -> Unit,
    tabs: List<String>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                RoundedCornerShape(12.dp)
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = index == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(index) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 弹窗底部固定保存按钮：全宽 44dp + Check 图标 + 加粗文字，样式与 MCP 编辑弹窗一致。 */
@Composable
private fun SheetSaveButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            enabled = enabled,
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteConnectionDialog(
    initialConnection: RemoteConnection? = null,
    loginKeys: List<SshLoginKey> = emptyList(),
    pendingHostKey: PendingHostKeyConfirmation? = null,
    onConfirmHostKey: () -> Unit = {},
    onRejectHostKey: () -> Unit = {},
    onDismiss: () -> Unit,
    onAdd: (String, String, String, String, RemoteAuth, RemoteProtocol) -> Unit,
    onTestConnection: (String, String, String, RemoteAuth, RemoteProtocol, (Boolean, String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var name by remember(initialConnection) { mutableStateOf(initialConnection?.name ?: "") }
    var host by remember(initialConnection) { mutableStateOf(initialConnection?.host ?: "") }
    var port by remember(initialConnection) { mutableStateOf(initialConnection?.port?.toString() ?: "22") }
    var username by remember(initialConnection) { mutableStateOf(initialConnection?.username ?: "") }
    var password by remember(initialConnection) { mutableStateOf(initialConnection?.password ?: "") }
    var passwordVisible by remember { mutableStateOf(false) }
    var protocol by remember(initialConnection) { mutableStateOf(initialConnection?.protocol ?: RemoteProtocol.SFTP) }
    var isTesting by remember { mutableStateOf(false) }
    var authMethod by remember(initialConnection) { mutableStateOf(if (initialConnection?.authType == "key") 1 else 0) }
    var selectedKeyId by remember(initialConnection) {
        mutableStateOf(initialConnection?.authData?.let { path -> loginKeys.firstOrNull { it.path == path }?.id } ?: "")
    }
    var keyExpanded by remember { mutableStateOf(false) }

    val currentAuth: RemoteAuth = when {
        protocol == RemoteProtocol.FTP -> RemoteAuth.Password(password)
        authMethod == 1 -> loginKeys.firstOrNull { it.id == selectedKeyId }
            ?.let { RemoteAuth.PrivateKey(it.path, it.passphrase) }
            ?: RemoteAuth.Password("")
        else -> RemoteAuth.Password(password)
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
        ) {
            SheetTitle(
                text = if (initialConnection != null) stringResource(R.string.remote_edit_connection) else stringResource(R.string.remote_add_connection)
            )

            CompactSegments(
                selected = when (protocol) {
                    RemoteProtocol.SFTP -> 0
                    RemoteProtocol.FTP -> 1
                },
                onSelect = { index ->
                    val newProtocol = if (index == 0) RemoteProtocol.SFTP else RemoteProtocol.FTP
                    // 端口仅在「空白或等于上一协议默认值」时跟随新协议默认值，用户自定义端口切换后保留
                    val oldDefaultPort = when (protocol) {
                        RemoteProtocol.SFTP -> "22"
                        RemoteProtocol.FTP -> "21"
                    }
                    if (port.isBlank() || port == oldDefaultPort) {
                        port = when (newProtocol) {
                            RemoteProtocol.SFTP -> "22"
                            RemoteProtocol.FTP -> "21"
                        }
                    }
                    protocol = newProtocol
                },
                tabs = listOf("SFTP", "FTP"),
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SheetOutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(R.string.remote_connection_name_hint)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    SheetOutlinedTextField(
                        value = host,
                        onValueChange = { host = it },
                        label = { Text(stringResource(R.string.remote_host_address)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    SheetOutlinedTextField(value = port, onValueChange = { port = it.filter { char -> char.isDigit() }.take(5) }, label = { Text(stringResource(R.string.remote_port)) }, modifier = Modifier.fillMaxWidth())
                    SheetOutlinedTextField(value = username, onValueChange = { username = it }, label = { Text(stringResource(R.string.common_username)) }, modifier = Modifier.fillMaxWidth())

                    // 仅 SFTP 支持密钥认证；FTP 只有密码
                    if (protocol == RemoteProtocol.SFTP) {
                        CompactSegments(
                            selected = authMethod,
                            onSelect = { authMethod = it },
                            tabs = listOf(
                                stringResource(R.string.remote_auth_password),
                                stringResource(R.string.remote_auth_key)
                            )
                        )
                    }

                    if (protocol == RemoteProtocol.SFTP && authMethod == 1) {
                        ExposedDropdownMenuBox(
                            expanded = keyExpanded,
                            onExpandedChange = { keyExpanded = !keyExpanded }
                        ) {
                            val selectedKeyName = loginKeys.firstOrNull { it.id == selectedKeyId }?.name
                                ?: stringResource(R.string.remote_select_key)
                            SheetOutlinedTextField(
                                value = selectedKeyName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text(stringResource(R.string.remote_login_key)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = keyExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = keyExpanded,
                                onDismissRequest = { keyExpanded = false },
                                modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                            ) {
                                if (loginKeys.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.remote_no_keys_hint)) },
                                        onClick = { keyExpanded = false }
                                    )
                                }
                                loginKeys.forEach { key ->
                                    DropdownMenuItem(
                                        text = { Text(key.name) },
                                        onClick = {
                                            selectedKeyId = key.id
                                            keyExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    } else {
                        SheetOutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text(stringResource(R.string.remote_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                val image = if (passwordVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                    Icon(image, stringResource(R.string.remote_toggle_password), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            onTestConnection(host, port, username, currentAuth, protocol) { success, msg ->
                                isTesting = false
                                android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isTesting && host.isNotBlank() && username.isNotBlank() &&
                            (protocol == RemoteProtocol.FTP || authMethod == 0 || selectedKeyId.isNotEmpty())
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.remote_test_connection))
                        }
                    }

                }
            }

            SheetSaveButton(
                text = stringResource(if (initialConnection != null) R.string.common_save else R.string.common_add),
                enabled = name.isNotBlank() && host.isNotBlank() && username.isNotBlank() &&
                    (protocol == RemoteProtocol.FTP || authMethod == 0 || selectedKeyId.isNotEmpty()),
                onClick = {
                    onAdd(name, host, port, username, currentAuth, protocol)
                }
            )

            // 主机密钥确认：独立弹窗，覆盖在编辑弹窗之上（首次连接/指纹变化时由测试连通性触发）
            pendingHostKey?.let { pending ->
                AlertDialog(
                    onDismissRequest = onRejectHostKey,
                    containerColor = MaterialTheme.colorScheme.surface,
                    title = {
                        Text(
                            stringResource(
                                if (pending.changed) R.string.ssh_host_key_changed_title
                                else R.string.ssh_host_key_confirm_title
                            )
                        )
                    },
                    text = {
                        Text(
                            "${pending.host}:${pending.port}\n${pending.keyType}\n" +
                                stringResource(R.string.ssh_host_key_fingerprint_value, pending.fingerprint)
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                onConfirmHostKey()
                                // 确认后立即重测：指纹已保存，此次应直接连通
                                isTesting = true
                                onTestConnection(host, port, username, currentAuth, protocol) { success, msg ->
                                    isTesting = false
                                    android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        ) {
                            Text(stringResource(R.string.ssh_host_key_trust))
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = onRejectHostKey) {
                            Text(stringResource(R.string.ssh_host_key_reject))
                        }
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddRemoteMountDialog(
    initialMount: RemoteMount? = null,
    connections: List<RemoteConnection>,
    workspaces: List<com.aharou.feature.workspace.domain.model.Workspace>,
    mounts: List<RemoteMount> = emptyList(),
    onDismiss: () -> Unit,
    onAdd: (String, String, String, Boolean) -> Unit,
    onListDirectories: (String, String, (Boolean, List<String>, String) -> Unit) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedConnectionId by remember(initialMount) { mutableStateOf(initialMount?.connectionId ?: connections.firstOrNull()?.id ?: "") }
    var remotePath by remember(initialMount) { mutableStateOf(initialMount?.remotePath ?: "/") }

    var selectedWorkspacePath by remember(initialMount) { mutableStateOf(initialMount?.localMountPath ?: workspaces.firstOrNull()?.path ?: "") }
    var autoConnect by remember(initialMount) { mutableStateOf(initialMount?.autoConnect ?: true) }

    var connExpanded by remember { mutableStateOf(false) }
    var wsExpanded by remember { mutableStateOf(false) }
    var showBrowser by remember { mutableStateOf(false) }
    val selectedConnection = connections.find { it.id == selectedConnectionId }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val configuration = LocalConfiguration.current
    val screenHeight = configuration.screenHeightDp.dp
    val flingFix = rememberSheetFlingFix(sheetState)

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = { WindowInsets(0.dp) },
        dialogMaxWidth = 600.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = screenHeight * 0.88f)
        ) {
            SheetTitle(
                text = if (initialMount != null) stringResource(R.string.remote_edit_workspace) else stringResource(R.string.remote_add_workspace)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExposedDropdownMenuBox(
                        expanded = connExpanded,
                        onExpandedChange = { connExpanded = !connExpanded }
                    ) {
                        val selectedName = connections.find { it.id == selectedConnectionId }?.name ?: stringResource(R.string.remote_select_channel)
                        SheetOutlinedTextField(
                            value = selectedName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.remote_link_channel)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = connExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = connExpanded,
                            onDismissRequest = { connExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            connections.forEach { conn ->
                                DropdownMenuItem(
                                    text = { Text(conn.name) },
                                    onClick = {
                                        selectedConnectionId = conn.id
                                        connExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SheetOutlinedTextField(
                            value = remotePath,
                            onValueChange = { remotePath = it },
                            label = { Text(stringResource(R.string.remote_target_dir)) },
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = { showBrowser = true },
                            enabled = selectedConnectionId.isNotEmpty()
                        ) {
                            Icon(FeatherIcons.Folder, contentDescription = stringResource(R.string.remote_browse_dir), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    ExposedDropdownMenuBox(
                        expanded = wsExpanded,
                        onExpandedChange = { wsExpanded = !wsExpanded }
                    ) {
                        val selectedWsName = workspaces.find { it.path == selectedWorkspacePath }?.name ?: stringResource(R.string.remote_select_local_workspace)
                        SheetOutlinedTextField(
                            value = selectedWsName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(stringResource(R.string.remote_map_to_local)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = wsExpanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = wsExpanded,
                            onDismissRequest = { wsExpanded = false },
                            modifier = Modifier.background(MaterialTheme.colorScheme.surface)
                        ) {
                            workspaces.forEach { ws ->
                                DropdownMenuItem(
                                    text = { Text(ws.name) },
                                    onClick = {
                                        selectedWorkspacePath = ws.path
                                        wsExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    if (workspaces.isEmpty()) {
                        Text(stringResource(R.string.remote_no_local_workspace), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.remote_auto_connect_on_start), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(stringResource(R.string.remote_auto_connect_and_sync), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        AppSwitch(checked = autoConnect, onCheckedChange = { autoConnect = it })
                    }
                }
            }

            SheetSaveButton(
                text = stringResource(if (initialMount != null) R.string.common_save else R.string.remote_add_workspace),
                enabled = selectedWorkspacePath.isNotEmpty() && selectedConnectionId.isNotEmpty(),
                onClick = {
                    val duplicate = mounts.any {
                        it.id != initialMount?.id &&
                            it.connectionId == selectedConnectionId &&
                            it.remotePath == remotePath &&
                            it.localMountPath == selectedWorkspacePath
                    }
                    if (duplicate) {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.remote_mount_already_exists),
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } else {
                        onAdd(selectedConnectionId, remotePath, selectedWorkspacePath, autoConnect)
                    }
                }
            )
        }
    }

    if (showBrowser) {
        RemoteDirectoryBrowserDialog(
            connectionId = selectedConnectionId,
            initialPath = remotePath.ifBlank { "/" },
            onPathSelected = {
                remotePath = it
                showBrowser = false
            },
            onDismiss = { showBrowser = false },
            listDirectories = onListDirectories
        )
    }
}

@Composable
fun RemoteDirectoryBrowserDialog(
    connectionId: String,
    initialPath: String,
    onPathSelected: (String) -> Unit,
    onDismiss: () -> Unit,
    listDirectories: (String, String, (Boolean, List<String>, String) -> Unit) -> Unit
) {
    var currentPath by remember { mutableStateOf(if (initialPath.endsWith("/")) initialPath else "$initialPath/") }
    var directories by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentPath) {
        isLoading = true
        error = null
        listDirectories(connectionId, currentPath) { success, dirs, msg ->
            isLoading = false
            if (success) {
                directories = dirs.sorted()
            } else {
                error = msg
            }
        }
    }

    AlertDialog(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remote_select_remote_dir)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 400.dp)) {
                Text(stringResource(R.string.remote_current_path, currentPath), fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                val loadError = error
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                } else if (loadError != null) {
                    Text(stringResource(R.string.remote_load_failed, loadError), color = MaterialTheme.colorScheme.error)
                } else {
                    LazyColumn {
                        if (currentPath != "/") {
                            item {
                                TextButton(onClick = {
                                    val parent = currentPath.trimEnd('/').substringBeforeLast('/')
                                    currentPath = if (parent.isEmpty()) "/" else "$parent/"
                                }) {
                                    Icon(FeatherIcons.Folder, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text(stringResource(R.string.remote_parent_dir))
                                }
                            }
                        }
                        items(directories) { dir ->
                            TextButton(onClick = {
                                currentPath = if (currentPath == "/") "/$dir/" else "$currentPath$dir/"
                            }) {
                                Icon(FeatherIcons.Folder, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(dir)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onPathSelected(currentPath) }) {
                Text(stringResource(R.string.remote_confirm_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
