package com.aharou.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 窗口宽度档位，断点沿用 Material 3 window size class：600dp / 840dp。
 *
 * - [COMPACT]  手机竖屏、分屏后的窄窗口
 * - [MEDIUM]   多数平板竖屏、手机横屏、折叠屏展开竖握
 * - [EXPANDED] 平板横屏、桌面窗口模式——从这一档起才做双栏与常驻侧栏
 */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** 断点判定。纯函数，便于单测覆盖边界值。 */
fun windowWidthClassOf(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.COMPACT
    widthDp < 840 -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

/**
 * 当前窗口宽度档位。
 *
 * 取 `Configuration.screenWidthDp` 而非物理屏幕尺寸：分屏 / 自由窗口下它给出的是**本窗口**宽度，
 * 因此平板上被拖成 1/3 窄窗时会自动退回 [WindowWidthClass.COMPACT] 的单栏布局。
 */
@Composable
fun currentWindowWidthClass(): WindowWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { windowWidthClassOf(widthDp) }
}

/** 是否为大屏（平板横屏及以上），双栏布局的开关条件。 */
@Composable
fun isExpandedWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.EXPANDED

/** 是否为紧凑宽度（手机竖屏、窄分屏等，< 600dp）。 */
@Composable
fun isCompactWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.COMPACT

object ContentWidth {
    /** 正文列最大宽度：再宽一行文字过长，视线来回扫描成本高。 */
    val readable = 800.dp

    /** 侧边栏宽度：窄窗保持原尺寸，大屏给文件树多留一点横向空间。 */
    val drawerCompact = 300.dp
    val drawerWide = 340.dp
}

/**
 * 正文列宽度上限：大屏收窄到 [ContentWidth.readable] 并居中，其余档位不限制。
 *
 * 返回 [Dp.Unspecified] 时 `Modifier.widthIn(max = …)` 等价于不加约束，调用点无需分支。
 */
@Composable
fun readableContentMaxWidth(): Dp =
    if (isExpandedWidth()) ContentWidth.readable else Dp.Unspecified

/** 侧边栏宽度：随窗口档位切换。 */
@Composable
fun drawerWidth(): Dp =
    if (currentWindowWidthClass() == WindowWidthClass.COMPACT) {
        ContentWidth.drawerCompact
    } else {
        ContentWidth.drawerWide
    }
