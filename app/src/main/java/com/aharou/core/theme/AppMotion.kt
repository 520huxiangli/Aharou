package com.aharou.core.theme

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** 缓动档位，取值与设计规范的展示保持一致。 */
object AppEase {
    val Out: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val InOut: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val Spring: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)
}

/**
 * 动效 Token：时长与缓动是全局档位，不随皮肤变。
 *
 * 组件里禁止裸写毫秒数与贝塞尔曲线，一律从这里取，
 * 这样无障碍降级只需要换一个实例。
 */
@Immutable
data class AppMotion(
    val durationQuick: Int = 100,
    val durationStandard: Int = 200,
    val durationEntrance: Int = 300,
    val durationNarrative: Int = 500,
    val easeOut: Easing = AppEase.Out,
    val easeInOut: Easing = AppEase.InOut,
    val easeSpring: Easing = AppEase.Spring
) {
    /** 关闭动画时的等价实例：时长归零，曲线保留。 */
    val instant: AppMotion
        get() = copy(
            durationQuick = 0,
            durationStandard = 0,
            durationEntrance = 0,
            durationNarrative = 0
        )
}

val LocalAppMotion = staticCompositionLocalOf { AppMotion() }

/**
 * 系统把「动画时长缩放」设为 0（开发者选项或无障碍设置）时全部瞬时完成，
 * 对应设计规范里 prefers-reduced-motion 的要求。
 */
@Composable
fun rememberAppMotion(): AppMotion {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE
            )
        }.getOrDefault(1f) > 0f
    }
    return if (animationsEnabled) AppMotion() else AppMotion().instant
}

val MaterialTheme.appMotion: AppMotion
    @Composable
    @ReadOnlyComposable
    get() = LocalAppMotion.current
