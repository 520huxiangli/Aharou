package com.aharou.feature.remotedevice.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.feature.remotedevice.RemoteDeviceUiState
import com.aharou.feature.remotedevice.domain.RemoteDeviceConfig
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.server.RemoteServerState
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Globe
import compose.icons.feathericons.Key
import compose.icons.feathericons.Server
import compose.icons.feathericons.Share2
import compose.icons.feathericons.Shield
import compose.icons.feathericons.Smartphone
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 「跨设备协作」设置分区：本机作为局域网 MCP 服务端，供另一台 Aharou 配对后调用其能力。
 *
 * 全部状态与动作都转发给 [RemoteDeviceViewModel]（其背后是 @Singleton 的 RemoteDeviceController）——
 * 本组件只负责呈现，不持有任何业务状态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteDeviceSection(viewModel: RemoteDeviceViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val assist by viewModel.assist.collectAsStateWithLifecycle()
    val config = state.config

    var showNameSheet by remember { mutableStateOf(false) }
    var showPortSheet by remember { mutableStateOf(false) }
    var showPairSheet by remember { mutableStateOf(false) }
    var showAssistDialog by remember { mutableStateOf(false) }
    var revokeTarget by remember { mutableStateOf<RemotePairedDevice?>(null) }
    // ifBlank 的 lambda 不是 composable 上下文，默认名必须先在这一层取好。
    val defaultDeviceName = stringResource(R.string.settings_remote_device_name_default)

    // 每次开启都拉一次授权列表：对端可能在开关保持开启时刚完成配对。
    LaunchedEffect(config.enabled) {
        if (config.enabled) viewModel.refreshDevices()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(top = Spacing.sm, bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_remote_device_title))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Share2,
                title = stringResource(R.string.settings_remote_device_enable),
                subtitle = serviceStatusText(state),
                trailing = {
                    AppSwitch(
                        checked = config.enabled,
                        onCheckedChange = { viewModel.setEnabled(it) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.settings_remote_device_name),
                onClick = { showNameSheet = true },
                trailing = {
                    ValueText(
                        config.deviceName.ifBlank { defaultDeviceName }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Server,
                title = stringResource(R.string.settings_remote_device_port),
                onClick = { showPortSheet = true },
                trailing = { ValueText(config.port.toString()) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Shield,
                title = stringResource(R.string.settings_remote_device_always_ask),
                trailing = {
                    AppSwitch(
                        checked = config.alwaysAskRemote,
                        onCheckedChange = { viewModel.setAlwaysAskRemote(it) }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Share2,
                title = stringResource(R.string.settings_remote_device_allow_public),
                subtitle = stringResource(R.string.settings_remote_device_allow_public_hint),
                trailing = {
                    AppSwitch(
                        checked = config.allowPublicPeer,
                        onCheckedChange = { viewModel.setAllowPublicPeer(it) }
                    )
                }
            )
        }

        // 接受协助：作为协助方连到别人的 Aharou——与本机开服务是两件事，所以不放在 enabled 分支里。
        SettingsGroupHeader(text = stringResource(R.string.settings_remote_device_assist_title))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Share2,
                title = stringResource(R.string.settings_remote_device_accept_assist),
                subtitle = stringResource(R.string.settings_remote_device_accept_assist_hint),
                onClick = { showAssistDialog = true }
            )
        }

        // 配对与授权只在本机服务开启时有意义，关着就把这几组藏起来，避免空列表占屏。
        if (config.enabled) {
            SettingsGroupHeader(text = stringResource(R.string.settings_remote_device_pair_code))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Key,
                    title = stringResource(R.string.settings_remote_device_pair_code),
                    subtitle = stringResource(R.string.settings_remote_device_pair_hint),
                    onClick = { showPairSheet = true }
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Globe,
                    title = stringResource(R.string.settings_remote_device_endpoint),
                    subtitle = state.endpoints.firstOrNull()
                        ?: stringResource(R.string.settings_remote_device_service_stopped)
                )
            }

            SettingsGroupHeader(text = stringResource(R.string.settings_remote_device_paired))
            SettingsGroup {
                if (devices.isEmpty()) {
                    SettingsRow(title = stringResource(R.string.settings_remote_device_no_devices))
                } else {
                    devices.forEachIndexed { index, device ->
                        if (index > 0) SettingsDivider()
                        PairedDeviceRow(device = device, onRevoke = { revokeTarget = device })
                    }
                }
            }
        }
    }

    if (showNameSheet) {
        ValueEditSheet(
            title = stringResource(R.string.settings_remote_device_name),
            initial = config.deviceName,
            hint = stringResource(R.string.settings_remote_device_name_hint),
            keyboardType = KeyboardType.Text,
            onConfirm = { viewModel.setDeviceName(it) },
            onDismiss = { showNameSheet = false }
        )
    }

    if (showPortSheet) {
        val invalidMessage = stringResource(R.string.settings_remote_device_port_invalid)
        ValueEditSheet(
            title = stringResource(R.string.settings_remote_device_port),
            initial = config.port.toString(),
            hint = null,
            keyboardType = KeyboardType.Number,
            validate = { text ->
                val value = text.toIntOrNull()
                value != null &&
                    value in RemoteDeviceConfig.MIN_PORT..RemoteDeviceConfig.MAX_PORT
            },
            invalidMessage = invalidMessage,
            onConfirm = { text -> text.toIntOrNull()?.let { viewModel.setPort(it) } },
            onDismiss = { showPortSheet = false }
        )
    }

    if (showAssistDialog) {
        AcceptAssistDialog(
            state = assist,
            onConfirm = { host, port, code -> viewModel.acceptAssistance(host, port, code) },
            onDismiss = {
                showAssistDialog = false
                viewModel.clearAssist()
            }
        )
    }

    if (showPairSheet) {
        LaunchedEffect(Unit) { viewModel.generatePairingCode() }
        PairingCodeSheet(
            pairing = pairing,
            endpoints = state.endpoints,
            onDismiss = {
                showPairSheet = false
                viewModel.clearPairing()
            }
        )
    }

    revokeTarget?.let { device ->
        AlertDialog(
            onDismissRequest = { revokeTarget = null },
            title = { Text(stringResource(R.string.settings_remote_device_revoke)) },
            text = { Text(stringResource(R.string.settings_remote_device_revoke_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.revoke(device.deviceId)
                    revokeTarget = null
                }) {
                    Text(stringResource(R.string.settings_remote_device_revoke))
                }
            },
            dismissButton = {
                TextButton(onClick = { revokeTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

/** 行尾灰字（端口号、设备名等只读值）。 */
@Composable
private fun ValueText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.semanticColors.subtleText,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

/** 服务运行状态的一句话，作开关行的副标题。 */
@Composable
private fun serviceStatusText(state: RemoteDeviceUiState): String = when (val server = state.server) {
    is RemoteServerState.Running -> stringResource(R.string.settings_remote_device_service_running)
    is RemoteServerState.Failed -> stringResource(R.string.settings_remote_device_service_failed, server.message)
    RemoteServerState.Stopped -> stringResource(R.string.settings_remote_device_service_stopped)
}

/** 单台已授权设备行：设备名 + 授权时间 + 最近活跃，左滑露出撤销（点击再确认一次）。 */
@Composable
private fun PairedDeviceRow(device: RemotePairedDevice, onRevoke: () -> Unit) {
    SwipeToDeleteRow(onDelete = onRevoke) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = FeatherIcons.Smartphone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.deviceName.ifBlank { device.deviceId },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        R.string.settings_remote_device_approved_at,
                        formatTimestamp(device.approvedAt)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(
                        R.string.settings_remote_device_last_seen,
                        formatTimestamp(device.lastSeenAt)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 单字段编辑弹层（设备名 / 端口复用）：校验不通过时禁用确定并显示错误。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueEditSheet(
    title: String,
    initial: String,
    hint: String?,
    keyboardType: KeyboardType,
    validate: (String) -> Boolean = { true },
    invalidMessage: String = "",
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    val isError = !validate(text)
    val supporting: (@Composable () -> Unit)? = if (isError) {
        { Text(invalidMessage) }
    } else {
        null
    }

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            AppTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = hint,
                isError = isError,
                supportingText = supporting,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(onClick = { onConfirm(text) }, enabled = !isError) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        }
    }
}

/** 配对码弹层：大号展示 6 位码与协助地址。码不过期，配对成功后自动换新。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairingCodeSheet(
    pairing: RemotePairingUi?,
    endpoints: List<String>,
    onDismiss: () -> Unit
) {
    val clipboard = LocalClipboardManager.current

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.settings_remote_device_pair_code),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = pairing?.code.orEmpty(),
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(R.string.settings_remote_device_pair_no_expiry),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (endpoints.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.settings_remote_device_assist_address),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                endpoints.forEach { url ->
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.semanticColors.subtleText,
                        textAlign = TextAlign.Center
                    )
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(endpoints.joinToString("\n")))
                }) { Text(stringResource(R.string.settings_remote_device_copy)) }
            }
            Text(
                text = stringResource(R.string.settings_remote_device_pair_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.semanticColors.subtleText,
                textAlign = TextAlign.Center
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}

/** 接受协助：输入对方给的地址与 6 位码，配对成功后自动登记为本机的一个 MCP 服务器。 */
@Composable
private fun AcceptAssistDialog(
    state: AssistUiState,
    onConfirm: (host: String, port: Int, code: String) -> Unit,
    onDismiss: () -> Unit
) {
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(RemoteDeviceConfig.DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    val working = state is AssistUiState.Working

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_remote_device_accept_assist)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                AppTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = stringResource(R.string.settings_remote_device_assist_address),
                    singleLine = true
                )
                AppTextField(
                    value = code,
                    onValueChange = { code = it.uppercase().take(6) },
                    label = stringResource(R.string.settings_remote_device_pair_code),
                    singleLine = true
                )
                if (state is AssistUiState.Done) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !working && host.isNotBlank() && code.length == 6,
                onClick = { onConfirm(host, port.toIntOrNull() ?: RemoteDeviceConfig.DEFAULT_PORT, code) }
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** 时间戳转 yyyy-MM-dd HH:mm。 */
private fun formatTimestamp(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
