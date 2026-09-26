package com.aharou.core.theme

import android.os.Build
import androidx.annotation.StringRes
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.aharou.R

/** 一套主题在某个明暗模式下的完整配色。 */
data class AppThemeColors(
    val colorScheme: ColorScheme,
    val semanticColors: AppSemanticColors
)

/**
 * 推导整套配色所需的最小种子集。
 *
 * 只描述"这套主题长什么样"的 10 个关键色，其余 Material 槽位与 [AppSemanticColors]
 * 的 23 个语义色全部由 [deriveThemeColors] 推导，新增主题不必逐个手调。
 */
data class AppThemeSeed(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val outline: Color,
    val outlineVariant: Color
)

/**
 * 可选的应用配色主题。
 *
 * [DEFAULT] 直接沿用手调的品牌蓝配色，其余主题由种子推导——保证升级后默认观感不变。
 */
data class AppThemePreset(
    val id: String,
    @param:StringRes val nameRes: Int,
    val light: AppThemeColors,
    val dark: AppThemeColors
) {
    /** 选择器色块预览：主色与页面底色。 */
    fun previewColors(dark: Boolean): Pair<Color, Color> {
        val colors = if (dark) this.dark else this.light
        return colors.colorScheme.primary to colors.semanticColors.pageBackground
    }

    companion object {
        /** 品牌蓝：历史默认配色，颜色值逐个手调，不走推导。 */
        val DEFAULT = AppThemePreset(
            id = "default",
            nameRes = R.string.theme_preset_default,
            light = AppThemeColors(LightColorScheme, LightSemanticColors),
            dark = AppThemeColors(DarkColorScheme, DarkSemanticColors)
        )

        /** 咖啡：暖赤陶 + 米白纸感，取自 Anthropic 品牌色。 */
        val COFFEE = fromSeeds(
            id = "coffee",
            nameRes = R.string.theme_preset_coffee,
            lightSeed = AppThemeSeed(
                primary = Color(0xFFC15F3C),
                secondary = Color(0xFF6A9BCC),
                tertiary = Color(0xFF788C5D),
                background = Color(0xFFF5F3EC),
                surface = Color(0xFFFCFBF7),
                surfaceVariant = Color(0xFFEDEAE0),
                onSurface = Color(0xFF141413),
                onSurfaceVariant = Color(0xFF6B6761),
                outline = Color(0xFFB0AEA5),
                outlineVariant = Color(0xFFE4E0D4)
            ),
            darkSeed = AppThemeSeed(
                primary = Color(0xFFE08A6B),
                secondary = Color(0xFF8FB4DB),
                tertiary = Color(0xFF9AAF7C),
                background = Color(0xFF1A1917),
                surface = Color(0xFF232220),
                surfaceVariant = Color(0xFF2E2C29),
                onSurface = Color(0xFFFAF9F5),
                onSurfaceVariant = Color(0xFFB0AEA5),
                outline = Color(0xFF6B6862),
                outlineVariant = Color(0xFF373734)
            )
        )

        /** 石墨：中性冷灰，弱化色彩干扰。 */
        val GRAPHITE = fromSeeds(
            id = "graphite",
            nameRes = R.string.theme_preset_graphite,
            lightSeed = AppThemeSeed(
                primary = Color(0xFF3F4650),
                secondary = Color(0xFF6B7280),
                tertiary = Color(0xFF0E7490),
                background = Color(0xFFF2F3F5),
                surface = Color(0xFFFBFBFC),
                surfaceVariant = Color(0xFFE9EAED),
                onSurface = Color(0xFF17191C),
                onSurfaceVariant = Color(0xFF5C6169),
                outline = Color(0xFFA6ABB3),
                outlineVariant = Color(0xFFDFE1E5)
            ),
            darkSeed = AppThemeSeed(
                primary = Color(0xFFB9C0CC),
                secondary = Color(0xFF8B93A0),
                tertiary = Color(0xFF22D3EE),
                background = Color(0xFF101114),
                surface = Color(0xFF191B1F),
                surfaceVariant = Color(0xFF23262B),
                onSurface = Color(0xFFECEDEF),
                onSurfaceVariant = Color(0xFF9BA1AA),
                outline = Color(0xFF5E646D),
                outlineVariant = Color(0xFF2F333A)
            )
        )

        /** 森林：低饱和绿，长时间阅读代码不刺眼。 */
        val FOREST = fromSeeds(
            id = "forest",
            nameRes = R.string.theme_preset_forest,
            lightSeed = AppThemeSeed(
                primary = Color(0xFF15803D),
                secondary = Color(0xFF0F766E),
                tertiary = Color(0xFFB45309),
                background = Color(0xFFF1F7F2),
                surface = Color(0xFFFBFDFB),
                surfaceVariant = Color(0xFFE3EFE5),
                onSurface = Color(0xFF12211A),
                onSurfaceVariant = Color(0xFF4F6357),
                outline = Color(0xFF9CB4A3),
                outlineVariant = Color(0xFFD8E7DB)
            ),
            darkSeed = AppThemeSeed(
                primary = Color(0xFF4ADE80),
                secondary = Color(0xFF2DD4BF),
                tertiary = Color(0xFFFBBF24),
                background = Color(0xFF0A1410),
                surface = Color(0xFF12201A),
                surfaceVariant = Color(0xFF1B2E24),
                onSurface = Color(0xFFE6F2E9),
                onSurfaceVariant = Color(0xFF9CB8A6),
                outline = Color(0xFF567A63),
                outlineVariant = Color(0xFF263B2F)
            )
        )

        val ALL_PRESETS = listOf(DEFAULT, COFFEE, GRAPHITE, FOREST)

        fun findById(id: String?): AppThemePreset =
            ALL_PRESETS.firstOrNull { it.id == id } ?: DEFAULT

        private fun fromSeeds(
            id: String,
            @StringRes nameRes: Int,
            lightSeed: AppThemeSeed,
            darkSeed: AppThemeSeed
        ) = AppThemePreset(
            id = id,
            nameRes = nameRes,
            light = deriveThemeColors(lightSeed, dark = false),
            dark = deriveThemeColors(darkSeed, dark = true)
        )
    }
}

/** 系统级动态取色（莫奈）自 Android 12 起提供，低版本设备读不到壁纸调色板。 */
val isDynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/** 功能色基准：success / warning / info 的色相跨主题固定，只随明暗切换，避免"成功"在某套主题里变成别的颜色。 */
private object FunctionalHues {
    val LightSuccess = Color(0xFF22C55E)
    val LightWarning = Color(0xFFF59E0B)
    val LightInfo = Color(0xFF0284C7)
    val LightError = Color(0xFFDC2626)

    val DarkSuccess = Color(0xFF4ADE80)
    val DarkWarning = Color(0xFFFBBF24)
    val DarkInfo = Color(0xFF38BDF8)
    val DarkError = Color(0xFFF87171)
}

/** 由种子推导完整的 Material 槽位与语义色。 */
fun deriveThemeColors(seed: AppThemeSeed, dark: Boolean): AppThemeColors =
    AppThemeColors(
        colorScheme = deriveColorScheme(seed, dark),
        semanticColors = deriveSemanticColors(seed, dark)
    )

/**
 * 莫奈取色：系统只提供 Material 槽位，语义色需要从中反推，否则会出现
 * 主色跟着壁纸变、卡片底色和状态色还停在旧主题的割裂感。
 */
fun deriveDynamicThemeColors(scheme: ColorScheme, dark: Boolean): AppThemeColors {
    val seed = AppThemeSeed(
        primary = scheme.primary,
        secondary = scheme.secondary,
        tertiary = scheme.tertiary,
        background = scheme.background,
        surface = scheme.surface,
        surfaceVariant = scheme.surfaceVariant,
        onSurface = scheme.onSurface,
        onSurfaceVariant = scheme.onSurfaceVariant,
        outline = scheme.outline,
        outlineVariant = scheme.outlineVariant
    )
    return AppThemeColors(
        // surfaceTint 保持与自有主题一致，避免 M3 组件按高度叠加染色。
        colorScheme = scheme.copy(surfaceTint = if (dark) Color.Transparent else scheme.surface),
        semanticColors = deriveSemanticColors(seed, dark)
    )
}

private fun deriveColorScheme(seed: AppThemeSeed, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val error = if (dark) FunctionalHues.DarkError else FunctionalHues.LightError
    return base.copy(
        primary = seed.primary,
        onPrimary = seed.primary.contentColor(seed, dark),
        primaryContainer = seed.primary.container(seed, dark),
        onPrimaryContainer = seed.primary.onContainer(dark),
        secondary = seed.secondary,
        onSecondary = seed.secondary.contentColor(seed, dark),
        secondaryContainer = seed.secondary.container(seed, dark),
        onSecondaryContainer = seed.secondary.onContainer(dark),
        tertiary = seed.tertiary,
        onTertiary = seed.tertiary.contentColor(seed, dark),
        tertiaryContainer = seed.tertiary.container(seed, dark),
        onTertiaryContainer = seed.tertiary.onContainer(dark),
        background = seed.background,
        onBackground = seed.onSurface,
        surface = seed.surface,
        onSurface = seed.onSurface,
        surfaceVariant = seed.surfaceVariant,
        onSurfaceVariant = seed.onSurfaceVariant,
        surfaceTint = if (dark) Color.Transparent else seed.surface,
        // M3 组件（BottomSheet / Dialog / DropdownMenu）默认取 surfaceContainer* 而不是 surface，
        // 不覆盖会露出 Material 基线的纯白与淡紫。
        surfaceContainerLowest = if (dark) lerp(seed.background, Color.Black, 0.25f) else seed.surface,
        surfaceContainerLow = if (dark) seed.background else lerp(seed.surface, seed.background, 0.45f),
        surfaceContainer = if (dark) seed.surface else seed.background,
        surfaceContainerHigh = seed.surfaceVariant,
        surfaceContainerHighest = lerp(seed.surfaceVariant, seed.onSurface, if (dark) 0.08f else 0.05f),
        surfaceBright = if (dark) lerp(seed.surface, seed.onSurface, 0.12f) else seed.surface,
        surfaceDim = if (dark) seed.background else lerp(seed.background, seed.onSurface, 0.10f),
        inverseSurface = seed.onSurface,
        inverseOnSurface = seed.surface,
        outline = seed.outline,
        outlineVariant = seed.outlineVariant,
        error = error,
        onError = error.contentColor(seed, dark),
        errorContainer = error.container(seed, dark),
        onErrorContainer = error.onContainer(dark),
        scrim = Color.Black
    )
}

private fun deriveSemanticColors(seed: AppThemeSeed, dark: Boolean): AppSemanticColors {
    val success = if (dark) FunctionalHues.DarkSuccess else FunctionalHues.LightSuccess
    val warning = if (dark) FunctionalHues.DarkWarning else FunctionalHues.LightWarning
    val info = if (dark) FunctionalHues.DarkInfo else FunctionalHues.LightInfo
    val error = if (dark) FunctionalHues.DarkError else FunctionalHues.LightError
    val overlayAlpha = if (dark) 0.20f else 0.15f

    return AppSemanticColors(
        success = success,
        onSuccess = success.contentColor(seed, dark),
        successContainer = success.container(seed, dark),
        onSuccessContainer = success.onContainer(dark),
        warning = warning,
        onWarning = warning.contentColor(seed, dark),
        warningContainer = warning.container(seed, dark),
        onWarningContainer = warning.onContainer(dark),
        info = info,
        onInfo = info.contentColor(seed, dark),
        infoContainer = info.container(seed, dark),
        onInfoContainer = info.onContainer(dark),
        diffAdd = success,
        diffAddBg = success.copy(alpha = overlayAlpha),
        diffRemove = error,
        diffRemoveBg = error.copy(alpha = overlayAlpha),
        subtleText = lerp(seed.onSurfaceVariant, seed.surface, 0.25f),
        subtleBorder = seed.outlineVariant,
        cardSurface = seed.surface,
        pageBackground = seed.background,
        mutedSurface = seed.surfaceVariant,
        capsuleSurface = lerp(seed.surfaceVariant, seed.onSurface, 0.07f),
        buttonMutedBg = lerp(seed.surfaceVariant, seed.onSurface, 0.03f)
    )
}

/** 该色作背景时，其上文字取近白还是近黑——按自身亮度判定，并混入主题色温以免出现纯黑白。 */
private fun Color.contentColor(seed: AppThemeSeed, dark: Boolean): Color {
    val light = if (dark) seed.onSurface else seed.surface
    val deep = if (dark) seed.background else seed.onSurface
    return if (luminance() > 0.45f) lerp(deep, this, 0.12f) else lerp(light, this, 0.08f)
}

/** 该色的容器底：向主题表面色靠拢，让状态色融进当前主题而不是突兀的独立色块。 */
private fun Color.container(seed: AppThemeSeed, dark: Boolean): Color =
    lerp(this, seed.surface, if (dark) 0.78f else 0.86f)

/** 容器底之上的文字：浅色模式压深、深色模式提亮，保证与 [container] 的对比度。 */
private fun Color.onContainer(dark: Boolean): Color =
    if (dark) lerp(this, Color.White, 0.30f) else lerp(this, Color.Black, 0.45f)
