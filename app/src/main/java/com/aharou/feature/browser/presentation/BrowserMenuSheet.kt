package com.aharou.feature.browser.presentation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.aharou.R
import com.aharou.core.ui.AdaptiveModalBottomSheet
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.browser.BrowserEruda
import com.aharou.feature.browser.BrowserTabPool
import com.aharou.feature.settings.presentation.component.SettingsGroup
import com.aharou.feature.settings.presentation.component.SettingsRow

/**
 * [T-android-browser-more-menu] 底栏「更多」二级菜单。
 *
 * 提供页内开发者工具（Eruda）开关与进入完整浏览器设置的入口。
 * UA 分档不在这里重复列出——统一在设置页（那边还有 UA 字符串与 Web Viewport 的联动）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserMenuSheet(
    tabPool: BrowserTabPool,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    AdaptiveModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.browser_menu_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
                var devToolsOn by remember { mutableStateOf(BrowserEruda.isEnabled(context)) }
                SettingsRow(
                    icon = Icons.Default.Build,
                    title = stringResource(R.string.browser_devtools),
                    subtitle = stringResource(R.string.browser_devtools_desc),
                    trailing = {
                        AppSwitch(
                            checked = devToolsOn,
                            onCheckedChange = { checked ->
                                devToolsOn = checked
                                BrowserEruda.setEnabled(
                                    context,
                                    checked,
                                    tabPool.tabs.value.map { it.manager.webView },
                                )
                            },
                        )
                    },
                )
            }
            SettingsGroup(modifier = Modifier.padding(top = 12.dp)) {
                SettingsRow(
                    icon = Icons.Default.Settings,
                    title = stringResource(R.string.browser_settings_title),
                    onClick = {
                        onDismiss()
                        onOpenSettings()
                    },
                )
            }
        }
    }
}
