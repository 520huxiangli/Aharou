package com.aharou.feature.settings.presentation.component

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppSwitch
import com.aharou.core.ui.AppTextField
import com.aharou.feature.im.domain.model.TelegramState
import com.aharou.feature.settings.presentation.TelegramBotViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Trash2
import compose.icons.feathericons.X

/**
 * 设置页「Telegram 机器人」分区。
 *
 * 顶部一张卡片：启用开关 + 连接状态 + token / API 基址 / 代理三个输入框（即时写入，无保存按钮）。
 * 下方两张卡片：待配对（通过 / 拒绝）与已配对（移除）。
 *
 * 开关变更时由 [TelegramBotViewModel] 拉起/停掉 [com.aharou.feature.im.service.TelegramBotService]。
 * Android 13 起若不授予通知权限，前台服务通知会被隐藏，故开启前顺带申请一次。
 */
@Composable
internal fun TelegramBotSection(
    modifier: Modifier = Modifier,
    viewModel: TelegramBotViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val config = state.config

    // 待配对列表不会被 bridge 自动推送，每次进入本页重拉一次（VM 为 Activity 级，init 只跑一次）。
    LaunchedEffect(Unit) { viewModel.refresh() }

    // 通知权限申请只影响通知是否可见，不阻塞服务启停，回调里不做事。
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val onToggleEnabled: (Boolean) -> Unit = { enabled ->
        val needsNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        if (enabled && needsNotifPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        viewModel.setEnabled(enabled)
    }

    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 启用 + 状态 + 连接参数 ──
        SettingsGroup {
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_telegram_bot_enable),
                subtitle = stringResource(R.string.settings_telegram_bot_enable_subtitle),
                trailing = {
                    AppSwitch(checked = config.enabled, onCheckedChange = onToggleEnabled)
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = null,
                title = stringResource(R.string.settings_telegram_bot_status),
                trailing = {
                    Text(
                        text = connectionLabel(state.connection),
                        style = MaterialTheme.typography.bodyMedium,
                        color = connectionColor(state.connection)
                    )
                }
            )

            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                AppTextField(
                    value = config.botToken,
                    onValueChange = viewModel::setBotToken,
                    label = stringResource(R.string.settings_telegram_bot_token),
                    placeholder = stringResource(R.string.settings_telegram_bot_token_placeholder),
                    visualTransformation = if (tokenVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    trailingIcon = {
                        Icon(
                            imageVector = if (tokenVisible) FeatherIcons.Eye else FeatherIcons.EyeOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { tokenVisible = !tokenVisible }
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                AppTextField(
                    value = config.baseUrl,
                    onValueChange = viewModel::setBaseUrl,
                    label = stringResource(R.string.settings_telegram_bot_base_url),
                    placeholder = stringResource(R.string.settings_telegram_bot_base_url_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                AppTextField(
                    value = config.proxy,
                    onValueChange = viewModel::setProxy,
                    label = stringResource(R.string.settings_telegram_bot_proxy),
                    placeholder = stringResource(R.string.settings_telegram_bot_proxy_placeholder),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // ── 待配对 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_telegram_bot_pending_header))
        SettingsGroup {
            if (state.pending.isEmpty()) {
                InGroupHint(text = stringResource(R.string.settings_telegram_bot_pending_empty))
            } else {
                state.pending.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = null,
                        title = item.senderName.ifBlank { item.chatId },
                        subtitle = stringResource(R.string.settings_telegram_bot_pairing_code, item.code),
                        trailing = {
                            IconButton(onClick = { viewModel.approve(item.code) }) {
                                Icon(
                                    imageVector = FeatherIcons.Check,
                                    contentDescription = stringResource(R.string.settings_telegram_bot_approve),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.deny(item.code) }) {
                                Icon(
                                    imageVector = FeatherIcons.X,
                                    contentDescription = stringResource(R.string.settings_telegram_bot_deny),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    )
                }
            }
        }

        // ── 已配对 ──
        SettingsGroupHeader(text = stringResource(R.string.settings_telegram_bot_paired_header))
        SettingsGroup {
            if (state.paired.isEmpty()) {
                InGroupHint(text = stringResource(R.string.settings_telegram_bot_paired_empty))
            } else {
                state.paired.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    SettingsRow(
                        icon = null,
                        title = item.senderName.ifBlank { item.chatId },
                        subtitle = item.chatId,
                        trailing = {
                            IconButton(onClick = { viewModel.removePaired(item.senderId) }) {
                                Icon(
                                    imageVector = FeatherIcons.Trash2,
                                    contentDescription = stringResource(R.string.settings_telegram_bot_remove),
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 分组卡片里的空态提示（无待配对/无已配对时占位）。 */
@Composable
private fun InGroupHint(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp)
    )
}

/** 连接状态展示文案。 */
@Composable
private fun connectionLabel(state: TelegramState): String = when (state) {
    is TelegramState.Connected -> stringResource(
        R.string.settings_telegram_bot_status_connected,
        state.username
    )
    is TelegramState.Error -> stringResource(
        R.string.settings_telegram_bot_status_error,
        state.message
    )
    TelegramState.Connecting -> stringResource(R.string.settings_telegram_bot_status_connecting)
    TelegramState.Stopped -> stringResource(R.string.settings_telegram_bot_status_stopped)
}

/** 连接状态对应的语义色：正常主色、失败错误色、其余弱化色。 */
@Composable
private fun connectionColor(state: TelegramState) = when (state) {
    is TelegramState.Connected -> MaterialTheme.colorScheme.primary
    is TelegramState.Error -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.semanticColors.subtleText
}
