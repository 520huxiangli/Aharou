package com.aharou.core.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min

/** 皮肤自带的明暗属性：皮肤本身就分明的暗，不由明暗模式二次决定。 */
enum class SkinBrightness { LIGHT, DARK }

/** 一层投影：颜色 + 模糊半径 + Y 偏移，对应设计规范里的 box-shadow。 */
@Immutable
data class AppShadow(
    val color: Color,
    val blur: Dp,
    val offsetY: Dp = 2.dp
) {
    /** Compose 的 shadow 只接受 elevation，用模糊半径折半近似。 */
    val elevation: Dp get() = blur / 2
}

/**
 * 一套皮肤的全部长相参数，字段与《UI 控件库 · 多皮肤设计规范 v1.0》的 Token 合同一一对应。
 *
 * 换皮肤只改这里：组件层禁止出现固定色值、固定圆角与固定时长。
 * 规范里的 `--primary-hover` 在触屏上映射为按压态 [primaryPressed]（触屏没有 hover 语义）。
 */
@Immutable
data class AppSkinTokens(
    val primary: Color,
    val primaryPressed: Color,
    val primarySoft: Color,
    val primarySoftPressed: Color,
    val onPrimary: Color,
    val pageBackground: Color,
    val card: Color,
    val cardMuted: Color,
    val text: Color,
    val textSecondary: Color,
    val textDisabled: Color,
    val border: Color,
    val divider: Color,
    val success: Color,
    val successSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val shadow: AppShadow,
    val shadowHover: AppShadow,
    val glow: AppShadow,
    val ring: Color,
    val scrim: Color,
    val gradientStart: Color,
    val gradientEnd: Color,
    /** 圆角性格系数：1.0 = 规范青蓝基准（8/12/16），其余档位按它整体平移。 */
    val radiusScale: Float = 1f
)

/**
 * 取该皮肤另一侧明暗的变体。
 *
 * 规范每套皮肤只声明它自己那一侧（青蓝等是浅色皮肤，夜墨/玄青是深色皮肤），
 * 但用户要能用手动明暗开关覆盖皮肤自带明暗，所以另一侧由这里派生：
 * 保留色相、重排明度阶梯，不用白蒙层糊背景，也不出现纯黑纯白。
 */
fun AppSkinTokens.oppositeVariant(brightness: SkinBrightness): AppSkinTokens =
    if (brightness == SkinBrightness.LIGHT) asDarkVariant() else asLightVariant()

private fun AppSkinTokens.asDarkVariant(): AppSkinTokens {
    val pageHue = pageBackground.hue()
    val pageSat = pageBackground.saturation()
    val textHue = text.hue()
    val textSat = text.saturation()
    val primaryLightness = (primary.lightness() + 0.14f).coerceAtMost(0.78f)
    val primaryColor = primary.withLightness(primaryLightness)
    return copy(
        primary = primaryColor,
        primaryPressed = primaryColor.withLightness((primaryLightness + 0.08f).coerceAtMost(0.88f)),
        primarySoft = primary.copy(alpha = 0.16f),
        primarySoftPressed = primary.copy(alpha = 0.26f),
        onPrimary = readableOn(primaryColor),
        pageBackground = hslColor(pageHue, pageSat * 0.50f, 0.065f),
        card = hslColor(pageHue, pageSat * 0.45f, 0.105f),
        cardMuted = hslColor(pageHue, pageSat * 0.42f, 0.145f),
        text = hslColor(textHue, min(textSat * 0.60f, 0.22f), 0.93f),
        textSecondary = hslColor(textHue, min(textSat * 0.50f, 0.18f), 0.66f),
        textDisabled = hslColor(textHue, min(textSat * 0.40f, 0.14f), 0.42f),
        border = hslColor(pageHue, pageSat * 0.50f, 0.24f),
        divider = hslColor(pageHue, pageSat * 0.45f, 0.17f),
        success = success.brighten(0.10f),
        successSoft = success.copy(alpha = 0.15f),
        warning = warning.brighten(0.06f),
        warningSoft = warning.copy(alpha = 0.15f),
        danger = danger.brighten(0.10f),
        dangerSoft = danger.copy(alpha = 0.15f),
        shadow = AppShadow(Color.Black.copy(alpha = 0.42f), 12.dp, 2.dp),
        shadowHover = AppShadow(Color.Black.copy(alpha = 0.55f), 24.dp, 6.dp),
        glow = AppShadow(primary.copy(alpha = 0.28f), 14.dp, 4.dp),
        ring = primary.copy(alpha = 0.30f),
        scrim = Color.Black.copy(alpha = 0.62f),
        gradientStart = gradientStart.brighten(0.10f),
        gradientEnd = gradientEnd.brighten(0.10f)
    )
}

private fun AppSkinTokens.asLightVariant(): AppSkinTokens {
    val pageHue = pageBackground.hue()
    val pageSat = pageBackground.saturation()
    val textHue = text.hue()
    val textSat = text.saturation()
    val primaryLightness = (primary.lightness() - 0.16f).coerceAtLeast(0.34f)
    val primaryColor = primary.withLightness(primaryLightness)
    return copy(
        primary = primaryColor,
        primaryPressed = primaryColor.withLightness((primaryLightness - 0.07f).coerceAtLeast(0.26f)),
        onPrimary = readableOn(primaryColor),
        primarySoft = primary.copy(alpha = 0.14f).compositeOver(Color.White),
        primarySoftPressed = primary.copy(alpha = 0.22f).compositeOver(Color.White),
        pageBackground = hslColor(pageHue, pageSat * 0.28f, 0.955f),
        card = hslColor(pageHue, pageSat * 0.18f, 0.995f),
        cardMuted = hslColor(pageHue, pageSat * 0.24f, 0.960f),
        text = hslColor(textHue, min(textSat * 0.60f, 0.22f), 0.14f),
        textSecondary = hslColor(textHue, min(textSat * 0.50f, 0.18f), 0.45f),
        textDisabled = hslColor(textHue, min(textSat * 0.40f, 0.14f), 0.68f),
        border = hslColor(pageHue, pageSat * 0.40f, 0.88f),
        divider = hslColor(pageHue, pageSat * 0.35f, 0.93f),
        success = success.darken(0.12f),
        successSoft = success.copy(alpha = 0.14f).compositeOver(Color.White),
        warning = warning.darken(0.04f),
        warningSoft = warning.copy(alpha = 0.16f).compositeOver(Color.White),
        danger = danger.darken(0.10f),
        dangerSoft = danger.copy(alpha = 0.14f).compositeOver(Color.White),
        shadow = AppShadow(hslColor(pageHue, 0.3f, 0.12f).copy(alpha = 0.06f), 12.dp, 2.dp),
        shadowHover = AppShadow(hslColor(pageHue, 0.3f, 0.12f).copy(alpha = 0.11f), 24.dp, 6.dp),
        glow = AppShadow(primary.copy(alpha = 0.30f), 14.dp, 4.dp),
        ring = primary.copy(alpha = 0.18f),
        scrim = hslColor(pageHue, 0.3f, 0.12f).copy(alpha = 0.45f),
        gradientStart = gradientStart.darken(0.08f),
        gradientEnd = gradientEnd.darken(0.08f)
    )
}

/** 供取色 UI 使用：HSL → Color。 */
fun colorFromHsl(hue: Float, saturation: Float, lightness: Float): Color =
    hslColor(hue, saturation, lightness)

/** 供取色 UI 使用：Color → HSL（色相 0~360，饱和度与明度 0~1）。 */
fun colorToHsl(color: Color): Triple<Float, Float, Float> =
    color.toHsl().let { Triple(it[0], it[1], it[2]) }

private fun Color.hue(): Float = toHsl()[0]

private fun Color.saturation(): Float = toHsl()[1]

private fun Color.lightness(): Float = toHsl()[2]

private fun Color.withLightness(lightness: Float): Color =
    hslColor(hue(), saturation(), lightness, alpha)

private fun Color.brighten(delta: Float): Color =
    withLightness((lightness() + delta).coerceAtMost(0.94f))

private fun Color.darken(delta: Float): Color =
    withLightness((lightness() - delta).coerceAtLeast(0.08f))

/** RGB → HSL（纯计算，不依赖 Android 框架，便于单测校验对比度）。 */
private fun Color.toHsl(): FloatArray {
    val r = red
    val g = green
    val b = blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val lightness = (max + min) / 2f
    val delta = max - min
    if (delta < 1e-6f) return floatArrayOf(0f, 0f, lightness)
    val saturation =
        if (lightness > 0.5f) delta / (2f - max - min) else delta / (max + min)
    val hue = when (max) {
        r -> ((g - b) / delta + if (g < b) 6f else 0f)
        g -> ((b - r) / delta + 2f)
        else -> ((r - g) / delta + 4f)
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

/** HSL → RGB。 */
private fun hslColor(hue: Float, saturation: Float, lightness: Float, alpha: Float = 1f): Color {
    val s = saturation.coerceIn(0f, 1f)
    val l = lightness.coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * l - 1f)) * s
    val hp = (((hue % 360f) + 360f) % 360f) / 60f
    val x = chroma * (1f - abs(hp % 2f - 1f))
    val (r1, g1, b1) = when {
        hp < 1f -> Triple(chroma, x, 0f)
        hp < 2f -> Triple(x, chroma, 0f)
        hp < 3f -> Triple(0f, chroma, x)
        hp < 4f -> Triple(0f, x, chroma)
        hp < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val m = l - chroma / 2f
    return Color(
        red = (r1 + m).coerceIn(0f, 1f),
        green = (g1 + m).coerceIn(0f, 1f),
        blue = (b1 + m).coerceIn(0f, 1f),
        alpha = alpha.coerceIn(0f, 1f)
    )
}

/** 该底色上取对比度更高的文字色（近黑或白），保证按钮文字在任意主色上都能看清。 */
private fun readableOn(background: Color): Color {
    val dark = hslColor(background.hue(), 0.35f, 0.10f)
    return if (contrastRatio(Color.White, background) >= contrastRatio(dark, background)) {
        Color.White
    } else {
        dark
    }
}

private fun contrastRatio(a: Color, b: Color): Float {
    val first = a.luminance() + 0.05f
    val second = b.luminance() + 0.05f
    return if (first > second) first / second else second / first
}

/**
 * 自定义皮肤：用户只给一个主色（可再给一个辅色），其余长相由这里生成。
 *
 * 走轻量路线：容器色靠提亮/压暗，文字色按亮度阈值取近黑近白，中性色只带极微弱的主色相
 * （整页仍然是「背景→卡片→描边」三层，不会因为主色鲜艳而变得花）。
 *
 * 语义色（绿/琥珀/红）固定不动：它们是含义不是装饰，用户把主色选成红也不能让「危险」失效。
 * 返回的是该皮肤自带的浅色版，深色版由 [AppSkinTokens.oppositeVariant] 派生。
 */
fun customSkinTokens(primary: Color, accent: Color? = null): AppSkinTokens {
    val hue = primary.hue()
    val saturationValue = primary.saturation()
    // 过亮的主色在浅底上会让白字糊掉，压到可读区间再当主色。
    val readablePrimary =
        if (primary.lightness() > 0.72f) primary.withLightness(0.62f) else primary
    val accentColor = accent
        ?: hslColor((hue + 42f) % 360f, (saturationValue * 0.85f).coerceAtMost(0.85f), 0.55f)
    val neutralChroma = { factor: Float, limit: Float -> min(saturationValue * factor, limit) }
    return AppSkinTokens(
        primary = readablePrimary,
        primaryPressed = readablePrimary.withLightness(
            (readablePrimary.lightness() - 0.10f).coerceAtLeast(0.22f)
        ),
        primarySoft = hslColor(hue, neutralChroma(0.55f, 0.50f), 0.945f),
        primarySoftPressed = hslColor(hue, neutralChroma(0.70f, 0.60f), 0.885f),
        onPrimary = readableOn(readablePrimary),
        pageBackground = hslColor(hue, neutralChroma(0.18f, 0.12f), 0.965f),
        card = hslColor(hue, neutralChroma(0.12f, 0.08f), 0.995f),
        cardMuted = hslColor(hue, neutralChroma(0.16f, 0.10f), 0.970f),
        text = hslColor(hue, neutralChroma(0.35f, 0.18f), 0.15f),
        textSecondary = hslColor(hue, neutralChroma(0.30f, 0.15f), 0.46f),
        textDisabled = hslColor(hue, neutralChroma(0.22f, 0.12f), 0.68f),
        border = hslColor(hue, neutralChroma(0.35f, 0.18f), 0.885f),
        divider = hslColor(hue, neutralChroma(0.28f, 0.14f), 0.935f),
        success = Color(0xFF16A34A),
        successSoft = Color(0xFFE9F9EF),
        warning = Color(0xFFD97706),
        warningSoft = Color(0xFFFDF3E3),
        danger = Color(0xFFEF4444),
        dangerSoft = Color(0xFFFDECEC),
        shadow = AppShadow(hslColor(hue, 0.30f, 0.15f).copy(alpha = 0.06f), 12.dp, 2.dp),
        shadowHover = AppShadow(hslColor(hue, 0.30f, 0.15f).copy(alpha = 0.11f), 24.dp, 6.dp),
        glow = AppShadow(readablePrimary.copy(alpha = 0.32f), 14.dp, 4.dp),
        ring = readablePrimary.copy(alpha = 0.18f),
        scrim = hslColor(hue, 0.30f, 0.12f).copy(alpha = 0.45f),
        gradientStart = readablePrimary,
        gradientEnd = accentColor,
        radiusScale = 1.25f
    )
}
