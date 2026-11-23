package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 2481-3083，画布与自适应侧栏节点），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit.Companion.Unspecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun renderAdaptiveSidePanelNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    // 缺 onOpenChanged 时面板仍可显示，只是遮罩点击无法把关闭意图回传给 JS；
    // 面板渲染不能因为插件漏了回调就整页崩掉。
    val onOpenChangedActionId = ComposeDslParser.extractActionId(props["onOpenChanged"])

    val open = props["open"] == true
    val defaultWidth = (props["defaultWidth"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 360f
    val minWidth = (props["minWidth"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 280f
    val minContentWidth =
        (props["minContentWidth"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 320f
    val breakpoint = (props["breakpoint"] as? Number)?.toFloat()?.takeIf { it > 0f } ?: 600f
    val density = LocalDensity.current
    var resizedPanelWidth by remember(nodePath) { mutableStateOf<Dp?>(null) }

    BoxWithConstraints(
        modifier = applyScopedCommonModifier(Modifier, props, modifierResolver)
    ) {
        val isWideLayout = maxWidth >= breakpoint.dp
        val maximumPanelWidth =
            if (isWideLayout) {
                (maxWidth - minContentWidth.dp).coerceAtLeast(0.dp)
            } else {
                maxWidth
            }
        val minimumPanelWidth =
            if (isWideLayout) minOf(minWidth.dp, maximumPanelWidth) else maximumPanelWidth
        val panelWidth =
            (resizedPanelWidth ?: defaultWidth.dp).coerceIn(minimumPanelWidth, maximumPanelWidth)

        if (isWideLayout) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    renderAdaptiveSidePanelSlot(
                        node = node,
                        slotName = "content",
                        onAction = onAction,
                        nodePath = nodePath,
                        modifierResolver = modifierResolver,
                        useChildrenAsContent = true
                    )
                }
                if (open) {
                    Box(modifier = Modifier.width(panelWidth).fillMaxHeight()) {
                        renderAdaptiveSidePanelSlot(
                            node = node,
                            slotName = "side",
                            onAction = onAction,
                            nodePath = nodePath,
                            modifierResolver = modifierResolver
                        )
                        Box(
                            modifier =
                                Modifier
                                    .align(Alignment.CenterStart)
                                    .offset(x = (-12).dp)
                                    .width(24.dp)
                                    .fillMaxHeight()
                                    .pointerInput(minimumPanelWidth, maximumPanelWidth, panelWidth) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val widthDelta = with(density) { dragAmount.x.toDp() }
                                            resizedPanelWidth =
                                                (panelWidth - widthDelta).coerceIn(
                                                    minimumPanelWidth,
                                                    maximumPanelWidth
                                                )
                                        }
                                    },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .width(3.dp)
                                        .height(56.dp)
                                        .background(
                                            MaterialTheme.colorScheme.outlineVariant,
                                            RoundedCornerShape(2.dp)
                                        )
                            )
                        }
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                renderAdaptiveSidePanelSlot(
                    node = node,
                    slotName = "content",
                    onAction = onAction,
                    nodePath = nodePath,
                    modifierResolver = modifierResolver,
                    useChildrenAsContent = true
                )
                if (open) {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.18f))
                                .clickable { onOpenChangedActionId?.let { onAction(it, false) } }
                    )
                    Box(
                        modifier =
                            Modifier
                                .align(Alignment.CenterEnd)
                                .width(panelWidth)
                                .fillMaxHeight()
                    ) {
                        renderAdaptiveSidePanelSlot(
                            node = node,
                            slotName = "side",
                            onAction = onAction,
                            nodePath = nodePath,
                            modifierResolver = modifierResolver
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun renderAdaptiveSidePanelSlot(
    node: ComposeDslNode,
    slotName: String,
    onAction: (String, Any?) -> Unit,
    nodePath: String,
    modifierResolver: ComposeDslModifierResolver,
    useChildrenAsContent: Boolean = false
) {
    val explicitSlotNodes = node.slots[slotName].orEmpty()
    val slotNodes =
        if (explicitSlotNodes.isNotEmpty()) {
            explicitSlotNodes
        } else if (useChildrenAsContent) {
            node.children
        } else {
            emptyList()
        }
    // 只取第一个子节点，多余的忽略：抛异常会让整个面板白屏。
    val single = slotNodes.firstOrNull() ?: return
    renderComposeDslNode(
        node = single,
        onAction = onAction,
        nodePath = "$nodePath:$slotName/0",
        modifierResolver = modifierResolver
    )
}

internal data class CanvasCommand(
    val type: String,
    val values: Map<String, Any?>,
    val unit: String,
    val color: Color,
    val brush: Brush?,
    val alpha: Float?,
    val strokeWidth: Float
)

internal fun canvasNumberFromValue(value: Any?): Float? {
    return when (value) {
        is Number -> value.toFloat()
        is Map<*, *> -> {
            val raw = value["value"]
            when (raw) {
                is Number -> raw.toFloat()
                else -> raw?.toString()?.toFloatOrNull()
            }
        }
        else -> value?.toString()?.toFloatOrNull()
    }
}

internal fun canvasUnitFromValue(value: Any?): String? {
    val map = value as? Map<*, *> ?: return null
    val token =
        map["unit"]?.toString()
            ?: map["__unit"]?.toString()
    return token?.trim()?.lowercase(Locale.ROOT).orEmpty().ifBlank { null }
}

@Composable
internal fun parseCanvasCommands(raw: Any?): List<CanvasCommand> {
    val list = raw as? List<*> ?: return emptyList()
    @Composable
    fun parseCanvasBrush(value: Any?): Brush? {
        val map = value as? Map<*, *> ?: return null
        // 插件给错渐变参数时降级成纯色填充（返回 null），不抛异常拖垮整块画布。
        val type = map["type"]?.toString()?.trim()?.lowercase(Locale.ROOT) ?: return null
        if (type != "verticalgradient") return null
        val colorsRaw = map["colors"] as? List<*> ?: return null
        if (colorsRaw.isEmpty()) return null
        val colors = colorsRaw.mapNotNull { resolveColorValue(it) }
        if (colors.isEmpty()) return null
        return Brush.verticalGradient(colors)
    }
    return list.mapNotNull { entry ->
        val map = entry as? Map<*, *> ?: return@mapNotNull null
        val type = map["type"]?.toString()?.trim().orEmpty()
        if (type.isBlank()) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val values = map.entries.associate { (k, v) -> k.toString() to v } as Map<String, Any?>
        val unit =
            canvasUnitFromValue(values["unit"])
                ?: values["unit"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                ?: "fraction"
        val alpha = canvasNumberFromValue(values["alpha"])
        val strokeWidth = canvasNumberFromValue(values["strokeWidth"]) ?: 1f
        val resolvedColor = resolveColorValue(values["color"])
        val color = resolvedColor ?: Color.Unspecified
        val brush = parseCanvasBrush(values["brush"])
        CanvasCommand(
            type = type.lowercase(Locale.ROOT),
            values = values,
            unit = unit,
            color = color,
            brush = brush,
            alpha = alpha,
            strokeWidth = strokeWidth
        )
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
internal fun renderCanvasNode(
    node: ComposeDslNode,
    onAction: (String, Any?) -> Unit,
    modifierResolver: ComposeDslModifierResolver
) {
    val props = node.props
    val commands = parseCanvasCommands(props["commands"])
    val textMeasurer = rememberTextMeasurer()
    val onTransformActionId = ComposeDslParser.extractActionId(props["onTransform"])
    val onSizeChangedActionId = ComposeDslParser.extractActionId(props["onSizeChanged"])
    var lastSize by remember { mutableStateOf(IntSize.Zero) }

    val transform = props["transform"] as? Map<*, *>
    val transformScale = (transform?.get("scale") as? Number)?.toFloat()
    val transformOffsetX = (transform?.get("offsetX") as? Number)?.toFloat()
    val transformOffsetY = (transform?.get("offsetY") as? Number)?.toFloat()
    val transformPivotX = (transform?.get("pivotX") as? Number)?.toFloat()
    val transformPivotY = (transform?.get("pivotY") as? Number)?.toFloat()
    var localScale by remember(transformScale, transformOffsetX, transformOffsetY) {
        mutableStateOf(transformScale ?: 1f)
    }
    var localOffset by remember(transformScale, transformOffsetX, transformOffsetY) {
        mutableStateOf(
            androidx.compose.ui.geometry.Offset(
                transformOffsetX ?: 0f,
                transformOffsetY ?: 0f
            )
        )
    }

    var modifier =
        applyScopedCommonModifier(Modifier, props, modifierResolver)
            .onSizeChanged { size ->
                if (onSizeChangedActionId != null && size != lastSize) {
                    lastSize = size
                    onAction(
                        onSizeChangedActionId,
                        mapOf(
                            "width" to size.width,
                            "height" to size.height
                        )
                    )
                }
            }

    if (onTransformActionId != null) {
        modifier =
            modifier.pointerInput(onTransformActionId) {
                detectTransformGestures { centroid, pan, zoom, rotation ->
                    localScale = (localScale * zoom).coerceIn(0.6f, 2f)
                    localOffset = localOffset + pan
                    onAction(
                        onTransformActionId,
                        mapOf(
                            "__no_render" to true,
                            "centroidX" to centroid.x,
                            "centroidY" to centroid.y,
                            "panX" to pan.x,
                            "panY" to pan.y,
                            "zoom" to zoom,
                            "rotation" to rotation
                        )
                    )
                }
            }
    }

    Canvas(modifier = modifier) {
        val widthPx = size.width
        val heightPx = size.height

        fun resolve(value: Any?, defaultUnit: String, axis: String): Float {
            val unit = canvasUnitFromValue(value) ?: defaultUnit
            val numeric = canvasNumberFromValue(value) ?: 0f
            return when (unit) {
                "fraction" -> if (axis == "x") numeric * widthPx else numeric * heightPx
                "dp" -> numeric.dp.toPx()
                else -> numeric
            }
        }

        fun drawCommands() {
            commands.forEach { command ->
            val values = command.values
            val unit = command.unit
            val strokeWidth = command.strokeWidth
            val color = if (command.alpha != null) command.color.copy(alpha = command.alpha) else command.color
            val brush = command.brush
            val brushAlpha = command.alpha ?: 1f

            fun resolveStyle(): androidx.compose.ui.graphics.drawscope.DrawStyle {
                val token = values["style"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                return if (token == "stroke") Stroke(width = strokeWidth) else Fill
            }

            fun buildPath(raw: Any?): Path? {
                val list = raw as? List<*> ?: return null
                val path = Path()
                list.forEach { opRaw ->
                    val op = opRaw as? Map<*, *> ?: return@forEach
                    val opType = op["type"]?.toString()?.trim()?.lowercase(Locale.ROOT).orEmpty()
                    when (opType) {
                        "moveto" -> {
                            val x = resolve(op["x"], unit, "x")
                            val y = resolve(op["y"], unit, "y")
                            path.moveTo(x, y)
                        }
                        "lineto" -> {
                            val x = resolve(op["x"], unit, "x")
                            val y = resolve(op["y"], unit, "y")
                            path.lineTo(x, y)
                        }
                        "cubicto" -> {
                            val x1 = resolve(op["x1"], unit, "x")
                            val y1 = resolve(op["y1"], unit, "y")
                            val x2 = resolve(op["x2"], unit, "x")
                            val y2 = resolve(op["y2"], unit, "y")
                            val x3 = resolve(op["x3"], unit, "x")
                            val y3 = resolve(op["y3"], unit, "y")
                            path.cubicTo(x1, y1, x2, y2, x3, y3)
                        }
                        "quadto" -> {
                            val x1 = resolve(op["x1"], unit, "x")
                            val y1 = resolve(op["y1"], unit, "y")
                            val x2 = resolve(op["x2"], unit, "x")
                            val y2 = resolve(op["y2"], unit, "y")
                            path.quadraticTo(x1, y1, x2, y2)
                        }
                        "close" -> path.close()
                    }
                }
                return path
            }

            when (command.type) {
                "line" -> {
                    val x1 = resolve(values["x1"], unit, "x")
                    val y1 = resolve(values["y1"], unit, "y")
                    val x2 = resolve(values["x2"], unit, "x")
                    val y2 = resolve(values["y2"], unit, "y")
                    drawLine(color = color, start = androidx.compose.ui.geometry.Offset(x1, y1), end = androidx.compose.ui.geometry.Offset(x2, y2), strokeWidth = strokeWidth)
                }
                "rect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val filled = (values["filled"] as? Boolean) ?: true
                    val style = if (filled) Fill else Stroke(width = strokeWidth)
                    if (brush != null) {
                        drawRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            style = style
                        )
                    }
                }
                "roundrect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val radius = canvasNumberFromValue(values["radius"]) ?: 0f
                    val filled = (values["filled"] as? Boolean) ?: true
                    val style = if (filled) Fill else Stroke(width = strokeWidth)
                    if (brush != null) {
                        drawRoundRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRoundRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            style = style
                        )
                    }
                }
                "drawroundrect" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val w = resolve(values["width"], unit, "x")
                    val h = resolve(values["height"], unit, "y")
                    val radius = canvasNumberFromValue(values["cornerRadius"])
                        ?: canvasNumberFromValue(values["radius"])
                        ?: 0f
                    val style = resolveStyle()
                    if (brush != null) {
                        drawRoundRect(
                            brush = brush,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            alpha = brushAlpha,
                            style = style
                        )
                    } else {
                        drawRoundRect(
                            color = color,
                            topLeft = androidx.compose.ui.geometry.Offset(x, y),
                            size = androidx.compose.ui.geometry.Size(w, h),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
                            style = style
                        )
                    }
                }
                "circle" -> {
                    val cx = resolve(values["cx"], unit, "x")
                    val cy = resolve(values["cy"], unit, "y")
                    val r = resolve(values["radius"], unit, "x")
                    val filled = (values["filled"] as? Boolean) ?: true
                    drawCircle(
                        color = color,
                        radius = r,
                        center = androidx.compose.ui.geometry.Offset(cx, cy),
                        style = if (filled) Fill else Stroke(width = strokeWidth)
                    )
                }
                "text" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val text = values["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val fontSize = canvasNumberFromValue(values["fontSize"]) ?: 10f
                        val minWidthRaw = values["minWidth"]
                        val maxWidthRaw = values["maxWidth"]
                        val minWidth = canvasNumberFromValue(minWidthRaw)
                        val maxWidth = canvasNumberFromValue(maxWidthRaw)
                        val minHeightRaw = values["minHeight"]
                        val maxHeightRaw = values["maxHeight"]
                        val minHeight = canvasNumberFromValue(minHeightRaw)
                        val maxHeight = canvasNumberFromValue(maxHeightRaw)
                        val maxLines = (values["maxLines"] as? Number)?.toInt() ?: Int.MAX_VALUE
                        val overflowToken = values["overflow"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                        val overflow =
                            if (overflowToken == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = TextStyle(color = color, fontSize = fontSize.sp),
                            maxLines = maxLines,
                            overflow = overflow,
                            constraints = if (minWidth != null || maxWidth != null || minHeight != null || maxHeight != null) {
                                androidx.compose.ui.unit.Constraints(
                                    minWidth = minWidth?.let { resolve(minWidthRaw, unit, "x").toInt() } ?: 0,
                                    maxWidth = maxWidth?.let { resolve(maxWidthRaw, unit, "x").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity,
                                    minHeight = minHeight?.let { resolve(minHeightRaw, unit, "y").toInt() } ?: 0,
                                    maxHeight = maxHeight?.let { resolve(maxHeightRaw, unit, "y").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity
                                )
                            } else {
                                androidx.compose.ui.unit.Constraints()
                            }
                        )
                        drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
                "drawtext" -> {
                    val x = resolve(values["x"], unit, "x")
                    val y = resolve(values["y"], unit, "y")
                    val text = values["text"]?.toString().orEmpty()
                    if (text.isNotBlank()) {
                        val fontSize = canvasNumberFromValue(values["fontSize"]) ?: 10f
                        val minWidthRaw = values["minWidth"]
                        val maxWidthRaw = values["maxWidth"]
                        val minWidth = canvasNumberFromValue(minWidthRaw)
                        val maxWidth = canvasNumberFromValue(maxWidthRaw)
                        val minHeightRaw = values["minHeight"]
                        val maxHeightRaw = values["maxHeight"]
                        val minHeight = canvasNumberFromValue(minHeightRaw)
                        val maxHeight = canvasNumberFromValue(maxHeightRaw)
                        val maxLines = (values["maxLines"] as? Number)?.toInt() ?: Int.MAX_VALUE
                        val overflowToken = values["overflow"]?.toString()?.trim()?.lowercase(Locale.ROOT)
                        val overflow =
                            if (overflowToken == "ellipsis") TextOverflow.Ellipsis else TextOverflow.Clip
                        val layout = textMeasurer.measure(
                            text = AnnotatedString(text),
                            style = TextStyle(color = color, fontSize = fontSize.sp),
                            maxLines = maxLines,
                            overflow = overflow,
                            constraints = if (minWidth != null || maxWidth != null || minHeight != null || maxHeight != null) {
                                androidx.compose.ui.unit.Constraints(
                                    minWidth = minWidth?.let { resolve(minWidthRaw, unit, "x").toInt() } ?: 0,
                                    maxWidth = maxWidth?.let { resolve(maxWidthRaw, unit, "x").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity,
                                    minHeight = minHeight?.let { resolve(minHeightRaw, unit, "y").toInt() } ?: 0,
                                    maxHeight = maxHeight?.let { resolve(maxHeightRaw, unit, "y").toInt() }
                                        ?: androidx.compose.ui.unit.Constraints.Infinity
                                )
                            } else {
                                androidx.compose.ui.unit.Constraints()
                            }
                        )
                        drawText(layout, topLeft = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
                "drawpath" -> {
                    val path = buildPath(values["path"]) ?: return@forEach
                    drawPath(path = path, color = color, style = resolveStyle())
                }
            }
            }
        }

        val activeScale = if (onTransformActionId != null) localScale else transformScale
        val activeOffset = if (onTransformActionId != null) localOffset else null
        if (activeScale != null || activeOffset != null) {
            val pivot =
                androidx.compose.ui.geometry.Offset(
                    transformPivotX ?: (widthPx / 2f),
                    transformPivotY ?: (heightPx / 2f)
                )
            withTransform(
                {
                    val offsetToUse =
                        activeOffset ?: androidx.compose.ui.geometry.Offset(0f, 0f)
                    translate(offsetToUse.x, offsetToUse.y)
                    if (activeScale != null) {
                        scale(activeScale, activeScale, pivot)
                    }
                }
            ) {
                drawCommands()
            }
        } else {
            drawCommands()
        }
    }
}
