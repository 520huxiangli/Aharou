package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aharou.R

/** 触摸热区宽度：视觉上只有一条细线，但要给手指留够可抓取的宽度。 */
private val HandleTouchWidth = 14.dp

/** 排版中真正承担左右分栏分割线的宽度，与 [VerticalDivider] 的默认厚度一致。 */
private val DividerWidth = 1.dp

/**
 * 左右分栏之间的拖拽分割条浮层。[onDragDelta] 收到的是水平位移像素，由调用方换算成分栏比例。
 *
 * 排版中的实际分割线由 [SplitPaneLayout] 内的 [VerticalDivider]（[DividerWidth]）承担，保证左右面板
 * 无缝贴合、无留白；本组件作为 [HandleTouchWidth] 宽度的触控浮层，以 [anchorX]（左栏右边缘，即分割
 * 线起点）为中心骑跨在分割线上，既不挤占 [Row] 的排版宽度，又保留完整的触摸与鼠标悬停热区。
 */
@Composable
fun VerticalSplitHandle(
    anchorX: Int,
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val description = stringResource(R.string.workbench_split_handle)
    Box(
        modifier = modifier
            .offset {
                val touchWidthPx = HandleTouchWidth.roundToPx()
                val dividerWidthPx = DividerWidth.roundToPx()
                IntOffset(x = anchorX - (touchWidthPx - dividerWidthPx) / 2, y = 0)
            }
            .fillMaxHeight()
            .width(HandleTouchWidth)
            .semantics { contentDescription = description }
            .draggable(
                state = rememberDraggableState { onDragDelta(it) },
                orientation = Orientation.Horizontal,
                interactionSource = interactionSource
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        if (dragging) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * 大屏左右分栏容器。[paneOpen] 为 true 时按 [paneSplit] 比例分栏，左右面板之间由 [VerticalDivider]
 * 无缝贴合；[VerticalSplitHandle] 作为触控浮层骑跨在分割线上，不占 [Row] 排版宽度、不留透明缝隙。
 *
 * [leftPane] / [rightPane] 分别为左右面板内容；[overlay] 用于放在左面板与分割线之间、参与同一 [Row]
 * 排版的附加内容（如浮层弹窗）。拖动分割条的位移像素在此换算成分栏比例，并按 [minSplit]～[maxSplit] 夹取。
 */
@Composable
fun SplitPaneLayout(
    paneOpen: Boolean,
    paneSplit: Float,
    minSplit: Float,
    maxSplit: Float,
    onSplitChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
    rightPane: @Composable () -> Unit,
    leftPane: @Composable () -> Unit
) {
    // 拖动把手时要把位移像素换算成分栏比例，所以记下容器与左栏的实测宽度。
    var rowWidthPx by remember { mutableIntStateOf(0) }
    var leftPaneWidthPx by remember { mutableIntStateOf(0) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onSizeChanged { rowWidthPx = it.width }
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(if (paneOpen) paneSplit else 1f)
                    .onSizeChanged { leftPaneWidthPx = it.width }
            ) {
                leftPane()
            }
            overlay()
            if (paneOpen) {
                VerticalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = DividerWidth
                )
                Box(modifier = Modifier.weight(1f - paneSplit)) {
                    rightPane()
                }
            }
        }
        if (paneOpen && leftPaneWidthPx > 0) {
            VerticalSplitHandle(
                anchorX = leftPaneWidthPx,
                onDragDelta = { dx ->
                    if (rowWidthPx > 0) {
                        onSplitChange((paneSplit + dx / rowWidthPx).coerceIn(minSplit, maxSplit))
                    }
                }
            )
        }
    }
}
