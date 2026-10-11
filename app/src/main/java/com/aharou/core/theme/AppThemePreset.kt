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
 * 的 23 个语义色全部由 [deriveDynamicThemeColors] 推导，供系统莫奈取色使用。
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
 * 可选的应用皮肤：由 [AppSkinDefinition] 展开成浅色与深色两版。
 *
 * 皮肤自带明暗（[brightness]），另一侧按需派生——所以用户既能直接用皮肤自带的那一版，
 * 也能用手动明暗开关取另一版。
 */
data class AppThemePreset(
    val id: String,
    @param:StringRes val nameRes: Int,
    /** 自定义皮肤的名字；预设为 null，显示时走 [nameRes]。 */
    val customName: String? = null,
    val brightness: SkinBrightness,
    val lightSkin: AppSkinTokens,
    val darkSkin: AppSkinTokens,
    val light: AppThemeColors,
    val dark: AppThemeColors
) {
    /** 选择器色块预览：主色与页面底色。 */
    fun previewColors(dark: Boolean): Pair<Color, Color> {
        val skin = if (dark) darkSkin else lightSkin
        return skin.primary to skin.pageBackground
    }

    companion object {
        val ALL_PRESETS: List<AppThemePreset> = AppSkins.all.map { fromDefinition(it) }

        val DEFAULT: AppThemePreset = findById(null)

        fun findById(id: String?): AppThemePreset {
            val definition = AppSkins.findByIdOrNull(id) ?: AppSkins.Default
            return ALL_PRESETS.first { it.id == definition.id }
        }

        /** 预设与用户自定义皮肤统一查找；都找不到时回退默认皮肤。 */
        fun findById(id: String?, customSkins: List<CustomSkin>): AppThemePreset =
            customSkins.firstOrNull { it.id == id }?.toThemePreset() ?: findById(id)

        /** 自定义皮肤走这里构造，与预设共用同一套明暗展开逻辑。 */
        internal fun custom(id: String, name: String, skin: AppSkinTokens): AppThemePreset =
            fromTokens(
                id = id,
                nameRes = R.string.skin_custom,
                customName = name,
                brightness = SkinBrightness.LIGHT,
                tokens = skin
            )

        private fun fromDefinition(definition: AppSkinDefinition): AppThemePreset =
            fromTokens(
                id = definition.id,
                nameRes = definition.nameRes,
                customName = null,
                brightness = definition.brightness,
                tokens = definition.tokens
            )

        /** 把「皮肤自带明暗的那一版」展开成浅色与深色两套配色。 */
        private fun fromTokens(
            id: String,
            @StringRes nameRes: Int,
            customName: String?,
            brightness: SkinBrightness,
            tokens: AppSkinTokens
        ): AppThemePreset {
            val isLightSkin = brightness == SkinBrightness.LIGHT
            val lightSkin = if (isLightSkin) tokens else tokens.oppositeVariant(brightness)
            val darkSkin = if (isLightSkin) tokens.oppositeVariant(brightness) else tokens
            return AppThemePreset(
                id = id,
                nameRes = nameRes,
                customName = customName,
                brightness = brightness,
                lightSkin = lightSkin,
                darkSkin = darkSkin,
                light = lightSkin.toThemeColors(dark = false),
                dark = darkSkin.toThemeColors(dark = true)
            )
        }
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
