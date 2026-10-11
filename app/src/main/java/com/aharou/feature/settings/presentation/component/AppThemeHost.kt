package com.aharou.feature.settings.presentation.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aharou.core.theme.AIEditorTheme
import com.aharou.core.theme.AppThemePreset
import com.aharou.core.theme.SkinBrightness
import com.aharou.feature.agent.data.repository.ChatAppearance
import com.aharou.feature.agent.data.repository.ChatAppearanceRepository
import com.aharou.feature.agent.presentation.component.LocalChatAppearance
import com.aharou.feature.settings.data.repository.AppThemeMode
import com.aharou.feature.settings.data.repository.ThemeSettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** 外观决议结果：最终皮肤、明暗、莫奈开关与聊天气泡外观。 */
data class AppAppearance(
    val preset: AppThemePreset,
    val darkTheme: Boolean,
    val dynamicColor: Boolean,
    val chatAppearance: ChatAppearance
)

/**
 * 把「皮肤自带明暗」与「明暗模式开关」合成最终结果。
 *
 * 自动模式尊重皮肤自带明暗（夜墨、玄青在任何系统设置下都是深色）；
 * 手动选浅色或深色时取该皮肤对应的那一版，另一版由皮肤派生。
 */
@Composable
fun rememberAppAppearance(themeSettings: ThemeSettingsRepository): AppAppearance {
    val context = LocalContext.current
    val chatRepository = remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, ChatAppearanceEntryPoint::class.java)
            .chatAppearanceRepository()
    }
    val themeMode by themeSettings.themeModeFlow
        .collectAsStateWithLifecycle(initialValue = AppThemeMode.AUTO)
    val skinId by themeSettings.themePresetIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val customSkins by themeSettings.customSkinsFlow
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val dynamicColor by themeSettings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
    val systemDark = isSystemInDarkTheme()
    val preset = AppThemePreset.findById(skinId, customSkins)
    val darkTheme = remember(themeMode, preset, systemDark) {
        when (themeMode) {
            AppThemeMode.AUTO -> preset.brightness == SkinBrightness.DARK || systemDark
            AppThemeMode.DARK -> true
            AppThemeMode.LIGHT -> false
        }
    }
    val chatAppearance by chatRepository.settingsFlow
        .collectAsStateWithLifecycle(initialValue = ChatAppearance())
    return AppAppearance(
        preset = preset,
        darkTheme = darkTheme,
        dynamicColor = dynamicColor,
        chatAppearance = chatAppearance
    )
}

/** 按外观决议包裹全局主题，并把聊天气泡外观下发给消息区。 */
@Composable
fun AppThemeHost(appearance: AppAppearance, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalChatAppearance provides appearance.chatAppearance) {
        AIEditorTheme(
            darkTheme = appearance.darkTheme,
            preset = appearance.preset,
            dynamicColor = appearance.dynamicColor,
            content = content
        )
    }
}

/** 外观宿主直读聊天气泡仓库：这条链路不经过行数已满的视图模型。 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ChatAppearanceEntryPoint {
    fun chatAppearanceRepository(): ChatAppearanceRepository
}
