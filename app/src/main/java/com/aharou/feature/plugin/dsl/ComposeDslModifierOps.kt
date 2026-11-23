package com.aharou.feature.plugin.dsl

/*
 * 移植自 AAswordman/Operit（原文件 ui/common/composedsl/ToolPkgComposeDslScreen.kt（行 3301-3900，modifier 代理操作），LGPL-3.0）。
 * 本文件随 Aharou 以 GPL-3.0 分发；改动见文件内注释与仓库提交说明。
 */

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.PressGestureScope
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aharou.core.util.FileLogger

internal fun Map<String, Any?>.paddingValuesOrNull(key: String): PaddingValues? {
    return paddingSpecFromValue(this[key])?.toPaddingValues()
}

@Composable
internal fun applyProxyModifierOps(
    base: Modifier,
    rawModifier: Any?
): Modifier {
    val ops = extractModifierOps(rawModifier)
    if (ops.isEmpty()) {
        return base
    }
    var modifier = base
    ops.forEach { op ->
        modifier = applySingleModifierOp(modifier, op)
    }
    return modifier
}

internal fun extractModifierOps(rawModifier: Any?): List<ComposeDslModifierOp> {
    val container = rawModifier as? Map<*, *> ?: return emptyList()
    val list = container["__modifierOps"] as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
        val map = item as? Map<*, *> ?: return@mapNotNull null
        val name = map["name"]?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            return@mapNotNull null
        }
        val args = (map["args"] as? List<*>)?.toList() ?: emptyList()
        ComposeDslModifierOp(name = name, args = args)
    }
}

internal fun Map<String, Any?>.modifierOpByToken(token: String): ComposeDslModifierOp? {
    return extractModifierOps(this["modifier"])
        .lastOrNull { op -> normalizeToken(op.name) == token }
}

internal fun Map<String, Any?>.modifierWeightSpecOrNull(): ComposeDslModifierWeightSpec? {
    val explicitWeight = floatOrNull("weight")
    if (explicitWeight != null) {
        return ComposeDslModifierWeightSpec(
            weight = explicitWeight,
            fill = bool("weightFill", true)
        )
    }
    val op = modifierOpByToken("weight") ?: return null
    val weight = op.args.getOrNull(0).floatArg() ?: return null
    val fill =
        when {
            op.args.size > 1 -> op.args[1].boolArg() ?: true
            else -> true
        }
    return ComposeDslModifierWeightSpec(weight = weight, fill = fill)
}

internal fun Map<String, Any?>.hasModifierOp(token: String): Boolean {
    return modifierOpByToken(token) != null
}

internal fun Map<String, Any?>.scopeAlignToken(): String? {
    stringOrNull("align")?.let { return it }
    return modifierOpByToken("align")
        ?.args
        ?.firstOrNull()
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

internal fun Map<String, Any?>.commonPaddingSpecOrNull(): ComposeDslPaddingSpec? {
    paddingSpecFromValue(this["padding"])?.let { return it }

    val allPadding = floatOrNull("padding")
    if (allPadding != null) {
        return ComposeDslPaddingSpec(
            start = allPadding,
            top = allPadding,
            end = allPadding,
            bottom = allPadding
        )
    }

    val start = floatOrNull("paddingStart")
    val top = floatOrNull("paddingTop")
    val end = floatOrNull("paddingEnd")
    val bottom = floatOrNull("paddingBottom")
    if (start != null || top != null || end != null || bottom != null) {
        return ComposeDslPaddingSpec(
            start = start ?: 0f,
            top = top ?: 0f,
            end = end ?: 0f,
            bottom = bottom ?: 0f
        )
    }

    val horizontal = floatOrNull("paddingHorizontal")
    val vertical = floatOrNull("paddingVertical")
    if (horizontal != null || vertical != null) {
        return ComposeDslPaddingSpec(
            start = horizontal ?: 0f,
            top = vertical ?: 0f,
            end = horizontal ?: 0f,
            bottom = vertical ?: 0f
        )
    }

    return null
}

internal fun Map<String, Any?>.withoutCommonPaddingProps(): Map<String, Any?> {
    if (
        !containsKey("padding") &&
        !containsKey("paddingStart") &&
        !containsKey("paddingTop") &&
        !containsKey("paddingEnd") &&
        !containsKey("paddingBottom") &&
        !containsKey("paddingHorizontal") &&
        !containsKey("paddingVertical")
    ) {
        return this
    }
    return this - setOf(
        "padding",
        "paddingStart",
        "paddingTop",
        "paddingEnd",
        "paddingBottom",
        "paddingHorizontal",
        "paddingVertical"
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun applySingleModifierOp(
    modifier: Modifier,
    op: ComposeDslModifierOp
): Modifier {
    val onAction = LocalComposeDslActionHandler.current
    val nodeInfo = LocalComposeDslDebugNodeInfo.current
    val token = normalizeToken(op.name)
    return when (token) {
        "fillmaxsize" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxSize(fraction.coerceAtLeast(0f))
        }
        "fillmaxwidth" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxWidth(fraction.coerceAtLeast(0f))
        }
        "fillmaxheight" -> {
            val fraction = op.args.getOrNull(0).floatArg() ?: 1f
            modifier.fillMaxHeight(fraction.coerceAtLeast(0f))
        }
        "width" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.width(value.dp)
        }
        "height" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.height(value.dp)
        }
        "requiredwidth" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.requiredWidth(value.dp)
        }
        "requiredheight" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.requiredHeight(value.dp)
        }
        "size" -> {
            val width = op.args.getOrNull(0).floatArg() ?: return modifier
            val height = op.args.getOrNull(1).floatArg()
            if (height != null) {
                modifier.size(width.dp, height.dp)
            } else {
                modifier.size(width.dp)
            }
        }
        "requiredsize" -> {
            val width = op.args.getOrNull(0).floatArg() ?: return modifier
            val height = op.args.getOrNull(1).floatArg()
            if (height != null) {
                modifier.requiredSize(width.dp, height.dp)
            } else {
                modifier.requiredSize(width.dp)
            }
        }
        "padding" -> applyPaddingModifierOp(modifier, op.args)
        "offset" -> applyOffsetModifierOp(modifier, op.args)
        "widthin" -> applyWidthInModifierOp(modifier, op.args)
        "heightin" -> applyHeightInModifierOp(modifier, op.args)
        "sizein" -> applySizeInModifierOp(modifier, op.args)
        "requiredwidthin" -> applyRequiredWidthInModifierOp(modifier, op.args)
        "requiredheightin" -> applyRequiredHeightInModifierOp(modifier, op.args)
        "requiredsizein" -> applyRequiredSizeInModifierOp(modifier, op.args)
        "defaultminsize" -> applyDefaultMinSizeModifierOp(modifier, op.args)
        "wrapcontentwidth" -> applyWrapContentWidthModifierOp(modifier, op.args)
        "wrapcontentheight" -> applyWrapContentHeightModifierOp(modifier, op.args)
        "wrapcontentsize" -> applyWrapContentSizeModifierOp(modifier, op.args)
        "aspectratio" -> {
            val ratio = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.aspectRatio(ratio, true)
        }
        "alpha" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.alpha(value)
        }
        "rotate" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.rotate(value)
        }
        "scale" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.scale(value)
        }
        "zindex" -> {
            val value = op.args.getOrNull(0).floatArg() ?: return modifier
            modifier.zIndex(value)
        }
        "background" -> {
            val brush = parseBrush(op.args.getOrNull(0))
            val shape = shapeFromModifierArg(op.args.getOrNull(1))
            if (brush != null) {
                if (shape != null) {
                    modifier.background(brush = brush, shape = shape)
                } else {
                    modifier.background(brush = brush)
                }
            } else {
                val color = colorFromModifierArg(op.args.getOrNull(0)) ?: return modifier
                if (shape != null) {
                    modifier.background(color = color, shape = shape)
                } else {
                    modifier.background(color = color)
                }
            }
        }
        "border" -> {
            val width = op.args.getOrNull(0).floatArg() ?: 1f
            val brush = parseBrush(op.args.getOrNull(1))
            val shape = shapeFromModifierArg(op.args.getOrNull(2))
            if (brush != null) {
                if (shape != null) {
                    modifier.border(width = width.dp, brush = brush, shape = shape)
                } else {
                    modifier.border(
                        width = width.dp,
                        brush = brush,
                        shape = RoundedCornerShape(0.dp)
                    )
                }
            } else {
                val color = colorFromModifierArg(op.args.getOrNull(1)) ?: return modifier
                if (shape != null) {
                    modifier.border(width = width.dp, color = color, shape = shape)
                } else {
                    modifier.border(width = width.dp, color = color)
                }
            }
        }
        "clip" -> {
            val shape = shapeFromModifierArg(op.args.getOrNull(0)) ?: return modifier
            modifier.clip(shape)
        }
        "cliptobounds" -> modifier.clipToBounds()
        "shadow" -> {
            val spec = shadowSpecFromModifierArgs(op.args) ?: return modifier
            modifier.shadow(
                elevation = spec.elevation.dp,
                shape = spec.shape ?: RoundedCornerShape(0.dp),
                clip = spec.clip
            )
        }
        "clickable" -> {
            val actionId = ComposeDslParser.extractActionId(op.args.getOrNull(0))
            if (actionId.isNullOrBlank()) {
                modifier
            } else {
                modifier.clickable {
                    dispatchComposeDslModifierAction(
                        source = "clickable",
                        actionId = actionId,
                        payload = null,
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            }
        }
        "combinedclickable" -> applyCombinedClickableModifierOp(modifier, op.args, onAction, nodeInfo)
        "tapgestures" -> applyTapGesturesModifierOp(modifier, op.args, onAction, nodeInfo)
        "draggestures" -> applyDragGesturesModifierOp(modifier, op.args, onAction, nodeInfo)
        "transformgestures" -> applyTransformGesturesModifierOp(modifier, op.args, onAction, nodeInfo)
        "onsizechanged" -> applyOnSizeChangedModifierOp(modifier, op.args, onAction, nodeInfo)
        "ongloballypositioned" ->
            applyOnGloballyPositionedModifierOp(modifier, op.args, onAction, nodeInfo)
        "imepadding" -> modifier.imePadding()
        "statusbarspadding" -> modifier.statusBarsPadding()
        "navigationbarspadding" -> modifier.navigationBarsPadding()
        "systembarspadding" -> modifier.systemBarsPadding()
        "safedrawingpadding" -> modifier.safeDrawingPadding()
        else -> modifier
    }
}

internal fun logComposeDslModifierAction(
    source: String,
    actionId: String,
    nodeInfo: ComposeDslDebugNodeInfo?
) {
    FileLogger.d(
        TAG,
        "compose_dsl $source triggered: routeInstanceId=${nodeInfo?.routeInstanceId.orEmpty()}, nodePath=${nodeInfo?.nodePath.orEmpty()}, nodeType=${nodeInfo?.nodeType.orEmpty()}, nodeKey=${nodeInfo?.nodeKey.orEmpty()}, actionId=$actionId"
    )
}

internal fun dispatchComposeDslModifierAction(
    source: String,
    actionId: String,
    payload: Any?,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
) {
    logComposeDslModifierAction(source = source, actionId = actionId, nodeInfo = nodeInfo)
    onAction(actionId, payload)
}

private suspend fun dispatchComposeDslModifierActionAwait(
    source: String,
    actionId: String,
    payload: Any?,
    onAction: suspend (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
) {
    logComposeDslModifierAction(source = source, actionId = actionId, nodeInfo = nodeInfo)
    onAction(actionId, payload)
}

internal fun modifierOptionsArg(args: List<Any?>): Map<*, *>? {
    return args.firstOrNull() as? Map<*, *>
}

internal fun modifierOptionActionId(options: Map<*, *>?, key: String): String? {
    return ComposeDslParser.extractActionId(options?.get(key))
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun applyCombinedClickableModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val options = modifierOptionsArg(args) ?: return modifier
    val onClickActionId = modifierOptionActionId(options, "onClick") ?: return modifier
    val onLongClickActionId = modifierOptionActionId(options, "onLongClick")
    val onDoubleClickActionId = modifierOptionActionId(options, "onDoubleClick")
    return modifier.combinedClickable(
        onClick = {
            dispatchComposeDslModifierAction(
                source = "combinedClickable.onClick",
                actionId = onClickActionId,
                payload = null,
                onAction = onAction,
                nodeInfo = nodeInfo
            )
        },
        onLongClick =
            onLongClickActionId?.let { actionId ->
                {
                    dispatchComposeDslModifierAction(
                        source = "combinedClickable.onLongClick",
                        actionId = actionId,
                        payload = null,
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            },
        onDoubleClick =
            onDoubleClickActionId?.let { actionId ->
                {
                    dispatchComposeDslModifierAction(
                        source = "combinedClickable.onDoubleClick",
                        actionId = actionId,
                        payload = null,
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            }
    )
}

@Composable
internal fun applyTapGesturesModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val onActionAwait = LocalComposeDslSuspendingActionHandler.current
    val options = modifierOptionsArg(args) ?: return modifier
    val onPressActionId = modifierOptionActionId(options, "onPress")
    val onTapActionId = modifierOptionActionId(options, "onTap")
    val onDoubleTapActionId = modifierOptionActionId(options, "onDoubleTap")
    val onLongPressActionId = modifierOptionActionId(options, "onLongPress")
    if (
        onPressActionId == null &&
        onTapActionId == null &&
        onDoubleTapActionId == null &&
        onLongPressActionId == null
    ) {
        return modifier
    }
    return modifier.pointerInput(
        onPressActionId,
        onTapActionId,
        onDoubleTapActionId,
        onLongPressActionId
    ) {
        val onPressHandler: suspend PressGestureScope.(Offset) -> Unit =
            if (onPressActionId != null) {
                val pressActionId = onPressActionId
                { offset: Offset ->
                    dispatchComposeDslModifierActionAwait(
                        source = "tapGestures.onPress",
                        actionId = pressActionId,
                        payload = mapOf("x" to offset.x, "y" to offset.y),
                        onAction = onActionAwait,
                        nodeInfo = nodeInfo
                    )
                }
            } else {
                { _: Offset -> }
            }
        detectTapGestures(
            onPress = onPressHandler,
            onTap =
                onTapActionId?.let { actionId ->
                    { offset ->
                        dispatchComposeDslModifierAction(
                            source = "tapGestures.onTap",
                            actionId = actionId,
                            payload = mapOf("x" to offset.x, "y" to offset.y),
                            onAction = onAction,
                            nodeInfo = nodeInfo
                        )
                    }
                },
            onDoubleTap =
                onDoubleTapActionId?.let { actionId ->
                    { offset ->
                        dispatchComposeDslModifierAction(
                            source = "tapGestures.onDoubleTap",
                            actionId = actionId,
                            payload = mapOf("x" to offset.x, "y" to offset.y),
                            onAction = onAction,
                            nodeInfo = nodeInfo
                        )
                    }
                },
            onLongPress =
                onLongPressActionId?.let { actionId ->
                    { offset ->
                        dispatchComposeDslModifierAction(
                            source = "tapGestures.onLongPress",
                            actionId = actionId,
                            payload = mapOf("x" to offset.x, "y" to offset.y),
                            onAction = onAction,
                            nodeInfo = nodeInfo
                        )
                    }
                }
        )
    }
}

@Composable
internal fun applyDragGesturesModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val options = modifierOptionsArg(args) ?: return modifier
    val onDragStartActionId = modifierOptionActionId(options, "onDragStart")
    val onDragActionId = modifierOptionActionId(options, "onDrag")
    val onDragEndActionId = modifierOptionActionId(options, "onDragEnd")
    val onDragCancelActionId = modifierOptionActionId(options, "onDragCancel")
    if (
        onDragStartActionId == null &&
        onDragActionId == null &&
        onDragEndActionId == null &&
        onDragCancelActionId == null
    ) {
        return modifier
    }
    return modifier.pointerInput(
        onDragStartActionId,
        onDragActionId,
        onDragEndActionId,
        onDragCancelActionId
    ) {
        detectDragGestures(
            onDragStart = { offset ->
                val actionId = onDragStartActionId
                if (actionId != null) {
                    dispatchComposeDslModifierAction(
                        source = "dragGestures.onDragStart",
                        actionId = actionId,
                        payload = mapOf("x" to offset.x, "y" to offset.y),
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            },
            onDragEnd = {
                val actionId = onDragEndActionId
                if (actionId != null) {
                    dispatchComposeDslModifierAction(
                        source = "dragGestures.onDragEnd",
                        actionId = actionId,
                        payload = null,
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            },
            onDragCancel = {
                val actionId = onDragCancelActionId
                if (actionId != null) {
                    dispatchComposeDslModifierAction(
                        source = "dragGestures.onDragCancel",
                        actionId = actionId,
                        payload = null,
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            },
            onDrag = { change, dragAmount ->
                change.consume()
                val actionId = onDragActionId
                if (actionId != null) {
                    dispatchComposeDslModifierAction(
                        source = "dragGestures.onDrag",
                        actionId = actionId,
                        payload =
                            mapOf(
                                "x" to change.position.x,
                                "y" to change.position.y,
                                "deltaX" to dragAmount.x,
                                "deltaY" to dragAmount.y
                            ),
                        onAction = onAction,
                        nodeInfo = nodeInfo
                    )
                }
            }
        )
    }
}

@Composable
internal fun applyTransformGesturesModifierOp(
    modifier: Modifier,
    args: List<Any?>,
    onAction: (String, Any?) -> Unit,
    nodeInfo: ComposeDslDebugNodeInfo?
): Modifier {
    val options = modifierOptionsArg(args) ?: return modifier
    val onGestureActionId = modifierOptionActionId(options, "onGesture") ?: return modifier
    val panZoomLock = options["panZoomLock"].boolArg() ?: false
    return modifier.pointerInput(onGestureActionId, panZoomLock) {
        detectTransformGestures(panZoomLock = panZoomLock) { centroid, pan, zoom, rotation ->
            dispatchComposeDslModifierAction(
                source = "transformGestures.onGesture",
                actionId = onGestureActionId,
                payload =
                    mapOf(
                        "centroidX" to centroid.x,
                        "centroidY" to centroid.y,
                        "panX" to pan.x,
                        "panY" to pan.y,
                        "zoom" to zoom,
                        "rotation" to rotation
                    ),
                onAction = onAction,
                nodeInfo = nodeInfo
            )
        }
    }
}
