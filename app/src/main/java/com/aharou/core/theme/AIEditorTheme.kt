package com.aharou.core.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/** 圆角档位集合。圆角是皮肤的性格参数，整套一起平移，禁止与别的皮肤混搭。 */
data class AppRadiusSet(
    val xs: Dp,
    val sm: Dp,
    val md: Dp,
    val mdLarge: Dp,
    val lg: Dp,
    val xl: Dp
) {
    companion object {
        /** 规范青蓝基准（8 / 12 / 16）折算到本项目既有档位后的取值。 */
        val Base = AppRadiusSet(
            xs = 4.dp,
            sm = 8.dp,
            md = 10.dp,
            mdLarge = 12.dp,
            lg = 14.dp,
            xl = 16.dp
        )

        fun scaled(scale: Float): AppRadiusSet {
            if (scale == 1f) return Base
            fun step(value: Float) = (value * scale).dp
            return AppRadiusSet(
                xs = step(4f),
                sm = step(8f),
                md = step(10f),
                mdLarge = step(12f),
                lg = step(14f),
                xl = step(16f)
            )
        }
    }
}

val LocalAppRadius = staticCompositionLocalOf { AppRadiusSet.Base }

/** 圆角随当前皮肤缩放；pill 是完整胶囊，不参与缩放。 */
object Radius {
    val xs: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.xs
    val sm: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.sm
    val md: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.md
    val mdLarge: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.mdLarge
    val lg: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.lg
    val xl: Dp
        @Composable @ReadOnlyComposable get() = LocalAppRadius.current.xl
    val pill: Dp get() = 999.dp
}

object Brand {
    val Blue = Color(0xFF2563EB)
    val Sky = Color(0xFF38BDF8)
    val IconGray = Color(0xFF64748B)
    val Orange = Color(0xFFF57C00)
}

/** 全局统一语义色彩，解决业务代码私自 hardcode 颜色问题。 */
data class AppSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val diffAdd: Color,
    val diffAddBg: Color,
    val diffRemove: Color,
    val diffRemoveBg: Color,
    val subtleText: Color,
    val subtleBorder: Color,
    val cardSurface: Color,
    val pageBackground: Color,
    val mutedSurface: Color,
    val capsuleSurface: Color,
    val buttonMutedBg: Color
)

val LightSemanticColors = AppSemanticColors(
    success = Color(0xFF22C55E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF15803D),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF92400E),
    info = Color(0xFF0284C7),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF075985),
    diffAdd = Color(0xFF22C55E),
    diffAddBg = Color(0x2622C55E),
    diffRemove = Color(0xFFEF4444),
    diffRemoveBg = Color(0x26EF4444),
    subtleText = Color(0xFF8E8E93),
    subtleBorder = Color(0xFFE5E5EA),
    cardSurface = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFF8F8F8),
    mutedSurface = Color(0xFFF2F2F7),
    capsuleSurface = Color(0xFFE9E9EB),
    buttonMutedBg = Color(0xFFF0F2F5)
)

val LocalAppSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.semanticColors: AppSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSemanticColors.current

/** 当前皮肤的长相参数，供需要 token 原值（光晕、渐变、投影）的组件直接取用。 */
val LocalAppSkin = staticCompositionLocalOf { AppSkins.Default.tokens }

val MaterialTheme.appSkin: AppSkinTokens
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSkin.current

/** Git 模块统一 8 色泳道调色板 */
object GitLanePalette {
    val colors = listOf(
        Color(0xFF2563EB), // 1. 经典蓝
        Color(0xFF16A34A), // 2. 翠绿
        Color(0xFFF59E0B), // 3. 琥珀
        Color(0xFF8B5CF6), // 4. 优雅紫
        Color(0xFF06B6D4), // 5. 青蓝
        Color(0xFFEF4444), // 6. 珊瑚红
        Color(0xFFEC4899), // 7. 亮粉
        Color(0xFFEAB308)  // 8. 柠檬金
    )
}

/** Git 状态徽章统一色彩映射 */
object GitStatusColors {
    val Added = Color(0xFF16A34A)
    val Modified = Color(0xFFD97706)
    val Deleted = Color(0xFFDC2626)
    val Renamed = Color(0xFF2563EB)
    val Untracked = Color(0xFF94A3B8)
    val Conflict = Color(0xFF9333EA)
    val TypeChanged = Color(0xFF0891B2)
    val Default = Color(0xFF64748B)
}

/** 日志级别统一色彩映射 */
object LogLevelColors {
    val Verbose = Color(0xFF94A3B8)
    val Debug = Color(0xFF3B82F6)
    val Info = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val None = Color(0xFF64748B)
}

/** Token / 统计图表统一调色板 */
object TokenStatsPalette {
    val Input = Color(0xFF3B82F6)
    val Output = Color(0xFFF59E0B)
    val Cache = Color(0xFF8B5CF6)
    val Error = Color(0xFFEF4444)
    val Cancelled = Color(0xFF94A3B8)
    val Progress = Color(0xFF22C55E)
}

/** 存储空间页各占用分类的色标（堆叠条与行前色点共用）。 */
object StorageUsagePalette {
    val Chat = Color(0xFF3B82F6)
    val Container = Color(0xFF8B5CF6)
    val ContainerImages = Color(0xFFA78BFA)
    val Workspaces = Color(0xFF22C55E)
    val AiConfig = Color(0xFF14B8A6)
    val VoiceModels = Color(0xFFEC4899)
    val PetAssets = Color(0xFFF43F5E)
    val Checkpoints = Color(0xFFF59E0B)
    val Logs = Color(0xFFF97316)
    val Caches = Color(0xFF94A3B8)
    val OtherData = Color(0xFFCBD5E1)
    val Apk = Color(0xFF64748B)
}

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    )
}

/**
 * @param preset 用户选定的配色主题。
 * @param dynamicColor 是否启用系统莫奈取色；低于 Android 12 时自动回退到 [preset]。
 */
@Composable
fun AIEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: AppThemePreset = AppThemePreset.DEFAULT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val themeColors = remember(darkTheme, preset, dynamicColor, context) {
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dynamicScheme =
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            deriveDynamicThemeColors(dynamicScheme, darkTheme)
        } else if (darkTheme) {
            preset.dark
        } else {
            preset.light
        }
    }

    // 莫奈取色只改颜色，皮肤仍负责圆角性格与光晕质感，所以这里始终取当前预设的皮肤。
    val skin = if (darkTheme) preset.darkSkin else preset.lightSkin
    val radius = remember(skin) { AppRadiusSet.scaled(skin.radiusScale) }
    val motion = rememberAppMotion()

    CompositionLocalProvider(
        LocalAppSemanticColors provides themeColors.semanticColors,
        LocalAppSkin provides skin,
        LocalAppRadius provides radius,
        LocalAppMotion provides motion
    ) {
        MaterialTheme(
            colorScheme = themeColors.colorScheme,
            typography = AppTypography,
            content = content
        )
    }
}
