package com.aharou.feature.settings.presentation.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.aharou.core.theme.Radius
import com.aharou.core.theme.semanticColors
import sh.calvin.reorderable.ReorderableColumn
import sh.calvin.reorderable.ReorderableItem

/**
 * 分组卡片列表的长按拖拽排序容器（非 Lazy，行间自带 [SettingsDivider]，外层由调用方套卡片与滚动容器）。
 *
 * 交互与「供应商」页一致：长按 [itemContent] 挂上手柄的区域触发拖拽，拖拽中缩放 + 阴影提升 + 层级前置，
 * 起止各一次触感反馈；[dragHandleLabel] 是无障碍播报用的说明。容器列表与连接列表共用这一份，
 * 避免两处各写一遍动画与手势。
 */
@Composable
internal fun <T> ReorderableCardList(
    items: List<T>,
    onSettle: (fromIndex: Int, toIndex: Int) -> Unit,
    dragHandleLabel: String,
    itemContent: @Composable (item: T, dragHandle: Modifier) -> Unit
) {
    val hapticFeedback = LocalHapticFeedback.current
    ReorderableColumn(list = items, onSettle = onSettle) { index, item, isDragging ->
        val dragScale by animateFloatAsState(
            targetValue = if (isDragging) 0.95f else 1f,
            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
            label = "reorderDragScale"
        )
        val dragElevation by animateDpAsState(
            targetValue = if (isDragging) 8.dp else 0.dp,
            animationSpec = tween(durationMillis = if (isDragging) 120 else 220),
            label = "reorderDragElevation"
        )
        ReorderableItem {
            val dragHandle = Modifier
                .longPressDraggableHandle(
                    onDragStarted = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    },
                    onDragStopped = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                    }
                )
                .semantics { contentDescription = dragHandleLabel }
            Column(modifier = Modifier.fillMaxWidth()) {
                if (index > 0) SettingsDivider()
                Surface(
                    shape = RoundedCornerShape(if (isDragging) Radius.mdLarge else 0.dp),
                    color = MaterialTheme.semanticColors.cardSurface,
                    shadowElevation = dragElevation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .zIndex(if (isDragging) 1f else 0f)
                        .graphicsLayer {
                            scaleX = dragScale
                            scaleY = dragScale
                        }
                ) {
                    itemContent(item, dragHandle)
                }
            }
        }
    }
}
