package com.aharou.feature.settings.presentation.component

import android.content.Context
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
import com.aharou.R
import com.aharou.core.theme.Spacing
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.pet.PetOverlay
import com.aharou.feature.pet.PetOverlayService
import compose.icons.FeatherIcons
import compose.icons.feathericons.Lock
import compose.icons.feathericons.Smile
import compose.icons.feathericons.Smartphone

/**
 * 设置页「桌宠」分区。
 *
 * 开关启动 / 停止 [PetOverlayService]；未授权时引导去系统设置开启「显示在其他应用上层」。
 * 桌宠大小直接落到 `aharou_pet_prefs`，服务在跑时顺带通知它换图。
 */
@Composable
internal fun PetSection() {
    val context = LocalContext.current
    var permissionGranted by remember { mutableStateOf(petOverlayPermission(context)) }
    var enabled by remember { mutableStateOf(PetOverlayService.isEnabled(context)) }
    var size by remember { mutableStateOf(PetOverlay.readSize(context)) }
    var locked by remember { mutableStateOf(PetOverlay.readLocked(context)) }

    LifecycleResumeEffect(Unit) {
        permissionGranted = petOverlayPermission(context)
        enabled = PetOverlayService.isEnabled(context)
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

        SettingsGroup {
            SettingsRow(
                icon = FeatherIcons.Smartphone,
                title = stringResource(R.string.floating_grant),
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

private fun petSizeLabel(size: PetOverlay.Size): Int = when (size) {
    PetOverlay.Size.SMALL -> R.string.pet_size_small
    PetOverlay.Size.MEDIUM -> R.string.pet_size_medium
    PetOverlay.Size.LARGE -> R.string.pet_size_large
}

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
