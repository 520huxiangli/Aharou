package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 3083-3300，通用 modifier 与数据类），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import java.util.Locale

@Composable
internal fun applyCommonModifier(
    base: Modifier,
    props: Map<String, Any?>
): Modifier {
    var modifier = base

    val explicitWidth = props.floatOrNull("width")
    if (explicitWidth != null) {
        modifier = modifier.width(explicitWidth.dp)
    }
    val explicitHeight = props.floatOrNull("height")
    if (explicitHeight != null) {
        modifier = modifier.height(explicitHeight.dp)
    }

    if (props.bool("fillMaxSize", false)) {
        modifier = modifier.fillMaxSize()
    } else if (props.bool("fillMaxHeight", false)) {
        modifier = modifier.fillMaxHeight()
    } else if (props.bool("fillMaxWidth", false)) {
        modifier = modifier.fillMaxWidth()
    }

    props.commonPaddingSpecOrNull()?.let { paddingSpec ->
        modifier = paddingSpec.applyTo(modifier)
    }

    val backgroundBrush = props["backgroundBrush"]
    if (backgroundBrush != null) {
        val shape = shapeFromValue(props["backgroundShape"]) ?: shapeFromValue(props["shape"])
        val brush = parseBrush(backgroundBrush)
        if (brush != null) {
            if (shape != null) {
                modifier = modifier.clip(shape).background(brush, shape = shape)
            } else {
                modifier = modifier.background(brush)
            }
        }
    } else {
        val backgroundColor =
            resolveColorValue(
                props["backgroundColor"]
                    ?: props["background"]
            )
        if (backgroundColor != null) {
            val shape = shapeFromValue(props["backgroundShape"]) ?: shapeFromValue(props["shape"])
            val alpha = props.floatOrNull("backgroundAlpha") ?: props.floatOrNull("alpha")
            val resolvedColor = if (alpha != null) backgroundColor.copy(alpha = alpha) else backgroundColor
            modifier =
                if (shape != null) {
                    modifier.background(resolvedColor, shape = shape)
                } else {
                    modifier.background(resolvedColor)
                }
        }
    }

    val zIndex = props.floatOrNull("zIndex")
    if (zIndex != null) {
        modifier = modifier.zIndex(zIndex)
    }

    modifier = applyProxyModifierOps(modifier, props["modifier"])

    return modifier
}

internal fun paddingSpecFromValue(raw: Any?): ComposeDslPaddingSpec? {
    return when (raw) {
        is Number -> {
            val all = raw.toFloat()
            ComposeDslPaddingSpec(start = all, top = all, end = all, bottom = all)
        }
        is Map<*, *> -> {
            val all = raw["all"].floatArg()
            if (all != null) {
                return ComposeDslPaddingSpec(start = all, top = all, end = all, bottom = all)
            }
            val horizontal = raw["horizontal"].floatArg()
            val vertical = raw["vertical"].floatArg()
            val start = raw["start"].floatArg()
            val top = raw["top"].floatArg()
            val end = raw["end"].floatArg()
            val bottom = raw["bottom"].floatArg()
            when {
                start != null || top != null || end != null || bottom != null ->
                    ComposeDslPaddingSpec(
                        start = start ?: 0f,
                        top = top ?: 0f,
                        end = end ?: 0f,
                        bottom = bottom ?: 0f
                    )
                horizontal != null || vertical != null ->
                    ComposeDslPaddingSpec(
                        start = horizontal ?: 0f,
                        top = vertical ?: 0f,
                        end = horizontal ?: 0f,
                        bottom = vertical ?: 0f
                    )
                else -> null
            }
        }
        else -> null
    }
}

@Composable
internal fun parseBrush(value: Any?): Brush? {
    // 插件写错渐变参数时退回纯色背景（返回 null），不抛异常让整棵树崩掉。
    val map = value as? Map<*, *> ?: return null
    val type = map["type"]?.toString()?.trim()?.lowercase(Locale.ROOT) ?: return null
    if (type != "verticalgradient") return null
    val colorsRaw = map["colors"] as? List<*> ?: return null
    if (colorsRaw.isEmpty()) return null
    val colors = colorsRaw.mapNotNull { resolveColorValue(it) }
    if (colors.isEmpty()) return null
    return Brush.verticalGradient(colors)
}

internal data class ComposeDslModifierOp(
    val name: String,
    val args: List<Any?>
)

internal data class ComposeDslModifierWeightSpec(
    val weight: Float,
    val fill: Boolean
)

internal data class ComposeDslModifierAxisBounds(
    val min: Float?,
    val max: Float?
)

internal data class ComposeDslModifierSizeBounds(
    val minWidth: Float?,
    val minHeight: Float?,
    val maxWidth: Float?,
    val maxHeight: Float?
)

internal data class ComposeDslPaddingSpec(
    val start: Float,
    val top: Float,
    val end: Float,
    val bottom: Float
) {
    fun applyTo(modifier: Modifier): Modifier {
        return modifier.padding(
            start = start.dp,
            top = top.dp,
            end = end.dp,
            bottom = bottom.dp
        )
    }

    fun toPaddingValues(): PaddingValues {
        return PaddingValues(
            start = start.dp,
            top = top.dp,
            end = end.dp,
            bottom = bottom.dp
        )
    }
}

internal data class ComposeDslModifierWrapWidthSpec(
    val align: Alignment.Horizontal,
    val unbounded: Boolean
)

internal data class ComposeDslModifierWrapHeightSpec(
    val align: Alignment.Vertical,
    val unbounded: Boolean
)

internal data class ComposeDslModifierWrapSizeSpec(
    val align: Alignment,
    val unbounded: Boolean
)

internal data class ComposeDslModifierShadowSpec(
    val elevation: Float,
    val shape: androidx.compose.ui.graphics.Shape?,
    val clip: Boolean
)

internal data class ComposeDslModifierPositionedSnapshot(
    val rootX: Float,
    val rootY: Float,
    val width: Float,
    val height: Float,
    val windowX: Float,
    val windowY: Float
)

internal enum class ComposeDslShapeKind {
    Rounded,
    Cut,
    Circle,
    Pill
}

internal data class ComposeDslShapeCorners(
    val topStart: Float?,
    val topEnd: Float?,
    val bottomEnd: Float?,
    val bottomStart: Float?
) {
    fun hasAnyValue(): Boolean {
        return topStart != null || topEnd != null || bottomEnd != null || bottomStart != null
    }
}
