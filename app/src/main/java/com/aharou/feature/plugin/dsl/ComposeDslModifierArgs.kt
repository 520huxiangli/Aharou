package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 3901-4394，modifier 参数与形状解析），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredSizeIn
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit.Companion.Unspecified
import androidx.compose.ui.unit.dp
import java.util.Locale

@Composable
internal fun applyOnSizeChangedModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val actionId = ComposeDslParser.extractActionId(args.firstOrNull()) ?: return modifier
    var lastSize by remember { mutableStateOf<IntSize?>(null) }
    return modifier.onSizeChanged { size ->
        if (lastSize == size) {
            return@onSizeChanged
        }
        lastSize = size
        dispatchComposeDslModifierAction(
            source = "onSizeChanged",
            actionId = actionId,
            payload = mapOf("width" to size.width, "height" to size.height),
            onAction = onAction,
            nodeInfo = nodeInfo
        )
    }
}

@Composable
internal fun applyOnGloballyPositionedModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val actionId = ComposeDslParser.extractActionId(args.firstOrNull()) ?: return modifier
    var lastSnapshot by remember { mutableStateOf<ComposeDslModifierPositionedSnapshot?>(null) }
    return modifier.onGloballyPositioned { coordinates ->
        val rootBounds = coordinates.boundsInRoot()
        val windowPosition = coordinates.positionInWindow()
        val snapshot =
            ComposeDslModifierPositionedSnapshot(
                rootX = rootBounds.left,
                rootY = rootBounds.top,
                width = rootBounds.width,
                height = rootBounds.height,
                windowX = windowPosition.x,
                windowY = windowPosition.y
            )
        if (lastSnapshot == snapshot) {
            return@onGloballyPositioned
        }
        lastSnapshot = snapshot
        dispatchComposeDslModifierAction(
            source = "onGloballyPositioned",
            actionId = actionId,
            payload =
                mapOf(
                    "rootX" to snapshot.rootX,
                    "rootY" to snapshot.rootY,
                    "width" to snapshot.width,
                    "height" to snapshot.height,
                    "windowX" to snapshot.windowX,
                    "windowY" to snapshot.windowY
                ),
            onAction = onAction,
            nodeInfo = nodeInfo
        )
    }
}

internal fun applyPaddingModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    if (args.isEmpty()) {
        return modifier
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val all = first["all"].floatArg()
        if (all != null) {
            return modifier.padding(all.dp)
        }
        val horizontal = first["horizontal"].floatArg() ?: 0f
        val vertical = first["vertical"].floatArg() ?: 0f
        val start = first["start"].floatArg()
        val top = first["top"].floatArg()
        val end = first["end"].floatArg()
        val bottom = first["bottom"].floatArg()
        return if (start != null || top != null || end != null || bottom != null) {
            modifier.padding(
                start = (start ?: 0f).dp,
                top = (top ?: 0f).dp,
                end = (end ?: 0f).dp,
                bottom = (bottom ?: 0f).dp
            )
        } else {
            modifier.padding(horizontal = horizontal.dp, vertical = vertical.dp)
        }
    }

    val firstNumber = first.floatArg()
    val secondNumber = args.getOrNull(1).floatArg()
    val thirdNumber = args.getOrNull(2).floatArg()
    val fourthNumber = args.getOrNull(3).floatArg()

    return when {
        firstNumber != null && secondNumber == null -> modifier.padding(firstNumber.dp)
        firstNumber != null && secondNumber != null && thirdNumber == null ->
            modifier.padding(horizontal = firstNumber.dp, vertical = secondNumber.dp)
        firstNumber != null && secondNumber != null && thirdNumber != null && fourthNumber != null ->
            modifier.padding(
                start = firstNumber.dp,
                top = secondNumber.dp,
                end = thirdNumber.dp,
                bottom = fourthNumber.dp
            )
        else -> modifier
    }
}

internal fun applyOffsetModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    if (args.isEmpty()) {
        return modifier
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val x = first["x"].floatArg() ?: 0f
        val y = first["y"].floatArg() ?: 0f
        return modifier.offset(x.dp, y.dp)
    }
    val x = first.floatArg() ?: 0f
    val y = args.getOrNull(1).floatArg() ?: 0f
    return modifier.offset(x.dp, y.dp)
}

internal fun applyWidthInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = axisBoundsFromModifierArgs(args, "min", "max", "minWidth", "maxWidth")
    return modifier.widthIn(
        min = bounds.min?.dp ?: Dp.Unspecified,
        max = bounds.max?.dp ?: Dp.Unspecified
    )
}

internal fun applyHeightInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = axisBoundsFromModifierArgs(args, "min", "max", "minHeight", "maxHeight")
    return modifier.heightIn(
        min = bounds.min?.dp ?: Dp.Unspecified,
        max = bounds.max?.dp ?: Dp.Unspecified
    )
}

internal fun applySizeInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = sizeBoundsFromModifierArgs(args)
    return modifier.sizeIn(
        minWidth = bounds.minWidth?.dp ?: Dp.Unspecified,
        minHeight = bounds.minHeight?.dp ?: Dp.Unspecified,
        maxWidth = bounds.maxWidth?.dp ?: Dp.Unspecified,
        maxHeight = bounds.maxHeight?.dp ?: Dp.Unspecified
    )
}

internal fun applyRequiredWidthInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = axisBoundsFromModifierArgs(args, "min", "max", "minWidth", "maxWidth")
    return modifier.requiredWidthIn(
        min = bounds.min?.dp ?: Dp.Unspecified,
        max = bounds.max?.dp ?: Dp.Unspecified
    )
}

internal fun applyRequiredHeightInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = axisBoundsFromModifierArgs(args, "min", "max", "minHeight", "maxHeight")
    return modifier.requiredHeightIn(
        min = bounds.min?.dp ?: Dp.Unspecified,
        max = bounds.max?.dp ?: Dp.Unspecified
    )
}

internal fun applyRequiredSizeInModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = sizeBoundsFromModifierArgs(args)
    return modifier.requiredSizeIn(
        minWidth = bounds.minWidth?.dp ?: Dp.Unspecified,
        minHeight = bounds.minHeight?.dp ?: Dp.Unspecified,
        maxWidth = bounds.maxWidth?.dp ?: Dp.Unspecified,
        maxHeight = bounds.maxHeight?.dp ?: Dp.Unspecified
    )
}

internal fun applyDefaultMinSizeModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val bounds = defaultMinSizeFromModifierArgs(args)
    return modifier.defaultMinSize(
        minWidth = bounds.minWidth?.dp ?: Dp.Unspecified,
        minHeight = bounds.minHeight?.dp ?: Dp.Unspecified
    )
}

internal fun applyWrapContentWidthModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val spec = wrapWidthSpecFromModifierArgs(args)
    return modifier.wrapContentWidth(
        align = spec.align,
        unbounded = spec.unbounded
    )
}

internal fun applyWrapContentHeightModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val spec = wrapHeightSpecFromModifierArgs(args)
    return modifier.wrapContentHeight(
        align = spec.align,
        unbounded = spec.unbounded
    )
}

internal fun applyWrapContentSizeModifierOp(modifier: Modifier, args: List<Any?>): Modifier {
    val spec = wrapSizeSpecFromModifierArgs(args)
    return modifier.wrapContentSize(
        align = spec.align,
        unbounded = spec.unbounded
    )
}

@Composable
internal fun colorFromModifierArg(value: Any?): Color? {
    return resolveColorValue(value)
}

internal fun shapeFromValue(value: Any?): androidx.compose.ui.graphics.Shape? {
    val shapeValue = value as? Map<*, *> ?: return null
    val shapeKind = shapeKindFromValue(shapeValue)
    return when (shapeKind) {
        ComposeDslShapeKind.Circle -> CircleShape
        ComposeDslShapeKind.Pill -> RoundedCornerShape(percent = 50)
        ComposeDslShapeKind.Cut -> {
            val corners = shapeCornersFromValue(shapeValue)
            if (!corners.hasAnyValue()) {
                null
            } else {
                CutCornerShape(
                    topStart = (corners.topStart ?: 0f).dp,
                    topEnd = (corners.topEnd ?: 0f).dp,
                    bottomEnd = (corners.bottomEnd ?: 0f).dp,
                    bottomStart = (corners.bottomStart ?: 0f).dp
                )
            }
        }
        ComposeDslShapeKind.Rounded -> {
            val corners = shapeCornersFromValue(shapeValue)
            if (!corners.hasAnyValue()) {
                null
            } else {
                RoundedCornerShape(
                    topStart = (corners.topStart ?: 0f).dp,
                    topEnd = (corners.topEnd ?: 0f).dp,
                    bottomEnd = (corners.bottomEnd ?: 0f).dp,
                    bottomStart = (corners.bottomStart ?: 0f).dp
                )
            }
        }
    }
}

internal fun shapeFromModifierArg(value: Any?): androidx.compose.ui.graphics.Shape? {
    return shapeFromValue(value)
}

internal fun shapeKindFromValue(value: Map<*, *>): ComposeDslShapeKind {
    return when (
        normalizeToken(
            value["type"]?.toString()
                ?: value["kind"]?.toString()
                ?: ""
        )
    ) {
        "cut", "cutcorner", "cutcorners" -> ComposeDslShapeKind.Cut
        "circle" -> ComposeDslShapeKind.Circle
        "pill", "capsule", "stadium" -> ComposeDslShapeKind.Pill
        else -> ComposeDslShapeKind.Rounded
    }
}

internal fun shapeCornersFromValue(value: Map<*, *>): ComposeDslShapeCorners {
    val uniformRadius = value["cornerRadius"].floatArg() ?: value["radius"].floatArg()
    return ComposeDslShapeCorners(
        topStart = shapeCornerValue(value, "topStart", "topLeft", uniformRadius),
        topEnd = shapeCornerValue(value, "topEnd", "topRight", uniformRadius),
        bottomEnd = shapeCornerValue(value, "bottomEnd", "bottomRight", uniformRadius),
        bottomStart = shapeCornerValue(value, "bottomStart", "bottomLeft", uniformRadius)
    )
}

internal fun shapeCornerValue(
    value: Map<*, *>,
    logicalKey: String,
    physicalKey: String,
    fallback: Float?
): Float? {
    return value[logicalKey].floatArg() ?: value[physicalKey].floatArg() ?: fallback
}

internal fun Any?.floatArg(): Float? {
    return canvasNumberFromValue(this)
}

internal fun Any?.boolArg(): Boolean? {
    return when (this) {
        is Boolean -> this
        is Number -> this.toInt() != 0
        null -> null
        else -> {
            when (this.toString().trim().lowercase(Locale.ROOT)) {
                "true", "1", "yes", "on" -> true
                "false", "0", "no", "off" -> false
                else -> null
            }
        }
    }
}

internal fun axisBoundsFromModifierArgs(
    args: List<Any?>,
    minKey: String,
    maxKey: String,
    minFallbackKey: String,
    maxFallbackKey: String
): ComposeDslModifierAxisBounds {
    if (args.isEmpty()) {
        return ComposeDslModifierAxisBounds(min = null, max = null)
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        return ComposeDslModifierAxisBounds(
            min = first[minKey].floatArg() ?: first[minFallbackKey].floatArg(),
            max = first[maxKey].floatArg() ?: first[maxFallbackKey].floatArg()
        )
    }
    return ComposeDslModifierAxisBounds(
        min = first.floatArg(),
        max = args.getOrNull(1).floatArg()
    )
}

internal fun sizeBoundsFromModifierArgs(args: List<Any?>): ComposeDslModifierSizeBounds {
    if (args.isEmpty()) {
        return ComposeDslModifierSizeBounds(
            minWidth = null,
            minHeight = null,
            maxWidth = null,
            maxHeight = null
        )
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        return ComposeDslModifierSizeBounds(
            minWidth = first["minWidth"].floatArg() ?: first["min"].floatArg(),
            minHeight = first["minHeight"].floatArg() ?: first["min"].floatArg(),
            maxWidth = first["maxWidth"].floatArg() ?: first["max"].floatArg(),
            maxHeight = first["maxHeight"].floatArg() ?: first["max"].floatArg()
        )
    }
    return ComposeDslModifierSizeBounds(
        minWidth = first.floatArg(),
        minHeight = args.getOrNull(1).floatArg(),
        maxWidth = args.getOrNull(2).floatArg(),
        maxHeight = args.getOrNull(3).floatArg()
    )
}

internal fun defaultMinSizeFromModifierArgs(args: List<Any?>): ComposeDslModifierSizeBounds {
    if (args.isEmpty()) {
        return ComposeDslModifierSizeBounds(
            minWidth = null,
            minHeight = null,
            maxWidth = null,
            maxHeight = null
        )
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val all = first["all"].floatArg()
        val minWidth = first["minWidth"].floatArg() ?: all
        val minHeight = first["minHeight"].floatArg() ?: all
        return ComposeDslModifierSizeBounds(
            minWidth = minWidth,
            minHeight = minHeight,
            maxWidth = null,
            maxHeight = null
        )
    }
    val width = first.floatArg()
    val height = args.getOrNull(1).floatArg()
    return ComposeDslModifierSizeBounds(
        minWidth = width,
        minHeight = height ?: width,
        maxWidth = null,
        maxHeight = null
    )
}

internal fun wrapWidthSpecFromModifierArgs(args: List<Any?>): ComposeDslModifierWrapWidthSpec {
    if (args.isEmpty()) {
        return ComposeDslModifierWrapWidthSpec(
            align = Alignment.CenterHorizontally,
            unbounded = false
        )
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        return ComposeDslModifierWrapWidthSpec(
            align =
                first["align"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let(::horizontalAlignmentFromToken)
                    ?: Alignment.CenterHorizontally,
            unbounded = first["unbounded"].boolArg() ?: false
        )
    }
    return ComposeDslModifierWrapWidthSpec(
        align =
            first?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::horizontalAlignmentFromToken)
                ?: Alignment.CenterHorizontally,
        unbounded = args.getOrNull(1).boolArg() ?: false
    )
}

internal fun wrapHeightSpecFromModifierArgs(args: List<Any?>): ComposeDslModifierWrapHeightSpec {
    if (args.isEmpty()) {
        return ComposeDslModifierWrapHeightSpec(
            align = Alignment.CenterVertically,
            unbounded = false
        )
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        return ComposeDslModifierWrapHeightSpec(
            align =
                first["align"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let(::verticalAlignmentFromToken)
                    ?: Alignment.CenterVertically,
            unbounded = first["unbounded"].boolArg() ?: false
        )
    }
    return ComposeDslModifierWrapHeightSpec(
        align =
            first?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::verticalAlignmentFromToken)
                ?: Alignment.CenterVertically,
        unbounded = args.getOrNull(1).boolArg() ?: false
    )
}

internal fun wrapSizeSpecFromModifierArgs(args: List<Any?>): ComposeDslModifierWrapSizeSpec {
    if (args.isEmpty()) {
        return ComposeDslModifierWrapSizeSpec(
            align = Alignment.Center,
            unbounded = false
        )
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        return ComposeDslModifierWrapSizeSpec(
            align =
                first["align"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let(::boxAlignmentFromToken)
                    ?: Alignment.Center,
            unbounded = first["unbounded"].boolArg() ?: false
        )
    }
    return ComposeDslModifierWrapSizeSpec(
        align =
            first?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                ?.let(::boxAlignmentFromToken)
                ?: Alignment.Center,
        unbounded = args.getOrNull(1).boolArg() ?: false
    )
}

@Composable
internal fun shadowSpecFromModifierArgs(args: List<Any?>): ComposeDslModifierShadowSpec? {
    if (args.isEmpty()) {
        return null
    }
    val first = args.firstOrNull()
    if (first is Map<*, *>) {
        val elevation = first["elevation"].floatArg() ?: return null
        val shape = shapeFromModifierArg(first["shape"])
        val clip = first["clip"].boolArg() ?: (elevation > 0f)
        return ComposeDslModifierShadowSpec(
            elevation = elevation,
            shape = shape,
            clip = clip
        )
    }
    val elevation = first.floatArg() ?: return null
    val shape = shapeFromModifierArg(args.getOrNull(1))
    val clip = args.getOrNull(2).boolArg() ?: (elevation > 0f)
    return ComposeDslModifierShadowSpec(
        elevation = elevation,
        shape = shape,
        clip = clip
    )
}
