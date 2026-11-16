package com.aharou.feature.plugin.presentation

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.aharou.core.theme.AppRadiusSet
import com.aharou.core.theme.AppSemanticColors
import com.aharou.core.theme.Spacing
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 插件页面可见的设计 token。颜色、间距、圆角都从当前 Compose 主题计算，
 * 页面只依赖 CSS 变量；主题切换时宿主更新变量即可，不重新加载页面。
 *
 * 变量名是插件作者可见的稳定接口，改动等于破坏插件页面，新增变量只能追加。
 */
object PluginThemeTokens {

    fun isDark(scheme: ColorScheme): Boolean = scheme.background.luminance() < 0.5f

    fun variables(
        scheme: ColorScheme,
        semantic: AppSemanticColors,
        fontScale: Float,
        radius: AppRadiusSet
    ): Map<String, String> = linkedMapOf(
        "--aharou-bg" to scheme.background.toCss(),
        "--aharou-surface" to scheme.surface.toCss(),
        "--aharou-surface-variant" to scheme.surfaceVariant.toCss(),
        "--aharou-on-surface" to scheme.onSurface.toCss(),
        "--aharou-on-surface-variant" to scheme.onSurfaceVariant.toCss(),
        "--aharou-primary" to scheme.primary.toCss(),
        "--aharou-on-primary" to scheme.onPrimary.toCss(),
        "--aharou-primary-container" to scheme.primaryContainer.toCss(),
        "--aharou-primary-soft" to scheme.primary.copy(alpha = 0.12f).toCss(),
        "--aharou-tertiary" to scheme.tertiary.toCss(),
        "--aharou-tertiary-soft" to scheme.tertiary.copy(alpha = 0.12f).toCss(),
        "--aharou-outline" to scheme.outline.toCss(),
        "--aharou-outline-soft" to scheme.outline.copy(alpha = 0.12f).toCss(),
        "--aharou-error" to scheme.error.toCss(),
        "--aharou-success" to semantic.success.toCss(),
        "--aharou-success-soft" to semantic.success.copy(alpha = 0.12f).toCss(),
        "--aharou-warning" to semantic.warning.toCss(),
        "--aharou-warning-soft" to semantic.warning.copy(alpha = 0.12f).toCss(),
        "--aharou-page-bg" to semantic.pageBackground.toCss(),
        "--aharou-card-surface" to semantic.cardSurface.toCss(),
        "--aharou-subtle-text" to semantic.subtleText.toCss(),
        "--aharou-subtle-border" to semantic.subtleBorder.toCss(),
        "--aharou-space-xs" to px(Spacing.xs.value),
        "--aharou-space-sm" to px(Spacing.sm.value),
        "--aharou-space-md" to px(Spacing.md.value),
        "--aharou-space-lg" to px(Spacing.lg.value),
        "--aharou-space-xl" to px(Spacing.xl.value),
        "--aharou-radius-xs" to px(radius.xs.value),
        "--aharou-radius-sm" to px(radius.sm.value),
        "--aharou-radius-md" to px(radius.md.value),
        "--aharou-radius-md-large" to px(radius.mdLarge.value),
        "--aharou-radius-lg" to px(radius.lg.value),
        "--aharou-radius-xl" to px(radius.xl.value),
        "--aharou-font-scale" to String.format(Locale.US, "%.2f", fontScale),
        "--aharou-touch-target" to "44px",
        "--aharou-switch-track" to if (isDark(scheme)) "#334155" else "#E2E8F0",
        "--aharou-switch-thumb" to if (isDark(scheme)) "#F8FAFC" else "#FFFFFF",
        "--aharou-font-sans" to "system-ui, -apple-system, \"Noto Sans CJK SC\", sans-serif",
        "--aharou-font-mono" to "ui-monospace, \"Roboto Mono\", monospace",
        "--aharou-text-field-border" to if (isDark(scheme)) {
            scheme.outlineVariant.copy(alpha = 0.6f).toCss()
        } else {
            scheme.outlineVariant.toCss()
        },
        "--aharou-text-field-focused-bg" to if (isDark(scheme)) {
            scheme.surfaceVariant.copy(alpha = 0.8f).toCss()
        } else {
            semantic.cardSurface.toCss()
        },
        "--aharou-text-field-disabled-border" to scheme.outlineVariant
            .copy(alpha = if (isDark(scheme)) 0.3f else 0.5f).toCss(),
        "--aharou-text-field-disabled-text" to scheme.onSurface.copy(alpha = 0.38f).toCss(),
        "--aharou-dialog-surface" to scheme.surfaceContainerHigh.toCss()
    )

    fun css(variables: Map<String, String>): String =
        ":root{" + variables.entries.joinToString("") { (key, value) -> "$key:$value;" } + "}"

    /** 主题变化时注入页面；页面侧由 [aicode.js] 的 applyTheme 写到根元素。 */
    fun applyScript(variables: Map<String, String>): String {
        val obj = variables.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"$key\":\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        return "window.aharou&&window.aharou.applyTheme($obj)"
    }

    private fun px(value: Float): String = "${value.roundToInt()}px"

    private fun Color.toCss(): String {
        val r = (red * 255f).roundToInt().coerceIn(0, 255)
        val g = (green * 255f).roundToInt().coerceIn(0, 255)
        val b = (blue * 255f).roundToInt().coerceIn(0, 255)
        if (alpha >= 1f) return "#%02X%02X%02X".format(r, g, b)
        val a = String.format(Locale.US, "%.3f", alpha.coerceIn(0f, 1f))
        return "rgba($r,$g,$b,$a)"
    }
}
