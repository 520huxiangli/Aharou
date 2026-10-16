package com.aharou.feature.plugin.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.plugin.domain.model.InstalledPlugin
import com.aharou.feature.plugin.domain.model.PluginStatus
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Upload

/**
 * 插件二级页：导入 `.txplugin` 包、查看已安装插件、启停常驻服务、卸载。
 *
 * 插件本体在容器里跑（不是往 App 里塞代码），所以这里只负责「装 / 起 / 停 / 卸」四件事与状态展示。
 */
@Composable
internal fun PluginSettingsSection(
    viewModel: PluginViewModel = hiltViewModel(),
) {
    val plugins by viewModel.plugins.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val event by viewModel.event.collectAsStateWithLifecycle()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importAndInstall(uri)
    }
    var pendingUninstall by remember { mutableStateOf<InstalledPlugin?>(null) }

    // 进入页面时刷新一次状态（端口探测反映最新运行态）
    LaunchedEffect(Unit) { viewModel.refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg)
                .padding(top = Spacing.sm, bottom = Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            event?.let { PluginEventText(it) }

            SettingsGroup {
                SettingsRow(
                    icon = FeatherIcons.Upload,
                    title = stringResource(R.string.plugin_import),
                    subtitle = stringResource(R.string.plugin_import_hint),
                    enabled = busyId == null,
                    onClick = { importLauncher.launch(arrayOf("*/*")) }
                )
            }

            if (plugins.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.plugin_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                plugins.forEach { plugin ->
                    PluginCard(
                        plugin = plugin,
                        busy = busyId == plugin.manifest.id,
                        anyBusy = busyId != null,
                        onLaunch = { viewModel.launch(plugin.manifest.id) },
                        onStop = { viewModel.stop(plugin.manifest.id) },
                        onUninstall = { pendingUninstall = plugin }
                    )
                }
            }
        }
    }

    pendingUninstall?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingUninstall = null },
            title = { Text(stringResource(R.string.plugin_uninstall_title)) },
            text = { Text(stringResource(R.string.plugin_uninstall_message, plugin.manifest.name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.uninstall(plugin.manifest.id)
                    pendingUninstall = null
                }) { Text(stringResource(R.string.plugin_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingUninstall = null }) {
                    Text(stringResource(R.string.plugin_cancel))
                }
            }
        )
    }
}

/** 一张插件卡片：名称 + 状态、版本/作者、说明、权限提示、操作行。 */
@Composable
private fun PluginCard(
    plugin: InstalledPlugin,
    busy: Boolean,
    anyBusy: Boolean,
    onLaunch: () -> Unit,
    onStop: () -> Unit,
    onUninstall: () -> Unit,
) {
    val manifest = plugin.manifest
    SettingsGroup {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = manifest.name,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (manifest.isService) {
                    val running = plugin.status == PluginStatus.RUNNING
                    Text(
                        text = stringResource(
                            if (running) R.string.plugin_status_running else R.string.plugin_status_stopped
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (running) MaterialTheme.colorScheme.primary
                        else MaterialTheme.semanticColors.subtleText
                    )
                }
            }

            val meta = remember(plugin) {
                listOfNotNull(
                    manifest.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                    manifest.publisher.takeIf { it.isNotBlank() },
                    manifest.id.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            }
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (manifest.description.isNotBlank()) {
                Text(
                    text = manifest.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (manifest.permissions.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.plugin_permissions,
                        manifest.permissions.joinToString("、")
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.semanticColors.subtleText
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    Text(
                        text = stringResource(R.string.plugin_busy),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                } else if (manifest.isService) {
                    if (plugin.status == PluginStatus.RUNNING) {
                        TextButton(onClick = onStop, enabled = !anyBusy) {
                            Text(stringResource(R.string.plugin_action_stop))
                        }
                    } else {
                        TextButton(onClick = onLaunch, enabled = !anyBusy) {
                            Text(stringResource(R.string.plugin_action_start))
                        }
                    }
                } else {
                    Text(
                        text = stringResource(R.string.plugin_no_service),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.semanticColors.subtleText
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onUninstall, enabled = !anyBusy) {
                    Text(stringResource(R.string.plugin_action_uninstall))
                }
            }
        }
    }
}

/** 一次操作的结果提示。 */
@Composable
private fun PluginEventText(event: PluginEvent) {
    val text = when (event) {
        is PluginEvent.Installed -> stringResource(R.string.plugin_event_installed, event.name, event.version)
        PluginEvent.Completed -> stringResource(R.string.plugin_event_completed)
        is PluginEvent.Failed -> stringResource(R.string.plugin_event_failed, event.reason)
    }
    val isError = event is PluginEvent.Failed
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
