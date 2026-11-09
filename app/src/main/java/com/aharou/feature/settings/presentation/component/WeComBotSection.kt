package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.im.domain.ImChannelState
import com.aharou.feature.settings.presentation.WeComBotViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/**
 * 「设置 → 企业微信机器人」。
 *
 * 走智能机器人的「API 模式 → 长连接」，只需要 BotID + Secret，不用配回调地址。
 * 这里的 Secret 是长连接专用密钥，与「接收消息回调地址」模式的 Token/EncodingAESKey 不是一回事。
 */
@Composable
internal fun WeComBotSection(
    viewModel: WeComBotViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val secretWriteFailed by viewModel.secretWriteFailed.collectAsStateWithLifecycle()
    val cfg = ui.config

    // 密钥默认打码，与 Telegram 分区的处理一致：设置页常被他人扫到，明文挂在屏幕上不合适。
    var secretVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_wecom_bot_connection))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_wecom_bot_enable),
                subtitle = connectionLabel(ui.connection),
                trailing = {
                    AppSwitch(checked = cfg.enabled, onCheckedChange = viewModel::setEnabled)
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_wecom_bot_credentials))
        SettingsGroup {
            WeComTextField(
                label = stringResource(R.string.settings_wecom_bot_bot_id),
                value = cfg.botId,
                onValueChange = viewModel::setBotId,
                hint = stringResource(R.string.settings_wecom_bot_bot_id_hint)
            )
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Text(
                    text = stringResource(R.string.settings_wecom_bot_secret),
                    style = MaterialTheme.typography.bodyMedium
                )
                AppTextField(
                    value = cfg.secret,
                    onValueChange = viewModel::setSecret,
                    placeholder = stringResource(R.string.settings_wecom_bot_secret_hint),
                    visualTransformation = if (secretVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    trailingIcon = {
                        Icon(
                            imageVector = if (secretVisible) FeatherIcons.Eye else FeatherIcons.EyeOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { secretVisible = !secretVisible }
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.xs)
                )
            }
            SettingsDivider()
            WeComTextField(
                label = stringResource(R.string.settings_wecom_bot_proxy),
                value = cfg.proxy,
                onValueChange = viewModel::setProxy,
                hint = stringResource(R.string.settings_wecom_bot_proxy_hint)
            )
        }

        // 密钥库不可用时 Secret 实际没存进去，不提示的话用户会以为已经保存
        if (secretWriteFailed) {
            Text(
                text = stringResource(R.string.im_secret_write_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }

        Text(
            text = stringResource(R.string.settings_wecom_bot_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )
    }
}

@Composable
private fun WeComTextField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
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
private fun connectionLabel(state: ImChannelState): String = when (state) {
    is ImChannelState.Connected -> stringResource(R.string.settings_wecom_bot_status_connected, state.identity)
    is ImChannelState.Error ->
        stringResource(R.string.settings_wecom_bot_status_error, state.message) + imDiagnosticSuffix(state.diagnostic)
    ImChannelState.Connecting -> stringResource(R.string.im_status_connecting)
    ImChannelState.Stopped -> stringResource(R.string.settings_wecom_bot_status_stopped)
}
