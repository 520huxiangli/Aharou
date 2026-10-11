package com.aharou.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * 把皮肤 Token 映射到 Material 槽位与语义色。
 *
 * 皮肤把长相参数给全了，所以这里不做"由种子推导"，只做映射：
 * 规范之外的槽位（secondary / tertiary / info）取皮肤里语义最接近的值，不引入新色。
 */
fun AppSkinTokens.toThemeColors(dark: Boolean): AppThemeColors =
    AppThemeColors(colorScheme = toColorScheme(dark), semanticColors = toSemanticColors(dark))

private fun AppSkinTokens.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primarySoft,
        onPrimaryContainer = primary,
        secondary = gradientEnd,
        onSecondary = onPrimary,
        secondaryContainer = primarySoft,
        onSecondaryContainer = primary,
        tertiary = success,
        onTertiary = onPrimary,
        tertiaryContainer = successSoft,
        onTertiaryContainer = success,
        background = pageBackground,
        onBackground = text,
        surface = card,
        onSurface = text,
        surfaceVariant = cardMuted,
        onSurfaceVariant = textSecondary,
        // 皮肤自带整页底色，不再叠加 M3 的高度染色。
        surfaceTint = Color.Transparent,
        surfaceContainerLowest = if (dark) pageBackground else card,
        surfaceContainerLow = if (dark) card else pageBackground,
        surfaceContainer = if (dark) card else pageBackground,
        surfaceContainerHigh = cardMuted,
        surfaceContainerHighest = lerp(cardMuted, text, 0.06f),
        surfaceBright = if (dark) lerp(cardMuted, text, 0.10f) else card,
        surfaceDim = if (dark) pageBackground else cardMuted,
        inverseSurface = text,
        inverseOnSurface = card,
        outline = border,
        outlineVariant = divider,
        error = danger,
        onError = onPrimary,
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        scrim = scrim
    )
}

private fun AppSkinTokens.toSemanticColors(dark: Boolean): AppSemanticColors {
    val overlayAlpha = if (dark) 0.20f else 0.15f
    return AppSemanticColors(
        success = success,
        onSuccess = onPrimary,
        successContainer = successSoft,
        onSuccessContainer = success,
        warning = warning,
        onWarning = onPrimary,
        warningContainer = warningSoft,
        onWarningContainer = warning,
        info = gradientEnd,
        onInfo = onPrimary,
        infoContainer = primarySoft,
        onInfoContainer = primary,
        diffAdd = success,
        diffAddBg = success.copy(alpha = overlayAlpha),
        diffRemove = danger,
        diffRemoveBg = danger.copy(alpha = overlayAlpha),
        subtleText = textSecondary,
        subtleBorder = divider,
        cardSurface = card,
        pageBackground = pageBackground,
        mutedSurface = cardMuted,
        capsuleSurface = cardMuted,
        buttonMutedBg = cardMuted
    )
}
