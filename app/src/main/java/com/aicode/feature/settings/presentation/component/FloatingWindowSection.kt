package com.aicode.feature.settings.presentation.component

import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.overlay.FloatingToolService
import compose.icons.FeatherIcons
import compose.icons.feathericons.Smartphone

/**
 * 设置页「悬浮窗」分区（自 OpenMinis 的 Background 悬浮窗开关移植·裁剪）。
 *
 * 开关启动 / 停止 [FloatingToolService]；未授权时引导去系统设置开启「显示在其他应用上层」。
 */
@Composable
internal fun FloatingWindowSection() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(hasOverlayPermission(context)) }
    var enabled by remember { mutableStateOf(FloatingToolService.isEnabled(context)) }

    LifecycleResumeEffect(Unit) {
        permissionGranted = hasOverlayPermission(context)
        enabled = FloatingToolService.isEnabled(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.floating_title))
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.floating_hint),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.floating_grant),
                onClick = {
                    if (!permissionGranted) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = {
                    Text(
                        text = stringResource(
                            if (permissionGranted) R.string.floating_status_granted
                            else R.string.floating_status_denied
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.floating_enable),
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = { turnOn ->
                            if (turnOn) {
                                if (hasOverlayPermission(context)) {
                                    FloatingToolService.setEnabled(context, true)
                                    FloatingToolService.start(context)
                                    enabled = true
                                } else {
                                    // 没权限：先去授权（开关保持关闭）
                                    runCatching {
                                        context.startActivity(
                                            Intent(
                                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                                Uri.parse("package:${context.packageName}")
                                            )
                                        )
                                    }
                                }
                            } else {
                                FloatingToolService.setEnabled(context, false)
                                FloatingToolService.stop(context)
                                enabled = false
                            }
                        },
                    )
                },
            )
        }

        SettingsGroup {
            Text(
                text = stringResource(R.string.floating_oem_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }
    }
}

private fun hasOverlayPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
