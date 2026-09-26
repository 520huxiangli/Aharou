package com.aharou.feature.settings.presentation.component

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.accessibility.AharouAccessibilityService
import com.aharou.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.RefreshCw

/**
 * 设置页「无障碍」分区（自 上游项目 的无障碍引导移植·裁剪）。
 *
 * 展示服务状态（是否在系统设置里开启 / 是否已连接），提供去系统设置开启与刷新。
 * 无障碍服务让 Agent「看得懂、点得动」宿主屏幕（读节点树 / 手势 / 截图）。
 */
@Composable
internal fun AccessibilitySection() {
    val context = LocalContext.current
    var enabledInSettings by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(false) }

    fun refresh() {
        enabledInSettings = isServiceEnabledInSettings(context)
        connected = AharouAccessibilityService.isConnected()
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.a11y_settings_title))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
            ) {
                Text(
                    text = when {
                        enabledInSettings && connected -> stringResource(R.string.a11y_status_on_connected)
                        enabledInSettings -> stringResource(R.string.a11y_status_on_disconnected)
                        else -> stringResource(R.string.a11y_status_off)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.a11y_intro),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Eye,
                title = stringResource(R.string.a11y_open_settings),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.a11y_refresh),
                onClick = { refresh() },
            )
        }

        SettingsGroup {
            Text(
                text = stringResource(R.string.a11y_oem_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }
    }
}

/** 是否已在系统「无障碍」中勾选本服务。 */
private fun isServiceEnabledInSettings(context: android.content.Context): Boolean = runCatching {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    enabled.split(':').any {
        it.contains(context.packageName) && it.contains("AharouAccessibilityService")
    }
}.getOrDefault(false)
