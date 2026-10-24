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
import androidx.compose.foundation.Image
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.im.domain.ImChannelState
import androidx.compose.foundation.layout.size
import com.aharou.core.ui.rememberQrBitmap
import com.aharou.feature.settings.presentation.QrScanState
import com.aharou.feature.settings.presentation.WeixinBotViewModel

/**
 * 「设置 → 微信机器人」。
 *
 * 走微信官方 iLink（ClawBot）通道：填 botToken 与绑定时拿到的 ilinkUserId，
 * 打开开关即拉起前台长轮询服务。凭证由用户自己填，App 不内置任何人的身份。
 */
@Composable
internal fun WeixinBotSection(
    viewModel: WeixinBotViewModel = hiltViewModel()
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    val qrState by viewModel.qr.collectAsStateWithLifecycle()
    val cfg = ui.config

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(stringResource(R.string.settings_weixin_bot_connection))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_weixin_bot_enable),
                subtitle = connectionLabel(ui.connection),
                trailing = {
                    AppSwitch(checked = cfg.enabled, onCheckedChange = viewModel::setEnabled)
                }
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.settings_weixin_bot_status),
                subtitle = cfg.baseUrl
            )
        }

        SettingsGroupHeader(stringResource(R.string.settings_weixin_bot_qr_header))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.settings_weixin_bot_qr_action),
                subtitle = qrLabel(qrState),
                trailing = {
                    // 只有停下来的时候才能重新发起；进行中给「取消」，不给重复触发
                    val busy = qrState is QrScanState.Loading || qrState is QrScanState.Showing ||
                        qrState is QrScanState.Scanned
                    TextButton(onClick = { if (busy) viewModel.cancelQrLogin() else viewModel.startQrLogin() }) {
                        Text(
                            stringResource(
                                if (busy) R.string.settings_weixin_bot_qr_cancel
                                else R.string.settings_weixin_bot_qr_start
                            )
                        )
                    }
                }
            )
            val qr = qrState
            if (qr is QrScanState.Showing) {
                SettingsDivider()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(Spacing.lg),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // qrcode_img_content 是 H5 链接不是图片，必须在本机编成二维码才扫得了
                    val qrBitmap = rememberQrBitmap(qr.display, sizePx = 720)
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = null,
                            modifier = Modifier.size(220.dp)
                        )
                    } else {
                        Text(qr.display, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        text = stringResource(R.string.settings_weixin_bot_qr_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = Spacing.sm)
                    )
                }
            }
        }

        SettingsGroupHeader(stringResource(R.string.settings_weixin_bot_credentials))
        SettingsGroup {
            TextFieldRow(
                label = stringResource(R.string.settings_weixin_bot_token),
                value = cfg.botToken,
                onValueChange = viewModel::setBotToken,
                hint = stringResource(R.string.settings_weixin_bot_token_hint)
            )
            SettingsDivider()
            TextFieldRow(
                label = stringResource(R.string.settings_weixin_bot_user_id),
                value = cfg.ilinkUserId,
                onValueChange = viewModel::setIlinkUserId,
                hint = stringResource(R.string.settings_weixin_bot_user_id_hint)
            )
            SettingsDivider()
            TextFieldRow(
                label = stringResource(R.string.settings_weixin_bot_base_url),
                value = cfg.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                hint = stringResource(R.string.settings_weixin_bot_base_url_hint)
            )
            SettingsDivider()
            TextFieldRow(
                label = stringResource(R.string.settings_weixin_bot_proxy),
                value = cfg.proxy,
                onValueChange = viewModel::setProxy,
                hint = stringResource(R.string.settings_weixin_bot_proxy_hint)
            )
        }

        Text(
            text = stringResource(R.string.settings_weixin_bot_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
        )
    }
}

@Composable
private fun TextFieldRow(label: String, value: String, onValueChange: (String) -> Unit, hint: String) {
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
private fun InGroupHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)
    )
}

/** 连接状态展示文案。 */
/** 扫码状态文案。 */
@Composable
private fun qrLabel(state: QrScanState): String = when (state) {
    QrScanState.Idle -> stringResource(R.string.settings_weixin_bot_qr_idle)
    QrScanState.Loading -> stringResource(R.string.settings_weixin_bot_qr_loading)
    is QrScanState.Showing -> stringResource(R.string.settings_weixin_bot_qr_waiting_scan)
    QrScanState.Scanned -> stringResource(R.string.settings_weixin_bot_qr_scanned)
    QrScanState.Expired -> stringResource(R.string.settings_weixin_bot_qr_expired)
    is QrScanState.Done -> stringResource(R.string.settings_weixin_bot_qr_done, state.userId)
    is QrScanState.Failed -> state.message
}

@Composable
private fun connectionLabel(state: ImChannelState): String = when (state) {
    is ImChannelState.Connected -> stringResource(R.string.settings_weixin_bot_status_connected, state.identity)
    is ImChannelState.Error -> stringResource(R.string.settings_weixin_bot_status_error, state.message)
    ImChannelState.Connecting -> stringResource(R.string.im_status_connecting)
    ImChannelState.Stopped -> stringResource(R.string.settings_weixin_bot_status_stopped)
}
