package com.aharou.feature.settings.presentation.component

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.pet.PetDailyBrief
import com.aharou.feature.pet.PetMood
import com.aharou.feature.pet.PetMoodStore
import com.aharou.feature.pet.PetOverlay
import com.aharou.feature.pet.PetOverlayService
import compose.icons.FeatherIcons
import compose.icons.feathericons.Bell
import compose.icons.feathericons.CloudRain
import compose.icons.feathericons.EyeOff
import compose.icons.feathericons.Heart
import compose.icons.feathericons.Lock
import compose.icons.feathericons.MapPin
import compose.icons.feathericons.Mic
import compose.icons.feathericons.Smile
import compose.icons.feathericons.Smartphone
import compose.icons.feathericons.Calendar

/**
 * 设置页「小染」分区：屏幕上的悬浮伙伴。
 *
 * 她同时承担原来的「状态胶囊」职责（Agent 进度、通话字幕、通话开关），所以这一页合并了
 * 旧「悬浮窗」分区的权限引导与 OEM 提示，不再单列一页。
 *
 * 两个开关分工：
 *  - **显示小染**：总开关，关掉等于停掉悬浮服务；
 *  - **常驻待机**：关掉后她只在 Agent 跑任务或通话时出现（等同旧胶囊的行为）。
 */
@Composable
internal fun PetSection() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(petOverlayPermission(context)) }
    var enabled by remember { mutableStateOf(PetOverlayService.isEnabled(context)) }
    var always by remember { mutableStateOf(petAlways(context)) }
    var size by remember { mutableStateOf(PetOverlay.readSize(context)) }
    var locked by remember { mutableStateOf(PetOverlay.readLocked(context)) }
    var petMood by remember { mutableStateOf(PetMoodStore.read(context)) }
    var passThrough by remember { mutableStateOf(PetOverlay.readPassThrough(context)) }
    var brief by remember { mutableStateOf(PetDailyBrief.readEnabled(context)) }
    var locationGranted by remember { mutableStateOf(PetDailyBrief.hasLocation(context)) }
    var calendarGranted by remember { mutableStateOf(PetDailyBrief.hasCalendar(context)) }
    var micGranted by remember { mutableStateOf(granted(context, Manifest.permission.RECORD_AUDIO)) }
    var notifGranted by remember { mutableStateOf(notificationsGranted(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        micGranted = granted(context, Manifest.permission.RECORD_AUDIO)
        notifGranted = notificationsGranted(context)
        locationGranted = PetDailyBrief.hasLocation(context)
        calendarGranted = PetDailyBrief.hasCalendar(context)
    }

    LifecycleResumeEffect(Unit) {
        permissionGranted = petOverlayPermission(context)
        enabled = PetOverlayService.isEnabled(context)
        petMood = PetMoodStore.read(context)
        passThrough = PetOverlay.readPassThrough(context)
        locationGranted = PetDailyBrief.hasLocation(context)
        calendarGranted = PetDailyBrief.hasCalendar(context)
        micGranted = granted(context, Manifest.permission.RECORD_AUDIO)
        notifGranted = notificationsGranted(context)
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Spacing.xl),
    ) {
        SettingsGroupHeader(text = stringResource(R.string.pet_title))
        SettingsGroup {
            Text(
                text = stringResource(R.string.pet_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
            )
        }

        SettingsGroupHeader(text = stringResource(R.string.pet_perms_header))
        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.floating_grant),
                subtitle = stringResource(R.string.pet_perm_overlay_why),
                onClick = {
                    if (!permissionGranted) openOverlaySettings(context)
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
                icon = FeatherIcons.Mic,
                title = stringResource(R.string.pet_perm_mic),
                subtitle = stringResource(R.string.pet_perm_mic_why),
                onClick = {
                    if (!micGranted) {
                        permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    }
                },
                trailing = {
                    Text(
                        text = stringResource(
                            if (micGranted) R.string.floating_status_granted
                            else R.string.floating_status_denied
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Bell,
                    title = stringResource(R.string.pet_perm_notif),
                    subtitle = stringResource(R.string.pet_perm_notif_why),
                    onClick = {
                        if (!notifGranted) {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                            )
                        }
                    },
                    trailing = {
                        Text(
                            text = stringResource(
                                if (notifGranted) R.string.floating_status_granted
                                else R.string.floating_status_denied
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.pet_enable),
                trailing = {
                    AppSwitch(
                        checked = enabled,
                        onCheckedChange = { turnOn ->
                            if (turnOn) {
                                if (petOverlayPermission(context)) {
                                    PetOverlayService.setEnabled(context, true)
                                    PetOverlayService.start(context)
                                    enabled = true
                                } else {
                                    // 没权限：先去授权（开关保持关闭）
                                    openOverlaySettings(context)
                                }
                            } else {
                                PetOverlayService.setEnabled(context, false)
                                PetOverlayService.stop(context)
                                enabled = false
                            }
                        },
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Smile,
                title = stringResource(R.string.pet_always),
                subtitle = stringResource(R.string.pet_always_hint),
                trailing = {
                    AppSwitch(
                        checked = always,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            PetOverlayService.applyAlways(context, value)
                            always = value
                        },
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Smile,
                title = stringResource(R.string.pet_size),
                subtitle = stringResource(R.string.pet_size_hint),
                onClick = {
                    val next = when (size) {
                        PetOverlay.Size.SMALL -> PetOverlay.Size.MEDIUM
                        PetOverlay.Size.MEDIUM -> PetOverlay.Size.LARGE
                        PetOverlay.Size.LARGE -> PetOverlay.Size.SMALL
                    }
                    PetOverlayService.applySize(context, next)
                    size = next
                },
                trailing = {
                    Text(
                        text = stringResource(petSizeLabel(size)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Lock,
                title = stringResource(R.string.pet_lock),
                subtitle = stringResource(R.string.pet_lock_hint),
                trailing = {
                    AppSwitch(
                        checked = locked,
                        onCheckedChange = { value ->
                            PetOverlay.writeLocked(context, value)
                            locked = value
                        },
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.Heart,
                title = stringResource(R.string.pet_mood),
                subtitle = stringResource(R.string.pet_mood_hint),
                trailing = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LinearProgressIndicator(
                            progress = { petMood.affection / 100f },
                            modifier = Modifier.width(64.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                        )
                        Text(
                            text = stringResource(petMoodLabel(petMood.level)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.EyeOff,
                title = stringResource(R.string.pet_pass),
                subtitle = stringResource(R.string.pet_pass_hint),
                trailing = {
                    AppSwitch(
                        checked = passThrough,
                        enabled = enabled,
                        onCheckedChange = { value ->
                            PetOverlayService.applyPassThrough(context, value)
                            passThrough = value
                        },
                    )
                },
            )
            SettingsDivider()
            SettingsRow(
                icon = FeatherIcons.CloudRain,
                title = stringResource(R.string.pet_brief),
                subtitle = stringResource(R.string.pet_brief_hint),
                trailing = {
                    AppSwitch(
                        checked = brief,
                        onCheckedChange = { value ->
                            PetDailyBrief.writeEnabled(context, value)
                            brief = value
                        },
                    )
                },
            )
            if (brief) {
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.MapPin,
                    title = stringResource(R.string.pet_brief_location),
                    onClick = {
                        if (!locationGranted) {
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                )
                            )
                        }
                    },
                    trailing = {
                        Text(
                            text = stringResource(
                                if (locationGranted) R.string.pet_brief_location_granted
                                else R.string.pet_brief_location_denied
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                SettingsDivider()
                SettingsRow(
                    icon = FeatherIcons.Calendar,
                    title = stringResource(R.string.pet_brief_calendar),
                    onClick = {
                        if (!calendarGranted) {
                            permissionLauncher.launch(
                                arrayOf(Manifest.permission.READ_CALENDAR)
                            )
                        }
                    },
                    trailing = {
                        Text(
                            text = stringResource(
                                if (calendarGranted) R.string.pet_brief_calendar_granted
                                else R.string.pet_brief_calendar_denied
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }

        SettingsGroup {
            Text(
                text = stringResource(R.string.pet_menu_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp),
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

private fun granted(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Android 13 起通知要单独授权，再早的版本装了就有。 */
private fun notificationsGranted(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        granted(context, Manifest.permission.POST_NOTIFICATIONS)

private fun petMoodLabel(level: PetMood.Level): Int = when (level) {
    PetMood.Level.COLD -> R.string.pet_mood_cold
    PetMood.Level.NORMAL -> R.string.pet_mood_normal
    PetMood.Level.CLOSE -> R.string.pet_mood_close
    PetMood.Level.CLINGY -> R.string.pet_mood_clingy
}

private fun petSizeLabel(size: PetOverlay.Size): Int = when (size) {
    PetOverlay.Size.SMALL -> R.string.pet_size_small
    PetOverlay.Size.MEDIUM -> R.string.pet_size_medium
    PetOverlay.Size.LARGE -> R.string.pet_size_large
}

private fun petAlways(context: Context): Boolean =
    // 默认 true：总开关一开她就该出现在屏幕上，否则用户打开「显示小染」会觉得坏了。
    // 只想在 Agent 跑任务时看到她的人，自己关掉这一行就是旧胶囊的行为。
    context.getSharedPreferences(PetOverlay.PREFS, Context.MODE_PRIVATE)
        .getBoolean(PetOverlay.KEY_ALWAYS, true)

private fun petOverlayPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        )
    }
}
