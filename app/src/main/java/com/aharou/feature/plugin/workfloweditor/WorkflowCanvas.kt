/*
 * 移植自 Operit（https://github.com/AAswordman/Operit，LGPL-3.0）
 * 原路径：app/src/main/java/com/ai/assistance/operit/ui/features/workflow/components/GridWorkflowCanvas.kt
 *        + components/DraggableNodeCard.kt
 * 改动：
 * - 画布底色、网格点、节点配色改由 core/theme 的语义色与 Radius token 决定，不再写死 #RRGGBB；
 * - 连线从「按住节点拖动」改成「拖节点右缘的连线点」，整卡拖动只移动位置；同时保留「把节点拖到
 *   另一个节点身上即连接」的快捷方式；
 * - 去掉执行状态动画层，只按上次运行结果给节点描边；
 * - 上游用固定 4000x3000dp 世界，这里按内容自适应尺寸，避免超大世界在低端机上白画网格。
 */
package com.aharou.feature.plugin.workfloweditor

import android.graphics.Paint
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import com.aharou.feature.plugin.workflow.ConditionNode
import com.aharou.feature.plugin.workflow.ExecuteNode
import com.aharou.feature.plugin.workflow.ExtractNode
import com.aharou.feature.plugin.workflow.LogicNode
import com.aharou.feature.plugin.workflow.NodeExecutionState
import com.aharou.feature.plugin.workflow.TriggerNode
import com.aharou.feature.plugin.workflow.WorkflowNode
import com.aharou.feature.plugin.workflow.WorkflowNodeConnection
import compose.icons.FeatherIcons
import compose.icons.feathericons.Maximize
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val NodeWidth = 138.dp
private val NodeHeight = 84.dp
private val GridCell = 24.dp
private const val MIN_SCALE = 0.3f
private const val MAX_SCALE = 2.5f

/** 世界尺寸下限：节点可能被拖到很远处，画布跟着内容长，避免连线被裁掉。 */
private const val WORLD_PADDING = 800f

/**
 * 工作流画布。
 *
 * 节点是 Compose 卡片（要承载文本、点击与无障碍信息），网格、连线、箭头、条件标签用 Canvas 自绘；
 * 上游同样是这个分工，纯 Canvas 画文字会丢掉语义与点击命中。
 *
 * 交互：
 * - 单指拖动空白处平移，双指缩放，双击复位；
 * - 拖节点改位置（松手对齐 24dp 网格），拖到另一个节点身上即连一条线；
 * - 拖节点右缘的圆点拉一条新连线；
 * - 点节点选中，点连线选中，点空白取消选中。
 */
@Composable
fun WorkflowCanvas(
    nodes: List<WorkflowNode>,
    connections: List<WorkflowNodeConnection>,
    selectedNodeId: String?,
    selectedConnectionId: String?,
    nodeStates: Map<String, NodeExecutionState>,
    onSelectNode: (String?) -> Unit,
    onSelectConnection: (String?) -> Unit,
    onMoveNode: (nodeId: String, x: Float, y: Float) -> Unit,
    onConnect: (sourceNodeId: String, targetNodeId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val nodeWidthPx = with(density) { NodeWidth.toPx() }
    val nodeHeightPx = with(density) { NodeHeight.toPx() }
    val cellPx = with(density) { GridCell.toPx() }
    val hitRadiusPx = with(density) { 22.dp.toPx() }
    val handleOffsetPx = with(density) { 12.dp.toPx() }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var autoFitted by remember { mutableStateOf(false) }
    val dragPositions = remember { mutableStateMapOf<String, Offset>() }
    var connectFrom by remember { mutableStateOf<String?>(null) }
    var connectPoint by remember { mutableStateOf<Offset?>(null) }
    var hoverTargetId by remember { mutableStateOf<String?>(null) }

    // 手势回调在 pointerInput(Unit) 里长驻，读不到重组的普通局部变量，必须走 rememberUpdatedState。
    val nodesNow by rememberUpdatedState(nodes)
    val connectionsNow by rememberUpdatedState(connections)
    val scaleNow by rememberUpdatedState(scale)
    val panNow by rememberUpdatedState(pan)

    fun positionOf(node: WorkflowNode): Offset =
        dragPositions[node.id] ?: Offset(node.position.x, node.position.y)

    fun centerOf(node: WorkflowNode): Offset =
        positionOf(node) + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)

    fun hitNodeAt(worldPoint: Offset): WorkflowNode? = nodesNow.firstOrNull { node ->
        val topLeft = positionOf(node)
        worldPoint.x in topLeft.x..(topLeft.x + nodeWidthPx) &&
            worldPoint.y in topLeft.y..(topLeft.y + nodeHeightPx)
    }

    fun fitToContent() {
        val list = nodesNow
        if (list.isEmpty() || viewport == IntSize.Zero) {
            scale = 1f
            pan = Offset.Zero
            return
        }
        val minX = list.minOf { it.position.x }
        val minY = list.minOf { it.position.y }
        val maxX = list.maxOf { it.position.x + nodeWidthPx }
        val maxY = list.maxOf { it.position.y + nodeHeightPx }
        val contentWidth = (maxX - minX).coerceAtLeast(nodeWidthPx)
        val contentHeight = (maxY - minY).coerceAtLeast(nodeHeightPx)
        val padding = with(density) { 48.dp.toPx() }
        val availableWidth = (viewport.width - padding * 2).coerceAtLeast(1f)
        val availableHeight = (viewport.height - padding * 2).coerceAtLeast(1f)
        val target = min(availableWidth / contentWidth, availableHeight / contentHeight)
            .coerceIn(MIN_SCALE, MAX_SCALE)
        scale = target
        pan = Offset(
            (viewport.width - contentWidth * target) / 2f - minX * target,
            (viewport.height - contentHeight * target) / 2f - minY * target,
        )
    }

    LaunchedEffect(viewport, nodes.map { it.id }) {
        if (!autoFitted && viewport != IntSize.Zero && nodes.isNotEmpty()) {
            fitToContent()
            autoFitted = true
        }
    }

    val worldWidth = ((nodes.maxOfOrNull { it.position.x } ?: 0f) + WORLD_PADDING)
        .coerceAtLeast(viewport.width.toFloat())
    val worldHeight = ((nodes.maxOfOrNull { it.position.y } ?: 0f) + WORLD_PADDING)
        .coerceAtLeast(viewport.height.toFloat())

    val accentByType = mapOf(
        "trigger" to MaterialTheme.semanticColors.success,
        "execute" to MaterialTheme.colorScheme.primary,
        "condition" to MaterialTheme.semanticColors.warning,
        "logic" to MaterialTheme.colorScheme.tertiary,
        "extract" to MaterialTheme.semanticColors.info,
    )
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val edgeColor = MaterialTheme.colorScheme.outline
    val edgeActiveColor = MaterialTheme.colorScheme.primary
    val edgeFailedColor = MaterialTheme.colorScheme.error
    val labelTextColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelBgColor = MaterialTheme.semanticColors.cardSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.semanticColors.pageBackground)
            .onGloballyPositioned { viewport = it.size }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val newScale = (scaleNow * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
                    val factor = newScale / scaleNow
                    pan = (panNow - centroid) * factor + centroid + panChange
                    scale = newScale
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { fitToContent() },
                    onTap = { screenPoint ->
                        val world = (screenPoint - panNow) / scaleNow
                        // 点在节点上时由节点自己处理；这里再拦一道，避免父层把刚选中的节点又取消掉。
                        if (hitNodeAt(world) == null) {
                            val edge = hitTestEdges(
                                world,
                                nodesNow,
                                connectionsNow,
                                ::positionOf,
                                nodeWidthPx,
                                nodeHeightPx,
                                hitRadiusPx / scaleNow,
                            )
                            if (edge != null) onSelectConnection(edge) else onSelectNode(null)
                        }
                    },
                )
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val scaledCell = cellPx * scaleNow
            if (scaledCell <= 0f) return@Canvas
            val dots = mutableListOf<Offset>()
            var x = ((pan.x % scaledCell) + scaledCell) % scaledCell
            while (x <= size.width) {
                var y = ((pan.y % scaledCell) + scaledCell) % scaledCell
                while (y <= size.height) {
                    dots.add(Offset(x, y))
                    y += scaledCell
                }
                x += scaledCell
            }
            drawPoints(
                points = dots,
                pointMode = PointMode.Points,
                color = gridColor.copy(alpha = 0.55f),
                strokeWidth = 3f,
                cap = StrokeCap.Round,
            )
        }

        Box(
            modifier = Modifier
                .width(with(density) { worldWidth.toDp() })
                .height(with(density) { worldHeight.toDp() })
                .graphicsLayer {
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val labelPaint = Paint().apply {
                    isAntiAlias = true
                    textSize = with(density) { 10.sp.toPx() }
                    color = labelTextColor.toArgb()
                }

                connections.forEach { connection ->
                    val source = nodes.find { it.id == connection.sourceNodeId } ?: return@forEach
                    val target = nodes.find { it.id == connection.targetNodeId } ?: return@forEach
                    val geometry = edgeGeometry(centerOf(source), centerOf(target), nodeWidthPx, nodeHeightPx) ?: return@forEach
                    val selected = connection.id == selectedConnectionId
                    val fails = nodeStates[connection.targetNodeId] is NodeExecutionState.Failed
                    val color = when {
                        selected -> edgeActiveColor
                        fails -> edgeFailedColor
                        else -> edgeColor
                    }
                    val path = Path().apply {
                        moveTo(geometry.start.x, geometry.start.y)
                        cubicTo(
                            geometry.control1.x, geometry.control1.y,
                            geometry.control2.x, geometry.control2.y,
                            geometry.end.x, geometry.end.y,
                        )
                    }
                    drawPath(
                        path = path,
                        color = color,
                        style = Stroke(
                            width = if (selected) 2.5f else 1.8f,
                            cap = StrokeCap.Round,
                            pathEffect = if (connection.condition.isNullOrBlank()) null
                            else PathEffect.dashPathEffect(floatArrayOf(10f, 7f), 0f),
                        ),
                    )
                    drawArrowAt(geometry.pointAt(1f), geometry.angleAt(1f), color, 12f)

                    val label = connectionLabel(connection)
                    if (label.isNotEmpty()) {
                        val mid = geometry.pointAt(0.5f)
                        val textWidth = labelPaint.measureText(label)
                        val rectWidth = textWidth + with(density) { 12.dp.toPx() }
                        val rectHeight = labelPaint.textSize + with(density) { 8.dp.toPx() }
                        val topLeft = Offset(mid.x - rectWidth / 2f, mid.y - rectHeight / 2f)
                        drawRoundRect(
                            color = labelBgColor,
                            topLeft = topLeft,
                            size = Size(rectWidth, rectHeight),
                            cornerRadius = CornerRadius(rectHeight / 2f, rectHeight / 2f),
                        )
                        drawRoundRect(
                            color = color,
                            topLeft = topLeft,
                            size = Size(rectWidth, rectHeight),
                            cornerRadius = CornerRadius(rectHeight / 2f, rectHeight / 2f),
                            style = Stroke(width = 1.2f),
                        )
                        drawIntoCanvas { canvas ->
                            canvas.nativeCanvas.drawText(
                                label,
                                topLeft.x + (rectWidth - textWidth) / 2f,
                                topLeft.y + rectHeight / 2f + labelPaint.textSize * 0.35f,
                                labelPaint,
                            )
                        }
                    }
                }

                // 正在拉的连线：从源节点中心拉一条虚线到手指位置。
                val from = connectFrom
                val point = connectPoint
                if (from != null && point != null) {
                    val source = nodes.find { it.id == from }
                    if (source != null) {
                        val center = centerOf(source)
                        val geometry = edgeGeometry(center, point, nodeWidthPx, nodeHeightPx)
                        if (geometry != null) {
                            drawPath(
                                path = Path().apply {
                                    moveTo(geometry.start.x, geometry.start.y)
                                    lineTo(point.x, point.y)
                                },
                                color = edgeActiveColor,
                                style = Stroke(
                                    width = 2f,
                                    cap = StrokeCap.Round,
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f),
                                ),
                            )
                        }
                    }
                }
            }

            nodes.forEach { node ->
                val position = positionOf(node)
                WorkflowNodeCard(
                    node = node,
                    selected = node.id == selectedNodeId,
                    connectingHover = node.id == hoverTargetId,
                    state = nodeStates[node.id],
                    accent = accentByType[node.type] ?: MaterialTheme.semanticColors.subtleText,
                    modifier = Modifier
                        .offset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }
                        .width(NodeWidth)
                        .height(NodeHeight)
                        .pointerInput(node.id) {
                            detectTapGestures(onTap = { onSelectNode(node.id) })
                        }
                        .pointerInput(node.id) {
                            var dragged = false
                            detectDragGestures(
                                onDragStart = { dragged = false },
                                onDrag = { change, amount ->
                                    change.consume()
                                    dragged = true
                                    val base = dragPositions[node.id] ?: Offset(node.position.x, node.position.y)
                                    dragPositions[node.id] = base + amount / scaleNow
                                    dragPositions[node.id]?.let { moving ->
                                        hoverTargetId = hitNodeAt(
                                            moving + Offset(nodeWidthPx / 2f, nodeHeightPx / 2f)
                                        )?.takeIf { it.id != node.id }?.id
                                    }
                                },
                                onDragEnd = {
                                    val settled = dragPositions[node.id]
                                    if (settled != null) {
                                        // 对齐网格：手松在哪就落在最近的格点上，连线端点才不会歪。
                                        val snappedX = (settled.x / cellPx).roundToInt() * cellPx
                                        val snappedY = (settled.y / cellPx).roundToInt() * cellPx
                                        onMoveNode(node.id, snappedX, snappedY)
                                        if (dragged) {
                                            val targetId = hitNodeAt(
                                                Offset(snappedX + nodeWidthPx / 2f, snappedY + nodeHeightPx / 2f)
                                            )?.takeIf { it.id != node.id }?.id
                                            if (targetId != null) onConnect(node.id, targetId)
                                        }
                                    }
                                    dragPositions.remove(node.id)
                                    hoverTargetId = null
                                },
                                onDragCancel = {
                                    dragPositions.remove(node.id)
                                    hoverTargetId = null
                                },
                            )
                        },
                )
            }

            // 连线点单独排在卡片之后：放在卡片内部会被 Surface 的圆角裁掉，手指也按不到超出卡片的部分。
            nodes.forEach { node ->
                val position = positionOf(node)
                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (position.x + nodeWidthPx - handleOffsetPx).roundToInt(),
                                (position.y + nodeHeightPx / 2f - handleOffsetPx).roundToInt(),
                            )
                        }
                        .size(24.dp)
                        .pointerInput(node.id) {
                            detectDragGestures(
                                onDragStart = {
                                    connectFrom = node.id
                                    connectPoint = positionOf(node) + Offset(nodeWidthPx, nodeHeightPx / 2f)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    connectPoint = (connectPoint ?: Offset.Zero) + amount / scaleNow
                                    connectPoint?.let { hoverTargetId = hitNodeAt(it)?.takeIf { it.id != node.id }?.id }
                                },
                                onDragEnd = {
                                    val targetId = connectPoint?.let { hitNodeAt(it) }?.takeIf { it.id != node.id }?.id
                                    if (targetId != null) onConnect(node.id, targetId)
                                    connectFrom = null
                                    connectPoint = null
                                    hoverTargetId = null
                                },
                                onDragCancel = {
                                    connectFrom = null
                                    connectPoint = null
                                    hoverTargetId = null
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(MaterialTheme.semanticColors.cardSurface, RoundedCornerShape(Radius.pill)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(edgeActiveColor, RoundedCornerShape(Radius.pill)),
                        )
                    }
                }
            }
        }

        ZoomControls(
            scale = scale,
            onZoomIn = { scale = (scale * 1.2f).coerceAtMost(MAX_SCALE) },
            onZoomOut = { scale = (scale / 1.2f).coerceAtLeast(MIN_SCALE) },
            onFit = { fitToContent() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.md),
        )
    }
}

/** 缩放工具条：手势之外留一条明确入口，平板/大屏上双指缩放不好用。 */
@Composable
private fun ZoomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.pill),
        color = MaterialTheme.semanticColors.cardSurface,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.xs, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onZoomOut, modifier = Modifier.size(32.dp)) {
                Icon(
                    FeatherIcons.Minus,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_out),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "${(scale * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onZoomIn, modifier = Modifier.size(32.dp)) {
                Icon(
                    FeatherIcons.Plus,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_in),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFit, modifier = Modifier.size(32.dp)) {
                Icon(
                    FeatherIcons.Maximize,
                    contentDescription = stringResource(R.string.workflow_editor_zoom_fit),
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 连接线的短标签：条件分支只显示分支关键字，正则只露前 10 个字符。 */
private fun connectionLabel(connection: WorkflowNodeConnection): String {
    val raw = connection.condition?.trim().orEmpty()
    if (raw.isEmpty()) return ""
    return when (raw.lowercase()) {
        "true", "on_success", "success", "ok" -> "T"
        "false" -> "F"
        "on_error", "error", "failed" -> "E"
        else -> if (raw.length > 10) raw.take(10) + "…" else raw
    }
}

private data class EdgeGeometry(
    val start: Offset,
    val control1: Offset,
    val control2: Offset,
    val end: Offset,
) {
    fun pointAt(t: Float): Offset {
        val inv = 1 - t
        val x = inv * inv * inv * start.x + 3 * inv * inv * t * control1.x +
            3 * inv * t * t * control2.x + t * t * t * end.x
        val y = inv * inv * inv * start.y + 3 * inv * inv * t * control1.y +
            3 * inv * t * t * control2.y + t * t * t * end.y
        return Offset(x, y)
    }

    fun angleAt(t: Float): Float {
        val inv = 1 - t
        val dx = 3 * inv * inv * (control1.x - start.x) + 6 * inv * t * (control2.x - control1.x) +
            3 * t * t * (end.x - control2.x)
        val dy = 3 * inv * inv * (control1.y - start.y) + 6 * inv * t * (control2.y - control1.y) +
            3 * t * t * (end.y - control2.y)
        return atan2(dy, dx)
    }
}

/** 三次贝塞尔：起点终点落在节点边框上，控制点水平外推，看起来像「从边里流出去」。 */
private fun edgeGeometry(
    sourceCenter: Offset,
    targetCenter: Offset,
    nodeWidth: Float,
    nodeHeight: Float,
): EdgeGeometry? {
    val delta = targetCenter - sourceCenter
    val distance = hypot(delta.x, delta.y)
    if (distance <= 1f) return null
    val dirX = delta.x / distance
    val dirY = delta.y / distance
    val start = boxEdgePoint(sourceCenter, dirX, dirY, nodeWidth, nodeHeight)
    val end = boxEdgePoint(targetCenter, -dirX, -dirY, nodeWidth, nodeHeight)
    val controlDistance = hypot(end.x - start.x, end.y - start.y) * 0.4f
    return EdgeGeometry(
        start = start,
        control1 = Offset(start.x + controlDistance, start.y),
        control2 = Offset(end.x - controlDistance, end.y),
        end = end,
    )
}

/** 射线与轴对齐矩形的最近交点。 */
private fun boxEdgePoint(center: Offset, dirX: Float, dirY: Float, width: Float, height: Float): Offset {
    val halfWidth = width / 2f
    val halfHeight = height / 2f
    val absX = abs(dirX)
    val absY = abs(dirY)
    val tRight = if (dirX > 0f && absX > 0f) halfWidth / absX else Float.POSITIVE_INFINITY
    val tLeft = if (dirX < 0f && absX > 0f) halfWidth / absX else Float.POSITIVE_INFINITY
    val tBottom = if (dirY > 0f && absY > 0f) halfHeight / absY else Float.POSITIVE_INFINITY
    val tTop = if (dirY < 0f && absY > 0f) halfHeight / absY else Float.POSITIVE_INFINITY
    val t = min(min(tRight, tLeft), min(tBottom, tTop))
    return Offset(center.x + dirX * t, center.y + dirY * t)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowAt(
    tip: Offset,
    angle: Float,
    color: Color,
    size: Float,
) {
    val spread = (Math.PI / 7).toFloat()
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - size * cos(angle - spread), tip.y - size * sin(angle - spread))
        lineTo(tip.x - size * cos(angle + spread), tip.y - size * sin(angle + spread))
        close()
    }
    drawPath(path, color)
}

/** 点到连线的距离：沿曲线采样，够近就算点中。 */
private fun hitTestEdges(
    world: Offset,
    nodes: List<WorkflowNode>,
    connections: List<WorkflowNodeConnection>,
    positionOf: (WorkflowNode) -> Offset,
    nodeWidth: Float,
    nodeHeight: Float,
    threshold: Float,
): String? {
    connections.forEach { connection ->
        val source = nodes.find { it.id == connection.sourceNodeId } ?: return@forEach
        val target = nodes.find { it.id == connection.targetNodeId } ?: return@forEach
        val sourceCenter = positionOf(source) + Offset(nodeWidth / 2f, nodeHeight / 2f)
        val targetCenter = positionOf(target) + Offset(nodeWidth / 2f, nodeHeight / 2f)
        val geometry = edgeGeometry(sourceCenter, targetCenter, nodeWidth, nodeHeight) ?: return@forEach
        for (step in 4..28) {
            val point = geometry.pointAt(step / 32f)
            if (hypot(point.x - world.x, point.y - world.y) <= threshold) return connection.id
        }
    }
    return null
}

@Composable
private fun WorkflowNodeCard(
    node: WorkflowNode,
    selected: Boolean,
    connectingHover: Boolean,
    state: NodeExecutionState?,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val stateColor = when (state) {
        is NodeExecutionState.Failed -> MaterialTheme.colorScheme.error
        is NodeExecutionState.Success -> MaterialTheme.semanticColors.success
        is NodeExecutionState.Skipped -> MaterialTheme.semanticColors.subtleText
        else -> null
    }
    val borderColor = when {
        connectingHover -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.primary
        stateColor != null -> stateColor
        else -> MaterialTheme.semanticColors.subtleBorder
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.mdLarge),
        color = MaterialTheme.semanticColors.cardSurface,
        border = BorderStroke(if (selected || connectingHover) 2.dp else 1.dp, borderColor),
        shadowElevation = if (selected) 6.dp else 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(accent, RoundedCornerShape(Radius.pill)),
                )
                Text(
                    text = node.typeLabel(),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = Spacing.xs),
                )
            }
            Text(
                text = node.name.ifBlank { node.typeLabel() },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = node.subtitle(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun WorkflowNode.typeLabel(): String = when (this) {
    is TriggerNode -> stringResource(R.string.workflow_editor_node_trigger)
    is ExecuteNode -> stringResource(R.string.workflow_editor_node_execute)
    is ConditionNode -> stringResource(R.string.workflow_editor_node_condition)
    is LogicNode -> stringResource(R.string.workflow_editor_node_logic)
    is ExtractNode -> stringResource(R.string.workflow_editor_node_extract)
    else -> stringResource(R.string.workflow_editor_node_unknown)
}

private fun WorkflowNode.subtitle(): String = when (this) {
    is TriggerNode -> triggerType
    is ExecuteNode -> actionType
    is ConditionNode -> operator.name
    is LogicNode -> operator.name
    is ExtractNode -> mode.name
    else -> type
}
