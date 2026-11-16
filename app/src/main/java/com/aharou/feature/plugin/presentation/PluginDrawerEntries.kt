package com.aharou.feature.plugin.presentation

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.aharou.R
import com.aharou.feature.settings.presentation.component.SettingsDivider
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsGroupHeader
import com.aharou.feature.settings.presentation.component.SettingsRow
import compose.icons.FeatherIcons
import compose.icons.feathericons.Archive

/** 抽屉底部的插件页面入口；最多三条，其余引导到「设置 → 插件」。 */
@Composable
internal fun PluginDrawerEntries(host: PluginHostState?) {
    val entries = host?.slots?.drawer ?: return
    SettingsGroupHeader(text = stringResource(R.string.settings_plugins))
    SettingsGroup {
        entries.take(3).forEachIndexed { index, entry ->
            if (index > 0) SettingsDivider()
            SettingsRow(
                icon = pluginPageIcon(entry.icon),
                title = entry.title.resolve(host.languageTag),
                subtitle = stringResource(R.string.plugins_ext_entry_subtitle, entry.pluginId),
                onClick = { host.open(entry) }
            )
        }
        if (entries.isNotEmpty()) SettingsDivider()
        SettingsRow(
            icon = FeatherIcons.Archive,
            title = stringResource(R.string.plugins_all_tools),
            onClick = { host.openToolbox() }
        )
    }
}
