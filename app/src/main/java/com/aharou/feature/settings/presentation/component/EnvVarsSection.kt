package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.settings.data.repository.EnvVarRepository
import com.aharou.feature.settings.presentation.EnvVarsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Plus
import compose.icons.feathericons.Trash2

/**
 * 设置页「环境变量」分区（自 上游项目 的 Environment Variables 移植·适配）。
 *
 * 列表 + 添加/编辑/删除；值加密存储、默认打码；注入到容器内所有命令与终端。
 */
@Composable
internal fun EnvVarsSection(viewModel: EnvVarsViewModel = hiltViewModel()) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EnvVarRepository.EnvVar?>(null) }
    var deleting by remember { mutableStateOf<EnvVarRepository.EnvVar?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.envvars_title))
        SettingsGroup {
            Text(
                text = stringResource(R.string.envvars_intro),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.envvars_list_header))
        if (entries.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.envvars_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                entries.forEachIndexed { index, entry ->
                    if (index > 0) SettingsDivider()
                    EnvVarRow(
                        entry = entry,
                        onClick = { editing = entry },
                        onDelete = { deleting = entry },
                        onToggleEnabled = { enabled -> viewModel.setEnabled(entry.name, enabled) },
                    )
                }
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Plus,
                title = stringResource(R.string.envvars_add),
                onClick = { showAdd = true },
            )
        }
    }

    if (showAdd) {
        EnvVarEditDialog(
            initial = null,
            existing = entries,
            onDismiss = { showAdd = false },
            onSave = { name, value, secret ->
                viewModel.upsert(name, value, secret)
                showAdd = false
            },
        )
    }
    editing?.let { target ->
        EnvVarEditDialog(
            initial = target,
            existing = entries,
            onDismiss = { editing = null },
            onSave = { name, value, secret ->
                viewModel.upsert(name, value, secret)
                editing = null
            },
        )
    }
    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.envvars_delete_title, target.name)) },
            text = { Text(stringResource(R.string.envvars_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(target.name)
                    deleting = null
                }) {
                    Text(
                        text = stringResource(R.string.envvars_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun EnvVarRow(
    entry: EnvVarRepository.EnvVar,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.lg, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                // 停用态置灰，一眼能看出该变量不再注入容器。
                .alpha(if (entry.enabled) 1f else 0.45f),
        ) {
            Text(
                text = entry.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (entry.secret) "••••••••" else entry.value,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!entry.enabled) {
            Text(
                text = stringResource(R.string.envvars_disabled),
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        AppSwitch(
            checked = entry.enabled,
            onCheckedChange = onToggleEnabled,
        )
        Spacer(Modifier.width(Spacing.xs))
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = FeatherIcons.Trash2,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun EnvVarEditDialog(
    initial: EnvVarRepository.EnvVar?,
    existing: List<EnvVarRepository.EnvVar>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var value by remember { mutableStateOf(initial?.value ?: "") }
    var secret by remember { mutableStateOf(initial?.secret ?: true) }
    var error by remember { mutableStateOf<String?>(null) }

    val errEmpty = stringResource(R.string.envvars_error_empty)
    val errFormat = stringResource(R.string.envvars_error_format)
    val errReserved = stringResource(R.string.envvars_error_reserved, name.trim())
    val errDuplicate = stringResource(R.string.envvars_error_duplicate)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial == null) R.string.envvars_add else R.string.envvars_edit_title
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text(stringResource(R.string.envvars_name)) },
                    singleLine = true,
                    enabled = initial == null,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it; error = null },
                    label = { Text(stringResource(R.string.envvars_value)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = secret, onCheckedChange = { secret = it })
                    Text(text = stringResource(R.string.envvars_secret), fontSize = 13.sp)
                }
                error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val normalized = name.trim()
                error = when {
                    normalized.isEmpty() -> errEmpty
                    !EnvVarRepository.NAME_REGEX.matches(normalized) -> errFormat
                    normalized in EnvVarRepository.RESERVED_NAMES -> errReserved
                    existing.any { it.name == normalized && it.name != initial?.name } -> errDuplicate
                    else -> null
                }
                if (error == null) onSave(normalized, value, secret)
            }) {
                Text(stringResource(R.string.envvars_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        },
    )
}
