package com.aharou.feature.plugin.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.feature.plugin.domain.InstalledPlugin
import com.aharou.feature.plugin.domain.PluginUiEntry
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader

/**
 * 详情页的「界面」区块：把插件声明的每个交互入口直接列出来，点「打开」就进去。
 *
 * 为什么不在详情页里做完整逻辑：`PluginsSection` 已经顶在行数基线上，多写十几行就会编不过。
 * 这块整个搬出来，详情页只留一行调用。
 *
 * 入口来自清单的 `contributes.uiEntries`：`screen` 是 Compose DSL 面板，`page` 是 WebView 页面。
 * 前者在这里内嵌打开；后者仍需走宿主页面的覆盖层，所以只给提示不接管。
 */
@Composable
internal fun PluginUiEntriesSection(
    plugin: InstalledPlugin,
    languageTag: String,
    viewModel: PluginsViewModel = hiltViewModel()
) {
    val entries = plugin.content.uiEntries
    if (entries.isEmpty()) return
    val openable = plugin.isActive && plugin.status == com.aharou.feature.plugin.domain.PluginStatus.OK
    var opened by remember(plugin.id) { mutableStateOf<PluginUiEntry?>(null) }

    SettingsGroupHeader(text = stringResource(R.string.plugins_ui_entries_section))
    SettingsGroup {
        entries.forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title.resolve(languageTag),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = stringResource(
                            if (openable) {
                                if (entry.screen != null) R.string.plugins_ui_entry_kind_panel
                                else R.string.plugins_ui_entry_kind_page
                            } else {
                                R.string.plugins_ui_entry_need_enable
                            }
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                TextButton(
                    onClick = { opened = entry },
                    enabled = openable && entry.screen != null
                ) {
                    Text(stringResource(R.string.plugins_ui_entry_open))
                }
            }
        }
    }

    opened?.screen?.let { screen ->
        Dialog(
            onDismissRequest = { opened = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            PluginDslPanelScreen(
                page = PluginDslPage(
                    pluginId = plugin.id,
                    localId = opened?.localId.orEmpty(),
                    title = opened?.title ?: com.aharou.feature.plugin.domain.LocalizedText(
                        mapOf("default" to plugin.displayName(languageTag))
                    ),
                    screen = screen
                ),
                languageTag = languageTag,
                viewModel = viewModel,
                onClose = { opened = null }
            )
        }
    }
}
