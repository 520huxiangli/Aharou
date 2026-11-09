package com.aharou.feature.settings.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.settings.presentation.NapCatViewModel
import com.aharou.feature.im.domain.napcat.NapCatInstallStage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NapCatBotSection(viewModel: NapCatViewModel = hiltViewModel()) {
    val cfg by viewModel.config.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val login by viewModel.login.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val webUrl by viewModel.webUrl.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val providers by viewModel.providers.collectAsStateWithLifecycle()
    val workspaces by viewModel.workspaces.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showModels by remember { mutableStateOf(false) }
    var showWorkspaces by remember { mutableStateOf(false) }
    var showLogs by remember { mutableStateOf(false) }
    var uin by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateMessages by remember(cfg.respondPrivate) { mutableStateOf(cfg.respondPrivate) }
    var groups by remember(cfg.respondGroups) { mutableStateOf(cfg.respondGroups) }
    var mention by remember(cfg.requireMention) { mutableStateOf(cfg.requireMention) }
    var self by remember(cfg.respondSelf) { mutableStateOf(cfg.respondSelf) }
    var users by remember(cfg.allowedUsers) { mutableStateOf(cfg.allowedUsers.joinToString(",")) }
    var groupIds by remember(cfg.allowedGroups) { mutableStateOf(cfg.allowedGroups.joinToString(",")) }
    var workspace by remember(cfg.workspacePath) { mutableStateOf(cfg.workspacePath) }
    var provider by remember(cfg.providerId) { mutableStateOf(cfg.providerId) }
    var model by remember(cfg.model) { mutableStateOf(cfg.model) }
    val working = busy || state.phase in setOf("installing", "starting")
    val running = state.phase == "running"
    val accountStatus = if (login.loggedIn) "${login.nickname} (${login.accountId})" else stringResource(when (login.status) {
        "logged_out" -> R.string.napcat_logged_out
        "checking" -> R.string.napcat_login_checking
        "unavailable" -> R.string.napcat_login_unavailable
        "stopped" -> R.string.napcat_login_stopped
        else -> R.string.napcat_login_unknown
    })
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        SettingsGroupHeader(stringResource(R.string.napcat_runtime))
        NapHint(R.string.napcat_runtime_hint)
        SettingsGroup {
            InfoRow(stringResource(R.string.napcat_version), state.version.ifBlank { "—" })
            InfoRow(stringResource(R.string.napcat_status), stringResource(when (state.phase) {
                "not_installed" -> R.string.napcat_phase_not_installed
                "installing" -> R.string.napcat_phase_installing
                "installed" -> R.string.napcat_phase_installed
                "starting" -> R.string.napcat_phase_starting
                "running" -> R.string.napcat_phase_running
                "stopped" -> R.string.napcat_phase_stopped
                else -> R.string.napcat_phase_error
            }))
            if (state.phase == "installing") {
                NapHint(NapCatInstallStage.label(state.stage))
                logs.lastOrNull()?.let { line ->
                    Text(viewModel.redact(line), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = Spacing.lg))
                }
            }
            if (working) {
                if (state.phase == "installing" && NapCatInstallStage.isDownload(state.stage)) {
                    LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg))
                }
            }
            if (state.phase == "installing") NapAction(R.string.common_cancel, true, viewModel::cancelInstallation)
            NapAction(R.string.napcat_install, !working && (state.phase == "not_installed" ||
                (state.phase == "error" && state.version.isBlank())), viewModel::install)
            NapAction(R.string.napcat_update, !working && !running && state.version.isNotBlank(), viewModel::update)
            NapAction(if (running || cfg.enabled) R.string.napcat_stop else R.string.napcat_start,
                !working && (running || cfg.enabled || state.version.isNotBlank())) { viewModel.setEnabled(!(running || cfg.enabled)) }
            if (cfg.enabled && !working && !running && state.version.isNotBlank()) {
                NapAction(R.string.napcat_retry_start, true, viewModel::retryStart)
            }
            SettingsDivider()
            InfoRow(stringResource(R.string.napcat_connection), when (val current = connection) {
                is ImChannelState.Connected -> stringResource(R.string.settings_qq_bot_status_connected, current.identity)
                is ImChannelState.Error ->
                    viewModel.redact(current.message) + imDiagnosticSuffix(current.diagnostic)
                ImChannelState.Connecting -> stringResource(R.string.im_status_connecting)
                ImChannelState.Stopped -> stringResource(R.string.settings_qq_bot_status_stopped)
            })
        }
        listOf(state.error, login.error, error).filter { it.isNotBlank() }.distinct().forEach {
            Text(viewModel.redact(it), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg), style = MaterialTheme.typography.bodyMedium)
        }
        SettingsGroupHeader(stringResource(R.string.napcat_login))
        SettingsGroup {
            InfoRow(stringResource(R.string.napcat_account), accountStatus)
            if (!login.loggedIn && cfg.lastAccountId.isNotBlank()) {
                InfoRow(stringResource(R.string.napcat_last_account), cfg.lastAccountId)
            }
            if (!login.loggedIn) {
                NapHint(R.string.napcat_login_hint)
                NapAction(R.string.napcat_scan_login, running && !working, viewModel::openWebUi)
                NapAction(R.string.napcat_refresh_qr, running && !working, viewModel::refreshQrCode)
                NapField(R.string.napcat_uin, uin, { uin = it }, enabled = running && !working)
                NapField(R.string.napcat_password, password, { password = it }, secret = true, enabled = running && !working)
                NapAction(R.string.napcat_password_login, running && !working && uin.isNotBlank() && password.isNotEmpty()) {
                    val submittedUin = uin.trim()
                    val submittedPassword = password
                    uin = ""
                    password = ""
                    viewModel.passwordLogin(submittedUin, submittedPassword)
                }
                NapAction(R.string.napcat_verification, running && !working, viewModel::openWebUi)
            } else {
                NapHint(R.string.napcat_logout_hint)
                NapAction(R.string.napcat_logout, !working, viewModel::logout)
            }
        }
        SettingsGroupHeader(stringResource(R.string.napcat_policy))
        SettingsGroup {
            NapToggle(R.string.napcat_private, privateMessages, !working) { privateMessages = it }
            NapToggle(R.string.napcat_groups, groups, !working) { groups = it }
            NapToggle(R.string.napcat_mention, mention, !working) { mention = it }
            NapToggle(R.string.napcat_self, self, !working) { self = it }
            NapHint(R.string.napcat_allowlist_hint)
            NapField(R.string.napcat_allowed_users, users, { users = it }, enabled = !working)
            NapField(R.string.napcat_allowed_groups, groupIds, { groupIds = it }, enabled = !working)
            NapAction(R.string.common_save, !working) {
                viewModel.savePolicy(privateMessages, groups, mention, self, parseIds(users), parseIds(groupIds))
            }
        }
        SettingsGroupHeader(stringResource(R.string.napcat_defaults))
        SettingsGroup {
            NapHint(R.string.napcat_defaults_hint)
            NapField(R.string.napcat_workspace_path, workspace, { workspace = it }, enabled = !working)
            SettingsRow(title = stringResource(R.string.napcat_select_workspace), subtitle = workspace,
                enabled = !working, onClick = { showWorkspaces = true })
            SettingsRow(title = stringResource(R.string.napcat_select_model),
                subtitle = if (model.isBlank()) stringResource(R.string.napcat_follow_defaults)
                    else "${providers.find { it.id == provider }?.name ?: provider} / $model",
                enabled = !working, onClick = { showModels = true })
            NapAction(R.string.common_save, !working) { viewModel.saveDefaults(workspace, provider, model) }
        }
        SettingsGroupHeader(stringResource(R.string.napcat_advanced))
        SettingsGroup {
            NapAction(R.string.napcat_webui, running && !working, viewModel::openWebUi)
            NapAction(R.string.napcat_logs, true) { showLogs = true }
        }
        NapHint(R.string.napcat_background_hint)
    }
    if (showModels) {
        ModelSelectionSheet(
            title = stringResource(R.string.napcat_select_model),
            noModelsText = stringResource(R.string.chat_no_models_hint),
            providers = providers.filter { it.isEnabled }, currentProviderId = provider, currentModel = model,
            modelMetadata = emptyMap(),
            onSelect = { providerId, modelId -> provider = providerId; model = modelId; showModels = false },
            onClear = { provider = ""; model = ""; showModels = false },
            onDismiss = { showModels = false }
        )
    }
    if (showWorkspaces) {
        AdaptiveModalBottomSheet(onDismissRequest = { showWorkspaces = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Spacing.xl)) {
                NapAction(R.string.napcat_follow_defaults, true) { workspace = ""; showWorkspaces = false }
                workspaces.forEach { item ->
                    SettingsRow(title = item.name, subtitle = item.path, enabled = item.available, onClick = {
                        workspace = item.path
                        showWorkspaces = false
                    })
                }
                NapAction(R.string.common_close, true) { showWorkspaces = false }
            }
        }
    }
    webUrl?.let {
        NapCatWebUiSheet(url = it, tokens = listOf(cfg.token, cfg.webUiToken),
            accountStatus = accountStatus,
            refreshEnabled = running && !working,
            onRefresh = viewModel::refreshQrCode, onDismiss = viewModel::closeWebUi)
    }
    if (showLogs) {
        val safeLogs = remember(logs, cfg.token, cfg.webUiToken) { viewModel.redactedLogs() }
        AdaptiveModalBottomSheet(
            onDismissRequest = { showLogs = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(Spacing.lg)) {
                NapAction(R.string.common_copy, safeLogs.isNotEmpty()) {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("NapCat", safeLogs))
                }
                NapAction(R.string.napcat_share_logs, safeLogs.isNotEmpty()) {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, safeLogs)
                    }, null))
                }
                SelectionContainer {
                    Text(safeLogs.ifBlank { stringResource(R.string.napcat_logs_empty) }, style = MaterialTheme.typography.bodySmall)
                }
                NapAction(R.string.common_close, true) { showLogs = false }
            }
        }
    }
}

private fun parseIds(value: String): Set<String> = value.split(Regex("[,，\\s]+"))
    .map { it.trim() }.filter { it.isNotBlank() }.toSet()

@Composable
private fun NapToggle(label: Int, value: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsRow(title = stringResource(label), enabled = enabled, trailing = {
        AppSwitch(checked = value, enabled = enabled, onCheckedChange = onChange)
    })
}

@Composable
private fun NapAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(label))
    }
}

@Composable
private fun NapHint(label: Int) {
    Text(stringResource(label), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm))
}

@Composable
private fun NapField(label: Int, value: String, onChange: (String) -> Unit, secret: Boolean = false, enabled: Boolean = true) {
    AppTextField(value = value, onValueChange = onChange, label = stringResource(label), enabled = enabled,
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = Spacing.sm))
}
