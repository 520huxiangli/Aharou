package com.aicode.feature.settings.presentation.component

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.aicode.core.ui.AppSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.agent.domain.shizuku.ShizukuState
import compose.icons.FeatherIcons
import compose.icons.feathericons.Bell
import compose.icons.feathericons.Calendar
import compose.icons.feathericons.Camera
import compose.icons.feathericons.Clock
import compose.icons.feathericons.HardDrive
import compose.icons.feathericons.MapPin
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Phone
import compose.icons.feathericons.Sliders
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Users
import compose.icons.feathericons.Download
import compose.icons.feathericons.Folder
import compose.icons.feathericons.Power
import compose.icons.feathericons.RefreshCw
import compose.icons.feathericons.Sun
import compose.icons.feathericons.Terminal
import compose.icons.feathericons.Zap

/**
 * 「软件权限」二级页：集中展示并管理系统级权限。
 * - 安装未知应用：展示授权状态，未授权点击跳转系统设置开启。
 * - 访问存储空间：展示授权状态，未授权点击申请运行时权限；已被永久拒绝时同样引导去系统设置。
 * - 忽略电池优化 / 自启动管理：跳转系统设置。
 * - Shizuku：展示 adb shell 授权状态，未就绪时点击安装/启动/申请授权。
 * 页面恢复（含从系统设置页或 Shizuku 应用返回）时刷新各权限状态。
 */
@Composable
internal fun AppPermissionsSection(
    shizukuState: ShizukuState,
    onRequestShizukuPermission: () -> Unit,
    onOpenShizuku: () -> Unit,
    onRefreshShizuku: () -> Unit
) {
    val context = LocalContext.current

    var apkInstallAllowed by remember { mutableStateOf(context.packageManager.canRequestPackageInstalls()) }
    var storageGranted by remember { mutableStateOf(checkStorageGranted(context)) }
    var batteryExempt by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }

    var showStorageDeniedDialog by remember { mutableStateOf(false) }

    // 从系统权限设置页返回时刷新状态。
    LifecycleResumeEffect(Unit) {
        apkInstallAllowed = context.packageManager.canRequestPackageInstalls()
        storageGranted = checkStorageGranted(context)
        batteryExempt = isIgnoringBatteryOptimizations(context)
        onRefreshShizuku()
        onPauseOrDispose { }
    }

    val storageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        storageGranted = checkStorageGranted(context)
        // 被拒且系统不再弹授权框（永久拒绝）→ 引导去系统设置手动开启。
        val permanentlyDenied = result.filterValues { !it }.keys.any {
            !shouldShowRationale(context, it)
        }
        if (!storageGranted && permanentlyDenied) showStorageDeniedDialog = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── [Aharou] 运行时权限总览 ──
        var permsTick by remember { mutableStateOf(0) }
        LifecycleResumeEffect(Unit) {
            permsTick++
            onPauseOrDispose { }
        }
        val runtimePermLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permsTick++ }
        fun permGranted(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        val runtimePermRows = listOf(
            RuntimePermRow(Manifest.permission.CAMERA, R.string.permissions_camera, R.string.permissions_camera_desc, FeatherIcons.Camera),
            RuntimePermRow(Manifest.permission.RECORD_AUDIO, R.string.permissions_mic, R.string.permissions_mic_desc, FeatherIcons.Mic),
            RuntimePermRow(Manifest.permission.ACCESS_FINE_LOCATION, R.string.permissions_location, R.string.permissions_location_desc, FeatherIcons.MapPin),
            RuntimePermRow(Manifest.permission.READ_CONTACTS, R.string.permissions_contacts, R.string.permissions_contacts_desc, FeatherIcons.Users),
            RuntimePermRow(Manifest.permission.READ_CALENDAR, R.string.permissions_calendar, R.string.permissions_calendar_desc, FeatherIcons.Calendar),
            RuntimePermRow(Manifest.permission.READ_PHONE_STATE, R.string.permissions_phone, R.string.permissions_phone_desc, FeatherIcons.Phone),
        )
        // 读取 tick 以订阅「从系统页返回 / 申请回调」后的状态刷新
        permsTick.let { }

        SettingsGroupHeader(text = stringResource(R.string.permissions_runtime_header))
        SettingsGroup {
            runtimePermRows.forEachIndexed { index, row ->
                if (index > 0) SettingsDivider()
                val grantedNow = permGranted(row.permission)
                SettingsRow(
                    icon = row.icon,
                    title = stringResource(row.titleRes),
                    subtitle = stringResource(row.descRes),
                    onClick = {
                        if (!grantedNow) {
                            if (!shouldShowRationale(context, row.permission)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                            Uri.parse("package:${context.packageName}")
                                        )
                                    )
                                }
                            } else {
                                runtimePermLauncher.launch(arrayOf(row.permission))
                            }
                        }
                    },
                    trailing = { PermissionStatusText(allowed = grantedNow) }
                )
            }
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.permissions_grant_all),
                onClick = {
                    val missing = runtimePermRows.map { it.permission }.filter { !permGranted(it) }
                    if (missing.isNotEmpty()) runtimePermLauncher.launch(missing.toTypedArray())
                }
            )
        }

        // ── [Aharou] 特殊系统权限 + 保活白名单 ──
        SettingsGroupHeader(text = stringResource(R.string.permissions_special_header))
        SettingsGroup {
            val overlayGranted = Settings.canDrawOverlays(context)
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.permissions_overlay),
                subtitle = stringResource(R.string.permissions_overlay_desc),
                onClick = {
                    if (!overlayGranted) {
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
                trailing = { PermissionStatusText(allowed = overlayGranted) }
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                SettingsDivider()
                val allFilesGranted = Environment.isExternalStorageManager()
                SettingsRow(
                    icon = FeatherIcons.HardDrive,
                    title = stringResource(R.string.permissions_all_files),
                    subtitle = stringResource(R.string.permissions_all_files_desc),
                    onClick = {
                        if (!allFilesGranted) {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    },
                    trailing = { PermissionStatusText(allowed = allFilesGranted) }
                )
            }
            SettingsDivider()
            val writeSettingsGranted = Settings.System.canWrite(context)
            SettingsRow(
                icon = FeatherIcons.Sliders,
                title = stringResource(R.string.permissions_write_settings),
                subtitle = stringResource(R.string.permissions_write_settings_desc),
                onClick = {
                    if (!writeSettingsGranted) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = { PermissionStatusText(allowed = writeSettingsGranted) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Power,
                title = stringResource(R.string.permissions_battery_whitelist),
                subtitle = stringResource(R.string.permissions_battery_whitelist_desc),
                onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                },
                trailing = {
                    Text(
                        text = stringResource(R.string.permissions_open_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Clock,
                title = stringResource(R.string.permissions_usage_access),
                subtitle = stringResource(R.string.permissions_usage_access_desc),
                onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                },
                trailing = {
                    Text(
                        text = stringResource(R.string.permissions_open_settings),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.settings_category_system_permissions))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Download,
                title = stringResource(R.string.settings_permission_apk_install),
                subtitle = stringResource(R.string.settings_permission_apk_install_desc),
                onClick = {
                    if (!apkInstallAllowed) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = { PermissionStatusText(allowed = apkInstallAllowed) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Folder,
                title = stringResource(R.string.settings_permission_storage),
                subtitle = stringResource(R.string.settings_permission_storage_desc),
                onClick = {
                    if (!storageGranted) {
                        storageLauncher.launch(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            )
                        )
                    }
                },
                trailing = { PermissionStatusText(allowed = storageGranted) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Zap,
                title = stringResource(R.string.settings_permission_battery),
                subtitle = stringResource(R.string.settings_permission_battery_desc),
                onClick = {
                    if (!batteryExempt) {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    }
                },
                trailing = { PermissionStatusText(allowed = batteryExempt) }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Power,
                title = stringResource(R.string.settings_autostart_title),
                subtitle = stringResource(R.string.settings_autostart_subtitle),
                onClick = { OemAutoStartGuide.openAutoStartSettings(context) }
            )
            SettingsDivider()
            val shizukuAction: (() -> Unit)? = when (shizukuState) {
                ShizukuState.NOT_INSTALLED, ShizukuState.NOT_RUNNING -> onOpenShizuku
                ShizukuState.PERMISSION_DENIED -> onRequestShizukuPermission
                ShizukuState.READY -> null
            }
            SettingsRow(
                icon = FeatherIcons.Terminal,
                title = stringResource(R.string.settings_shizuku),
                subtitle = stringResource(shizukuState.hintRes()),
                onClick = shizukuAction,
                trailing = {
                    Text(
                        text = stringResource(shizukuState.statusRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )
        }
    }

    if (showStorageDeniedDialog) {
        PermissionDeniedDialog(
            title = stringResource(R.string.settings_permission_storage),
            message = stringResource(R.string.settings_permission_storage_denied_desc),
            onGoToSettings = { OemAutoStartGuide.openAppDetails(context) },
            onDismiss = { showStorageDeniedDialog = false }
        )
    }
}

/**
 * 「后台运行」二级页：保活、屏幕常亮、Agent 完成通知三个开关。
 * 两个需要通知权限的开关（保活、提示音）开启前先检测并申请通知权限（Android 13+），
 * 系统不再弹授权框时弹窗引导去系统设置手动开启。
 */
@Composable
internal fun BackgroundRunSection(
    keepaliveEnabled: Boolean,
    onToggleKeepalive: (Boolean) -> Unit,
    screenOnEnabled: Boolean,
    onToggleScreenOn: (Boolean) -> Unit,
    agentSoundEnabled: Boolean,
    onToggleAgentSound: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var showNotificationDeniedDialog by remember { mutableStateOf(false) }
    // 通知权限申请成功后要打开的目标开关回调。
    var pendingNotificationToggle by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }

    // targetSdk 28 在 Android 13+ 上申请通知权限系统不弹框、直接回调 denied，
    // 此时只能引导用户去系统设置手动开启。
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingNotificationToggle
        pendingNotificationToggle = null
        if (granted) {
            pending?.invoke(true)
        } else {
            showNotificationDeniedDialog = true
        }
    }

    fun toggleWithNotificationPermission(onToggle: (Boolean) -> Unit, enabled: Boolean) {
        if (!enabled || notificationsGranted(context)) {
            onToggle(enabled)
            return
        }
        pendingNotificationToggle = onToggle
        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.RefreshCw,
                title = stringResource(R.string.settings_keepalive_title),
                subtitle = stringResource(R.string.settings_keepalive_subtitle),
                trailing = {
                    AppSwitch(
                        checked = keepaliveEnabled,
                        onCheckedChange = { enabled ->
                            toggleWithNotificationPermission(onToggleKeepalive, enabled)
                        }
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Sun,
                title = stringResource(R.string.settings_screen_on_title),
                subtitle = stringResource(R.string.settings_screen_on_subtitle),
                trailing = {
                    AppSwitch(
                        checked = screenOnEnabled,
                        onCheckedChange = onToggleScreenOn
                    )
                }
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Bell,
                title = stringResource(R.string.settings_agent_sound_title),
                subtitle = stringResource(R.string.settings_agent_sound_subtitle),
                trailing = {
                    AppSwitch(
                        checked = agentSoundEnabled,
                        onCheckedChange = { enabled ->
                            toggleWithNotificationPermission(onToggleAgentSound, enabled)
                        }
                    )
                }
            )
        }
    }

    if (showNotificationDeniedDialog) {
        PermissionDeniedDialog(
            title = stringResource(R.string.settings_permission_notification_title),
            message = stringResource(R.string.settings_permission_notification_denied_desc),
            onGoToSettings = { OemAutoStartGuide.openAppDetails(context) },
            onDismiss = { showNotificationDeniedDialog = false }
        )
    }
}

/** Shizuku 状态对应的右侧状态文字。 */
@StringRes
private fun ShizukuState.statusRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_status_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_status_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_status_denied
    ShizukuState.READY -> R.string.settings_shizuku_status_ready
}

/** Shizuku 状态对应的副标题（点击提示）。 */
@StringRes
private fun ShizukuState.hintRes(): Int = when (this) {
    ShizukuState.NOT_INSTALLED -> R.string.settings_shizuku_hint_not_installed
    ShizukuState.NOT_RUNNING -> R.string.settings_shizuku_hint_not_running
    ShizukuState.PERMISSION_DENIED -> R.string.settings_shizuku_hint_denied
    ShizukuState.READY -> R.string.settings_shizuku_hint_ready
}

/** 权限无法通过系统弹框授予时，提醒用户去系统设置手动开启。 */
@Composable
private fun PermissionDeniedDialog(
    title: String,
    message: String,
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onGoToSettings()
                }
            ) {
                Text(stringResource(R.string.common_go_to_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}

/** Android 13+ 需通知权限；更低版本无需。 */
private fun notificationsGranted(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

/** 系统是否还会为权限弹授权框（false 且未授予即永久拒绝）。 */
private fun shouldShowRationale(context: android.content.Context, permission: String): Boolean =
    (context as? android.app.Activity)?.shouldShowRequestPermissionRationale(permission) ?: false

/** 权限行右侧状态文字：已允许 / 未允许。 */
@Composable
private fun PermissionStatusText(allowed: Boolean) {
    Text(
        text = stringResource(
            if (allowed) R.string.settings_permission_granted else R.string.settings_permission_denied
        ),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

private fun checkStorageGranted(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

/** [Aharou] 权限总览行定义。 */
private data class RuntimePermRow(
    val permission: String,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)