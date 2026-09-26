package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aharou.R
import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import compose.icons.FeatherIcons
import compose.icons.feathericons.ArrowLeft
import compose.icons.feathericons.Star
import compose.icons.feathericons.Trash2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 设置页「记忆」分区（自 OpenMinis 的 MemoryManagementScreen 移植·适配）。
 *
 * 「默认启用记忆」开关（控制提示词注入）+ 记忆文件列表（名称/大小/时间）+ 预览 + 删除。
 * 文件位于 filesDir/aharou-global/memory（容器内 /root/.aharou/memory）。
 */
@Composable
internal fun MemorySection() {
    val context = LocalContext.current
    val store = remember { AharouMemoryStore(context.applicationContext) }
    var files by remember { mutableStateOf(store.listFiles()) }
    var enabled by remember { mutableStateOf(store.isMemoryEnabled()) }
    var detailName by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    LifecycleResumeEffect(Unit) {
        files = store.listFiles()
        enabled = store.isMemoryEnabled()
        onPauseOrDispose { }
    }

    val detail = detailName
    if (detail != null) {
        MemoryDetailPane(
            store = store,
            name = detail,
            onBack = { detailName = null },
            onDeleted = {
                detailName = null
                files = store.listFiles()
            },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.memory_settings_title))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Star,
                title = stringResource(R.string.memory_toggle_title),
                subtitle = stringResource(R.string.memory_toggle_subtitle),
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = { on ->
                            enabled = on
                            store.setMemoryEnabled(on)
                        },
                    )
                },
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.memory_files_header))
        if (files.isEmpty()) {
            SettingsGroup {
                Text(
                    text = stringResource(R.string.memory_empty),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 16.dp),
                )
            }
        } else {
            SettingsGroup {
                files.forEachIndexed { index, info ->
                    if (index > 0) SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { detailName = info.name }
                            .padding(horizontal = Spacing.lg, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = info.name,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = "${formatBytes(info.sizeBytes)} · ${formatDate(info.modifiedAt)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { deleteTarget = info.name }) {
                            Icon(
                                imageVector = FeatherIcons.Trash2,
                                contentDescription = stringResource(R.string.memory_delete),
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.memory_delete_title, target)) },
            text = { Text(stringResource(R.string.memory_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteFile(target)
                    deleteTarget = null
                    files = store.listFiles()
                }) {
                    Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun MemoryDetailPane(
    store: AharouMemoryStore,
    name: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
) {
    var showDelete by remember { mutableStateOf(false) }
    val content = remember(name) { store.readFile(name) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.ArrowLeft,
                title = stringResource(R.string.memory_back),
                onClick = onBack,
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Trash2,
                title = stringResource(R.string.memory_delete),
                onClick = { showDelete = true },
            )
        }

        SettingsGroupHeader(text = name)
        SettingsGroup {
            Text(
                text = content ?: stringResource(R.string.memory_read_error),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.memory_delete_title, name)) },
            text = { Text(stringResource(R.string.memory_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    store.deleteFile(name)
                    showDelete = false
                    onDeleted()
                }) {
                    Text(stringResource(R.string.memory_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMs))
