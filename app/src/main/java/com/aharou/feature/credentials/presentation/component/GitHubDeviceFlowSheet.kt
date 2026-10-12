package com.aharou.feature.credentials.presentation.component

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.feature.credentials.data.remote.GitHubAppConfig
import com.aharou.feature.credentials.presentation.CredentialViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.AlertCircle
import compose.icons.feathericons.Check
import compose.icons.feathericons.Copy
import compose.icons.feathericons.Download
import compose.icons.feathericons.ExternalLink

/**
 * GitHub 设备流快捷登录状态机弹层：申请中 / 等待授权 / 成功 / 失败。
 *
 * 复制设备码与打开浏览器在此处理；轮询由 [CredentialViewModel] 在后台进行，本弹层只渲染 [state]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GitHubDeviceFlowSheet(
    state: CredentialViewModel.QuickLoginUiState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            when (state) {
                CredentialViewModel.QuickLoginUiState.Idle -> Unit

                CredentialViewModel.QuickLoginUiState.RequestingCode -> {
                    SheetTitle(stringResource(R.string.credential_quick_login_title))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(Spacing.md))
                        Text(
                            text = stringResource(R.string.credential_quick_login_requesting),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is CredentialViewModel.QuickLoginUiState.Awaiting -> {
                    SheetTitle(stringResource(R.string.credential_quick_login_awaiting_title))
                    Text(
                        text = stringResource(R.string.credential_quick_login_awaiting_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(Radius.mdLarge))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { copyToClipboard(context, state.userCode) }
                            .padding(vertical = Spacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.userCode,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Button(
                        onClick = {
                            copyToClipboard(context, state.userCode, toast = false)
                            openExternal(
                                context,
                                state.verificationUri,
                                successToast = context.getString(R.string.credential_quick_login_copied_and_opened)
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(Radius.mdLarge)
                    ) {
                        Icon(FeatherIcons.ExternalLink, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.sm))
                        Text(stringResource(R.string.credential_quick_login_copy_and_open))
                    }
                    Text(
                        text = stringResource(R.string.credential_quick_login_awaiting_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.credential_quick_login_expire_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }

                is CredentialViewModel.QuickLoginUiState.Success -> {
                    SheetTitle(stringResource(R.string.credential_quick_login_success_title))
                    Text(
                        text = stringResource(R.string.credential_quick_login_success_body, state.username),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.needsInstall) {
                        Text(
                            text = stringResource(R.string.credential_quick_login_install_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = stringResource(R.string.credential_quick_login_install_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = { openExternal(context, GitHubAppConfig.INSTALL_URL) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(Radius.mdLarge)
                        ) {
                            Icon(FeatherIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(R.string.credential_quick_login_install_action))
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.credential_quick_login_install_skip))
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.credential_quick_login_token_expiry_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp),
                            shape = RoundedCornerShape(Radius.mdLarge)
                        ) {
                            Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Spacing.sm))
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }

                is CredentialViewModel.QuickLoginUiState.Failed -> {
                    SheetTitle(stringResource(R.string.credential_quick_login_failed_title), isError = true)
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
                        Button(
                            onClick = onRetry,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(Radius.mdLarge)
                        ) {
                            Text(stringResource(R.string.credential_quick_login_retry))
                        }
                        TextButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                        ) {
                            Text(stringResource(R.string.common_close))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String, isError: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (isError) {
            Icon(
                imageVector = FeatherIcons.AlertCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** 写入剪贴板；[toast] 为 true 时提示「已复制」。 */
private fun copyToClipboard(context: Context, text: String, toast: Boolean = true) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    cm?.setPrimaryClip(ClipData.newPlainText("device-code", text))
    if (toast) Toast.makeText(context, context.getString(R.string.common_copy_success), Toast.LENGTH_SHORT).show()
}

/** 用外部浏览器打开链接；[successToast] 非空时成功后提示，失败统一提示无法打开。 */
private fun openExternal(context: Context, url: String, successToast: String? = null) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        )
    }.onSuccess {
        successToast?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
    }.onFailure {
        Toast.makeText(context, context.getString(R.string.common_open_link_failed), Toast.LENGTH_SHORT).show()
    }
}
