package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.settings.presentation.QqBotViewModel

/**
 * 「设置 → QQ机器人」。
 *
 * 需要自建应用的 appId + appSecret（开放平台「凭证与基础信息」），事件走 WebSocket 长连接，
 * 不用配回调地址。凭证由用户自己填，App 不内置任何人的身份。
 */
@Composable
internal fun QqBotSection(
    viewModel: QqBotViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val cfg = ui.config

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_qq_bot_connection))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_qq_bot_enable),
                subtitle = connectionLabel(ui.connection),
                trailing = {
                    AppSwitch(checked = cfg.enabled, onCheckedChange = viewModel::setEnabled)
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_qq_bot_credentials))
        SettingsGroup {
            QqTextField(
                label = stringResource(R.string.settings_qq_bot_app_id),
                value = cfg.appId,
                onValueChange = viewModel::setAppId,
                hint = stringResource(R.string.settings_qq_bot_app_id_hint)
            )
            SettingsDivider()
            QqTextField(
                label = stringResource(R.string.settings_qq_bot_app_secret),
                value = cfg.appSecret,
                onValueChange = viewModel::setAppSecret,
                hint = stringResource(R.string.settings_qq_bot_app_secret_hint)
            )
            SettingsDivider()
            QqTextField(
                label = stringResource(R.string.settings_qq_bot_base_url),
                value = cfg.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                hint = stringResource(R.string.settings_qq_bot_base_url_hint)
            )
            SettingsDivider()
            QqTextField(
                label = stringResource(R.string.settings_qq_bot_proxy),
                value = cfg.proxy,
                onValueChange = viewModel::setProxy,
                hint = stringResource(R.string.settings_qq_bot_proxy_hint)
            )
        }

        Text(
            text = stringResource(R.string.settings_qq_bot_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )
    }
}

@Composable
private fun QqTextField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
    Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        AppTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = hint,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xs)
        )
    }
}

@Composable
private fun QqHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
    )
}

@Composable
private fun connectionLabel(state: ImChannelState): String = when (state) {
    is ImChannelState.Connected -> stringResource(R.string.settings_qq_bot_status_connected, state.identity)
    is ImChannelState.Error -> stringResource(R.string.settings_qq_bot_status_error, state.message)
    ImChannelState.Connecting -> stringResource(R.string.im_status_connecting)
    ImChannelState.Stopped -> stringResource(R.string.settings_qq_bot_status_stopped)
}
