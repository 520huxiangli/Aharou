package com.aharou.feature.workspace.presentation.remote

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.core.ui.AppTextField
import com.aharou.feature.agent.domain.container.SshLoginKey
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow
import com.aharou.feature.settings.presentation.component.settingsPageBackground
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Shield

/** 私钥内容隐藏时的掩码占位。 */
private const val MASKED_KEY = "••••••••••••••••••••••••••••••••"

/**
 * 登录密钥编辑页。两种用途：
 * - [key] 为 null：新建（粘贴私钥），名称/口令/私钥内容均可填，保存后入库；
 * - [key] 非空：编辑已有密钥，可改名称与口令，私钥内容只读（默认隐藏、可点开查看）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginKeyEditorScreen(
    key: SshLoginKey?,
    onLoadPem: ((String?) -> Unit) -> Unit,
    onSave: (name: String, passphrase: String, content: String) -> Unit,
    onNavigateBack: () -> Unit
) {
    val isCreate = key == null
    var name by remember(key?.id) { mutableStateOf(key?.name.orEmpty()) }
    var passphrase by remember(key?.id) { mutableStateOf(key?.passphrase.orEmpty()) }
    var passphraseVisible by remember { mutableStateOf(false) }
    var content by remember(key?.id) { mutableStateOf("") }
    var pem by remember(key?.id) { mutableStateOf<String?>(null) }
    var pemVisible by remember { mutableStateOf(false) }

    val contentInvalid = isCreate && content.isNotBlank() && !content.contains("PRIVATE KEY")

    BackHandler { onNavigateBack() }
    LaunchedEffect(key?.id) {
        if (!isCreate) onLoadPem { pem = it }
    }

    Scaffold(
        containerColor = settingsPageBackground(),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = settingsPageBackground(),
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                title = {
                    Text(
                        stringResource(
                            if (isCreate) R.string.ssh_login_key_paste_title
                            else R.string.ssh_login_key_edit_title
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(FeatherIcons.ArrowLeft, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.md)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            if (key != null) {
                SettingsGroup {
                    SettingsRow(
                        icon = FeatherIcons.Shield,
                        title = stringResource(R.string.ssh_login_key_fingerprint_label),
                        subtitle = key.fingerprint ?: stringResource(R.string.ssh_login_key_encrypted)
                    )
                }
            }
            AppTextField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.ssh_login_key_name_label),
                placeholder = if (isCreate) stringResource(R.string.ssh_login_key_paste_name_placeholder) else null
            )
            AppTextField(
                value = passphrase,
                onValueChange = { passphrase = it },
                label = stringResource(R.string.remote_key_passphrase),
                placeholder = stringResource(R.string.ssh_login_key_passphrase_placeholder),
                visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    val image = if (passphraseVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                    IconButton(onClick = { passphraseVisible = !passphraseVisible }) {
                        Icon(image, stringResource(R.string.remote_toggle_password), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            )
            if (isCreate) {
                AppTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = stringResource(R.string.ssh_login_key_content_label),
                    placeholder = stringResource(R.string.ssh_login_key_paste_content_placeholder),
                    singleLine = false,
                    minLines = 5,
                    maxLines = 10,
                    isError = contentInvalid,
                    supportingText = if (contentInvalid) {
                        { Text(stringResource(R.string.ssh_login_key_paste_invalid)) }
                    } else null,
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.ssh_login_key_content_label),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { pemVisible = !pemVisible }) {
                            val image = if (pemVisible) FeatherIcons.Eye else FeatherIcons.EyeOff
                            Icon(image, stringResource(R.string.ssh_login_key_toggle_content), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(Radius.mdLarge),
                        color = MaterialTheme.semanticColors.cardSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        // 固定高度 + 内部滚动：切换显示/隐藏时私钥行数变化不会撑开页面。
                        Text(
                            text = if (pemVisible) pem.orEmpty() else MASKED_KEY,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(Spacing.md)
                        )
                    }
                }
            }
            Button(
                enabled = if (isCreate) content.isNotBlank() && !contentInvalid else name.isNotBlank(),
                onClick = { onSave(name, passphrase, content) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(Radius.mdLarge)
            ) {
                Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    stringResource(R.string.common_save),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}
