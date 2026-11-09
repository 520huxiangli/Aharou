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
import com.aharou.feature.settings.presentation.DingTalkBotViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/**
 * 「设置 → 钉钉机器人」。
 *
 * 需要开放平台「企业内部应用」的 AppKey + AppSecret，事件走 Stream 长连接，
 * 不用配公网回调地址。凭证由用户自己填，App 不内置任何人的身份。
 */
@Composable
internal fun DingTalkBotSection(
    viewModel: DingTalkBotViewModel = hiltViewModel()
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
        SettingsGroupHeader(text = stringResource(R.string.settings_dingtalk_bot_connection))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_dingtalk_bot_enable),
                subtitle = connectionLabel(ui.connection),
                trailing = {
                    AppSwitch(checked = cfg.enabled, onCheckedChange = viewModel::setEnabled)
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_dingtalk_bot_credentials))
        SettingsGroup {
            DingTalkTextField(
                label = stringResource(R.string.settings_dingtalk_bot_client_id),
                value = cfg.clientId,
                onValueChange = viewModel::setClientId,
                hint = stringResource(R.string.settings_dingtalk_bot_client_id_hint)
            )
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Text(
                    text = stringResource(R.string.settings_dingtalk_bot_client_secret),
                    style = MaterialTheme.typography.bodyMedium
                )
                AppTextField(
                    value = cfg.clientSecret,
                    onValueChange = viewModel::setClientSecret,
                    placeholder = stringResource(R.string.settings_dingtalk_bot_client_secret_hint),
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
            DingTalkTextField(
                label = stringResource(R.string.settings_dingtalk_bot_robot_code),
                value = cfg.robotCode,
                onValueChange = viewModel::setRobotCode,
                hint = stringResource(R.string.settings_dingtalk_bot_robot_code_hint)
            )
            SettingsDivider()
            DingTalkTextField(
                label = stringResource(R.string.settings_dingtalk_bot_proxy),
                value = cfg.proxy,
                onValueChange = viewModel::setProxy,
                hint = stringResource(R.string.settings_dingtalk_bot_proxy_hint)
            )
        }

        // 密钥库不可用时 AppSecret 实际没存进去，不提示的话用户会以为已经保存
        if (secretWriteFailed) {
            Text(
                text = stringResource(R.string.im_secret_write_failed),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)
            )
        }

        Text(
            text = stringResource(R.string.settings_dingtalk_bot_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )
    }
}

@Composable
private fun DingTalkTextField(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
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
    is ImChannelState.Connected -> stringResource(R.string.settings_dingtalk_bot_status_connected, state.identity)
    is ImChannelState.Error ->
        stringResource(R.string.settings_dingtalk_bot_status_error, state.message) + imDiagnosticSuffix(state.diagnostic)
    ImChannelState.Connecting -> stringResource(R.string.im_status_connecting)
    ImChannelState.Stopped -> stringResource(R.string.settings_dingtalk_bot_status_stopped)
}
