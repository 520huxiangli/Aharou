package com.aharou.feature.account.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.account.domain.model.AccountSession
import com.aharou.feature.account.domain.model.quotaToCny
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.CreditCard
import compose.icons.feathericons.ExternalLink
import compose.icons.feathericons.Gift
import compose.icons.feathericons.LogOut
import compose.icons.feathericons.Mail
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.User
import java.util.Locale

/** 登录态下的两种表单模式。 */
private enum class AuthMode { Login, Register }

/**
 * 「内置AI」账号页：未登录时是登录/注册表单，登录后是余额、兑换码与退出。
 *
 * 登录成功会自动把后端令牌取回来写进一个 Aharou provider，用户不需要自己去找 API Key 粘贴。
 */
@Composable
fun BuiltinAiAccountScreen(
    modifier: Modifier = Modifier,
    viewModel: AccountViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val notice = state.notice
    var resolvedNotice: String? = null
    if (notice != null) {
        if (notice.localRes != null) {
            val base = stringResource(notice.localRes)
            val body = notice.formatArg?.let { base.format(it) } ?: base
            // 后端原文才是真正的失败原因，拼在后面；只显本地文案会把线索盖掉。
            resolvedNotice = if (notice.rawText.isNullOrBlank()) body else "$body：${notice.rawText}"
        } else {
            resolvedNotice = notice.rawText
        }
    }
    LaunchedEffect(notice) {
        resolvedNotice?.takeIf { it.isNotBlank() }?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeNotice()
        }
    }

    Box(modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.md)
        ) {
            // 身份行与助手对称；未登录也该能改自己的头像与名字，所以放在登录判断之外。
            UserIdentityCard()
            Spacer(Modifier.height(Spacing.lg))
            RechargeCard()
            Spacer(Modifier.height(Spacing.lg))
            if (session == null) {
                AuthForm(uiState = state, viewModel = viewModel)
            } else {
                ProfileContent(
                    session = session!!,
                    uiState = state,
                    onRefresh = viewModel::refresh,
                    onRedeem = viewModel::redeem,
                    onProvision = viewModel::provision,
                    onLogout = viewModel::logout
                )
            }
            Spacer(Modifier.height(Spacing.lg))
        }
        SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun AuthForm(uiState: AccountUiState, viewModel: AccountViewModel) {
    var mode by rememberSaveable { mutableStateOf(AuthMode.Login) }
    var email by rememberSaveable { mutableStateOf(uiState.prefilledEmail) }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    val isRegister = mode == AuthMode.Register

    SettingsGroupHeader(stringResource(R.string.account_sign_in_title))
    SettingsGroup {
        Column(modifier = Modifier.padding(Spacing.md)) {
            AppTextField(
                value = email,
                onValueChange = { email = it },
                label = stringResource(R.string.account_email_label),
                placeholder = stringResource(R.string.account_email_placeholder),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )
            if (isRegister) {
                Spacer(Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppTextField(
                        value = code,
                        onValueChange = { code = it },
                        modifier = Modifier.weight(1f),
                        label = stringResource(R.string.account_code_label),
                        placeholder = stringResource(R.string.account_code_placeholder),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    // 发码按钮独立成行内的次级按钮：注册表单里它是可选前置动作，不该和主按钮抢视线。
                    OutlinedButton(
                        onClick = { viewModel.sendVerificationCode(email) },
                        enabled = email.isNotBlank() && !uiState.busy
                    ) {
                        Text(stringResource(R.string.account_send_code))
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            AppTextField(
                value = password,
                onValueChange = { password = it },
                label = stringResource(R.string.account_password_label),
                placeholder = stringResource(R.string.account_password_placeholder),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = {
                    if (isRegister) {
                        viewModel.register(email, code, password)
                    } else {
                        viewModel.login(email, password)
                    }
                },
                enabled = email.isNotBlank() && password.isNotBlank() && !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(if (isRegister) R.string.account_register else R.string.account_login))
                }
            }
            Spacer(Modifier.height(Spacing.xs))
            TextButton(
                onClick = { mode = if (isRegister) AuthMode.Login else AuthMode.Register },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (isRegister) R.string.account_switch_to_login
                        else R.string.account_switch_to_register
                    )
                )
            }
        }
    }
    Spacer(Modifier.height(Spacing.lg))
    Text(
        text = stringResource(R.string.account_sign_in_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ProfileContent(
    session: AccountSession,
    uiState: AccountUiState,
    onRefresh: () -> Unit,
    onRedeem: (String) -> Unit,
    onProvision: () -> Unit,
    onLogout: () -> Unit
) {
    var code by rememberSaveable { mutableStateOf("") }
    val profile = uiState.profile

    SettingsGroupHeader(stringResource(R.string.account_profile_title))
    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.User,
            title = profile?.email?.takeIf { it.isNotBlank() } ?: session.displayLabel
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.CreditCard,
            title = stringResource(R.string.account_balance),
            trailing = { AccountValue(profile?.quota?.quotaToCny()) }
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Gift,
            title = stringResource(R.string.account_used),
            trailing = { AccountValue(profile?.usedQuota?.quotaToCny()) }
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.RefreshCw,
            title = stringResource(R.string.account_requests),
            onClick = onRefresh,
            trailing = {
                Text(
                    text = profile?.requestCount?.toString().orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        )
    }

    SettingsGroupHeader(stringResource(R.string.account_redeem_title))
    SettingsGroup {
        Column(modifier = Modifier.padding(Spacing.md)) {
            AppTextField(
                value = code,
                onValueChange = { code = it },
                label = stringResource(R.string.account_redeem_label),
                placeholder = stringResource(R.string.account_redeem_placeholder),
                leadingIcon = { Icon(FeatherIcons.Gift, contentDescription = null) }
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = {
                    onRedeem(code)
                    code = ""
                },
                enabled = code.isNotBlank() && !uiState.busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.account_redeem_action))
            }
        }
    }

    SettingsGroupHeader(stringResource(R.string.account_actions_title))
    SettingsGroup {
        SettingsRow(
            icon = FeatherIcons.Mail,
            title = stringResource(R.string.account_provision),
            subtitle = stringResource(R.string.account_provision_subtitle),
            onClick = onProvision
        )
        SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.LogOut,
            title = stringResource(R.string.account_logout),
            onClick = onLogout
        )
    }
}

/** 去店铺买兑换码的入口。买码不需要登录，所以与登录态无关，单独成卡。 */
@Composable
private fun RechargeCard() {
    val uriHandler = LocalUriHandler.current
    SettingsGroupHeader(stringResource(R.string.account_recharge_title))
    SettingsGroup {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Button(
                onClick = { runCatching { uriHandler.openUri(RECHARGE_SHOP_URL) } },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Spacing.xs))
                Text(stringResource(R.string.account_recharge_now))
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.account_recharge_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 云猫店铺地址：在这儿买兑换码，回 App 兑换。换店铺只改这一处。 */
private const val RECHARGE_SHOP_URL = "https://catfk.com/shop/Aharou"

/** 余额类数值统一按人民币两位小数展示；样式与用量行一致。 */
@Composable
private fun AccountValue(cny: Double?) {
    Text(
        text = if (cny == null) {
            stringResource(R.string.account_value_unknown)
        } else {
            stringResource(R.string.account_value_cny, String.format(Locale.getDefault(), "%.2f", cny))
        },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
