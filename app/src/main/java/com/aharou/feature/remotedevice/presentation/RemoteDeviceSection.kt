package com.aharou.feature.remotedevice.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import com.aharou.feature.remotedevice.RemoteDeviceController
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
    val pairing by viewModel.pairing.collectAsStateWithLifecycle()
    val assist by viewModel.assist.collectAsStateWithLifecycle()
    val config = state.config

    var showPairSheet by remember { mutableStateOf(false) }
    var showAssistDialog by remember { mutableStateOf(false) }

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
        }

        // 配对与授权只在本机服务开启时有意义，关着就把这组藏起来。
        if (config.enabled) {
            SettingsGroupHeader(text = stringResource(R.string.settings_remote_device_pair_code))
            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Key,
                    title = stringResource(R.string.settings_remote_device_pair_code),
                    subtitle = stringResource(R.string.settings_remote_device_pair_hint),
                    onClick = { showPairSheet = true }
                )
            }
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

/** 服务运行状态的一句话，作开关行的副标题；反向互通失败时在后面补一句。 */
@Composable
private fun serviceStatusText(state: RemoteDeviceUiState): String {
    val base = when (val server = state.server) {
        is RemoteServerState.Running -> stringResource(R.string.settings_remote_device_service_running)
        is RemoteServerState.Failed -> stringResource(R.string.settings_remote_device_service_failed, server.message)
        RemoteServerState.Stopped -> stringResource(R.string.settings_remote_device_service_stopped)
    }
    val issue = state.pairBackIssue ?: return base
    return base + " · " + stringResource(R.string.settings_remote_device_pair_back_failed, issue)
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
                    // 一段带走三样：对端把它粘进「协助地址」即自动拆填，省掉手抄地址与端口。
                    val uri = endpoints.firstOrNull()?.let { runCatching { java.net.URI(it) }.getOrNull() }
                    val shortHost = uri?.host?.removeSurrounding("[", "]")
                    val parsedPort = uri?.port?.takeIf { it in RemoteDeviceConfig.MIN_PORT..RemoteDeviceConfig.MAX_PORT }
                    val codeText = pairing?.code.orEmpty()
                    val payload = if (!shortHost.isNullOrBlank() && parsedPort != null && codeText.isNotBlank()) {
                        "aharou://pair?host=$shortHost&port=$parsedPort&code=$codeText"
                    } else {
                        endpoints.joinToString("\n")
                    }
                    clipboard.setText(AnnotatedString(payload))
                }) { Text(stringResource(R.string.settings_remote_device_copy_pairing)) }
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
@OptIn(ExperimentalMaterial3Api::class)
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

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            Text(
                text = stringResource(R.string.settings_remote_device_accept_assist),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
                AppTextField(
                    value = host,
                    onValueChange = { raw ->
                        // 粘贴一整条配对信息（aharou://pair?host=…&port=…&code=…）就地拆填；
                        // 手输也认 [ipv6]:port / host:port——端口填错是这一步最容易翻车的地方。
                        val parsed = parsePairingInput(raw)
                        if (parsed != null) {
                            host = parsed.first
                            parsed.second
                                ?.takeIf { it in RemoteDeviceConfig.MIN_PORT..RemoteDeviceConfig.MAX_PORT }
                                ?.let { port = it.toString() }
                            if (parsed.third.isNotBlank()) code = parsed.third
                        } else {
                            host = raw
                        }
                    },
                    label = stringResource(R.string.settings_remote_device_assist_address),
                    singleLine = true
                )
                AppTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit).take(5) },
                    label = stringResource(R.string.settings_remote_device_port),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        text = assistMessage(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.ok) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                TextButton(
                    enabled = !working && host.isNotBlank() && code.length == 6,
                    onClick = { onConfirm(host, port.toIntOrNull() ?: RemoteDeviceConfig.DEFAULT_PORT, code) }
                ) { Text(stringResource(R.string.common_ok)) }
            }
        }
    }
}

/** 配对结果文案：文案在 strings 里，控制器只返回原因码。 */
@Composable
private fun assistMessage(state: AssistUiState.Done): String = if (state.ok) {
    stringResource(R.string.settings_remote_device_assist_ok, state.detail)
} else {
    when (state.error) {
        RemoteDeviceController.AssistError.EMPTY_INPUT ->
            stringResource(R.string.settings_remote_device_assist_err_empty)
        RemoteDeviceController.AssistError.UNREACHABLE ->
            stringResource(R.string.settings_remote_device_assist_err_unreachable, state.detail)
        RemoteDeviceController.AssistError.INVALID_CODE ->
            stringResource(R.string.settings_remote_device_assist_err_invalid_code)
        RemoteDeviceController.AssistError.PEER_NOT_ALLOWED ->
            stringResource(R.string.settings_remote_device_assist_err_peer_not_allowed)
        RemoteDeviceController.AssistError.TOO_MANY_ATTEMPTS ->
            stringResource(R.string.settings_remote_device_assist_err_too_many)
        RemoteDeviceController.AssistError.HTTP_ERROR ->
            stringResource(R.string.settings_remote_device_assist_err_http, state.detail)
        RemoteDeviceController.AssistError.NO_TOKEN ->
            stringResource(R.string.settings_remote_device_assist_err_no_token)
        null -> ""
    }
}

/**
 * 解析一条「配对信息」。既认复制来的深链 `aharou://pair?host=…&port=…&code=…`，
 * 也认手输的 `[ipv6]:port` / `host:port`。
 *
 * 裸 IPv6（`240e:473:…`）里的冒号是地址本身的一部分，不当端口分隔符拆——拆了必错。
 * 不是配对信息时返回 null，调用方按普通输入处理。
 */
private fun parsePairingInput(raw: String): Triple<String, Int?, String>? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    if (text.startsWith("aharou://", ignoreCase = true) || text.contains("host=")) {
        val query = text.substringAfter('?', text)
        val params = query.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else pair.substring(0, idx) to pair.substring(idx + 1)
        }.toMap()
        val parsedHost = params["host"]?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }
            ?: return null
        return Triple(parsedHost, params["port"]?.toIntOrNull(), params["code"].orEmpty().uppercase())
    }
    val bracketed = text.startsWith("[") && text.contains("]:")
    val plainHostPort = !bracketed && text.count { it == ':' } == 1 &&
        text.substringAfterLast(':').all { it.isDigit() }
    if (bracketed || plainHostPort) {
        val parsedHost = if (bracketed) text.substringAfter('[').substringBefore(']')
        else text.substringBeforeLast(':')
        val portText = if (bracketed) text.substringAfter("]:").substringBefore('/')
        else text.substringAfterLast(':')
        return Triple(parsedHost, portText.toIntOrNull(), "")
    }
    return null
}


