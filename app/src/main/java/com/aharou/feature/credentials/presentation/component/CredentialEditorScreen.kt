package com.aharou.feature.credentials.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.aharou.core.ui.AdaptiveModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.feature.credentials.domain.model.GitCredential
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check
import compose.icons.feathericons.Eye
import compose.icons.feathericons.EyeOff

/**
 * 凭据编辑 BottomSheet 弹窗：从底部弹出编辑/新增 host、用户名、Token。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CredentialEditorSheet(
    initial: GitCredential?,
    onDismiss: () -> Unit,
    onSave: (GitCredential) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var username by remember(initial) { mutableStateOf(initial?.username ?: "") }
    var token by remember(initial) { mutableStateOf(initial?.token ?: "") }
    var tokenVisible by remember { mutableStateOf(false) }

    val canSave = host.trim().isNotBlank() && username.trim().isNotBlank() && token.isNotBlank()

    fun current(): GitCredential? {
        if (!canSave) return null
        val h = host.trim()
        return GitCredential(
            id = h.lowercase(),
            host = h,
            username = username.trim(),
            token = token
        )
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .padding(bottom = Spacing.xl)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Text(
                text = if (initial == null) stringResource(R.string.credential_add) else stringResource(R.string.credential_edit),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.xs)
            )

            CredentialField(
                value = host,
                onValueChange = { host = it },
                label = stringResource(R.string.credential_host),
                placeholder = "github.com"
            )
            CredentialField(
                value = username,
                onValueChange = { username = it },
                label = stringResource(R.string.common_username)
            )
            CredentialField(
                value = token,
                onValueChange = { token = it },
                label = stringResource(R.string.credential_token),
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(if (tokenVisible) FeatherIcons.EyeOff else FeatherIcons.Eye, contentDescription = if (tokenVisible) stringResource(R.string.common_hide) else stringResource(R.string.common_show))
                    }
                }
            )

            Button(
                onClick = {
                    current()?.let {
                        onSave(it)
                        onDismiss()
                    }
                },
                enabled = canSave,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(FeatherIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (initial != null) stringResource(R.string.common_save) else stringResource(R.string.common_add),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
            }
        }
    }
}

/** 统一使用全局 AppTextField 组件。 */
@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    trailingIcon: (@Composable () -> Unit)? = null
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        placeholder = placeholder,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        trailingIcon = trailingIcon,
        modifier = modifier.fillMaxWidth()
    )
}
