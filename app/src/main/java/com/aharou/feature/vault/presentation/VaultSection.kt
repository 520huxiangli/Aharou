package com.aharou.feature.vault.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppTextField
import com.aharou.core.ui.SwipeToDeleteRow
import com.aharou.core.ui.dialogTextFieldColors
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import com.aharou.feature.vault.VaultRepository
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus

/**
 * 设置页「凭据库」分区：通用密钥库的增删。列表只显示名字与 `{{vault:名字}}` 占位符，
 * 真值加密落盘、永不回显；模型侧只能引用占位符，由接受密钥的工具在用时解析。
 */
@Composable
internal fun VaultSection(viewModel: VaultViewModel = hiltViewModel()) {
    val names by viewModel.names.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl)
    ) {
        SettingsGroupHeader(text = stringResource(R.string.settings_vault))
        SettingsGroup {
            Text(
                text = stringResource(R.string.vault_intro),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.vault_list_header))
        if (names.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.vault_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp)
                )
            }
        } else {
            SettingsGroup {
                names.forEachIndexed { index, name ->
                    if (index > 0) SettingsDivider()
                    SwipeToDeleteRow(onDelete = { deleting = name }) {
                        VaultRow(name = name)
                    }
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.vault_add),
                onClick = { showAdd = true }
            )
        }
    }

    if (showAdd) {
        VaultEditDialog(
            existing = names,
            onDismiss = { showAdd = false },
            onSave = { name, value ->
                viewModel.save(name, value)
                showAdd = false
            }
        )
    }

    deleting?.let { name ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.vault_delete_title, name)) },
            text = { Text(stringResource(R.string.vault_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(name)
                    deleting = null
                }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** 单条凭据行：名字 + `{{vault:名字}}` 占位符；值不显示，删除走左滑。 */
@Composable
private fun VaultRow(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "{{vault:$name}}",
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 新增凭据表单：名字 + 值；名字需符合仓库的名字规则且不与已有重名。 */
@Composable
private fun VaultEditDialog(
    existing: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val errEmpty = stringResource(R.string.vault_error_empty)
    val errFormat = stringResource(R.string.vault_error_format)
    val errDuplicate = stringResource(R.string.vault_error_duplicate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vault_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = stringResource(R.string.vault_name),
                    singleLine = true,
                    isError = error != null,
                    colors = dialogTextFieldColors()
                )
                AppTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = stringResource(R.string.vault_value),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    colors = dialogTextFieldColors()
                )
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = name.trim()
                error = when {
                    normalized.isEmpty() || value.isEmpty() -> errEmpty
                    !VaultRepository.NAME_REGEX.matches(normalized) -> errFormat
                    normalized in existing -> errDuplicate
                    else -> null
                }
                if (error == null) onSave(normalized, value)
            }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}
