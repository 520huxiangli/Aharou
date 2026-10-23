package com.aharou.feature.settings.presentation.component

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.R
import com.aharou.core.ui.AppSwitch
import com.aharou.feature.settings.data.repository.AssistantOverlaySettingsRepository
import com.aharou.feature.settings.data.repository.AssistantStatusRepository
import com.aharou.feature.voice.assistant.PowerKeyWatcherService
import compose.icons.FeatherIcons
import compose.icons.feathericons.Eye
import compose.icons.feathericons.Image
import compose.icons.feathericons.Power
import compose.icons.feathericons.Users
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 「数字助理」这一整块设置：当前状态 + 一键设置 + 屏幕数据权限 + 两个隐私开关。
 *
 * 单独成组件而不是塞进 [DefaultModelsSection] 的参数列表：它要读系统状态（当前的助手是谁、
 * 屏幕 appop 有没有被拒）、要能写系统设置，跟「默认模型」那些纯选择项不是一类东西。
 */
@Composable
internal fun AssistantDigitalSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deps = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, AssistantDigitalEntryPoint::class.java)
    }
    val status = deps.statusRepository()
    val overlay = deps.overlaySettings()

    // 状态要跟着系统变：用户可能在系统设置里换了助手、或回来后才把「使用屏幕和应用数据」打开。
    var snapshot by remember { mutableStateOf(status.snapshot()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { snapshot = status.snapshot() }

    val screenText by overlay.screenTextFlow.collectAsStateWithLifecycle(initialValue = true)
    val screenshot by overlay.screenshotFlow.collectAsStateWithLifecycle(initialValue = false)
    val powerKey by overlay.powerKeyFlow.collectAsStateWithLifecycle(initialValue = false)

    fun togglePowerKey() {
        scope.launch {
            val next = !overlay.getPowerKey()
            overlay.setPowerKey(next)
            if (next) PowerKeyWatcherService.start(context) else PowerKeyWatcherService.stop(context)
        }
    }

    fun openSystemPage() {
        val intent = status.openAssistantSettings()
        if (intent == null) {
            Toast.makeText(context, R.string.voice_assistant_open_failed, Toast.LENGTH_LONG).show()
            return
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Toast.makeText(context, R.string.voice_assistant_open_failed, Toast.LENGTH_LONG).show()
            }
    }

    SettingsRow(
        icon = FeatherIcons.Users,
        title = stringResource(R.string.assistant_digital_title),
        subtitle = stringResource(R.string.assistant_digital_summary),
    )
    SettingsDivider()
    SettingsRow(
        title = stringResource(R.string.assistant_digital_current_title),
        subtitle = when {
            snapshot.isSelf -> null
            snapshot.currentLabel != null -> stringResource(
                R.string.assistant_digital_current_other,
                snapshot.currentLabel.orEmpty()
            )
            else -> stringResource(R.string.assistant_digital_current_none)
        },
        onClick = { openSystemPage() },
        trailing = {
            androidx.compose.material3.Text(
                if (snapshot.isSelf) stringResource(R.string.assistant_digital_current_self)
                else stringResource(R.string.assistant_digital_current_set)
            )
        }
    )
    SettingsDivider()
    SettingsRow(
        title = stringResource(R.string.assistant_digital_screen_title),
        subtitle = stringResource(R.string.assistant_digital_screen_summary),
        onClick = { openSystemPage() },
        trailing = {
            androidx.compose.material3.Text(
                if (snapshot.assistStructureAllowed && snapshot.assistScreenshotAllowed) {
                    stringResource(R.string.assistant_digital_screen_allowed)
                } else {
                    stringResource(R.string.assistant_digital_screen_denied)
                }
            )
        }
    )
    SettingsDivider()
    SettingsRow(
        title = if (snapshot.isSelf) {
            stringResource(R.string.assistant_digital_applied_title)
        } else {
            stringResource(R.string.assistant_digital_open_system)
        },
        subtitle = if (snapshot.isSelf) {
            stringResource(R.string.assistant_digital_applied_hint)
        } else {
            stringResource(R.string.assistant_digital_open_system_hint)
        },
        onClick = { openSystemPage() },
    )
    SettingsDivider()
    SettingsRow(
        icon = FeatherIcons.Power,
        title = stringResource(R.string.assistant_power_key_title),
        subtitle = stringResource(R.string.assistant_power_key_summary),
        onClick = { togglePowerKey() },
        trailing = {
            AppSwitch(
                checked = powerKey,
                onCheckedChange = { togglePowerKey() }
            )
        }
    )
    SettingsDivider()
    SettingsRow(
        icon = FeatherIcons.Eye,
        title = stringResource(R.string.assistant_overlay_screen_text_title),
        subtitle = stringResource(R.string.assistant_overlay_screen_text_summary),
        onClick = { scope.launch { overlay.setScreenText(!screenText) } },
        trailing = {
            AppSwitch(
                checked = screenText,
                onCheckedChange = { scope.launch { overlay.setScreenText(!screenText) } }
            )
        }
    )
    SettingsDivider()
    SettingsRow(
        icon = FeatherIcons.Image,
        title = stringResource(R.string.assistant_overlay_screenshot_title),
        subtitle = stringResource(R.string.assistant_overlay_screenshot_summary),
        onClick = { scope.launch { overlay.setScreenshot(!screenshot) } },
        trailing = {
            AppSwitch(
                checked = screenshot,
                onCheckedChange = { scope.launch { overlay.setScreenshot(!screenshot) } }
            )
        }
    )
    SettingsDivider()
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AssistantDigitalEntryPoint {
    fun statusRepository(): AssistantStatusRepository
    fun overlaySettings(): AssistantOverlaySettingsRepository
}
