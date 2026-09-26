package com.aharou.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.aharou.core.theme.Spacing

/**
 * 修复 Material3 ModalBottomSheet 已知 bug（issuetracker 486562294）：
 * 内容接近全屏高度且有可滚动区域时，快速上滑 fling 到内容边界，未消费的向上速度会传给
 * sheet 的拖拽状态，导致 sheet 反复「向下拖动再弹回」振荡。
 *
 * 挂到可滚动内容外层：sheet 已完全展开时吞掉向上方向的剩余 fling 速度，使速度不再流向
 * sheet 的 AnchoredDraggableState；内容自身的惯性滚动不受影响（只在子滚动消费完后拦截剩余量）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    remember(sheetState) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if ((sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded) && available.y < 0f) {
                    available
                } else {
                    Velocity.Zero
                }
        }
    }

/**
 * 自适应模态底栏/弹窗：
 * - 紧凑屏幕（手机竖屏、窄分屏等，< 600dp）：呈现为标准贴底 [ModalBottomSheet]，保留下拉手势与拖拽手柄，
 *   并内置 [rememberSheetFlingFix] 杜绝快速滑动到边界时的抽搐振荡；
 * - 宽屏/平板（>= 600dp）：呈现为规范的居中模态 [Dialog]，移除拖拽手柄，限制最大宽度并居中展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    sheetGesturesEnabled: Boolean = true,
    dialogMaxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isCompactWidth()) {
        val flingFix = rememberSheetFlingFix(sheetState)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            sheetGesturesEnabled = sheetGesturesEnabled
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                content()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = modifier
                    .widthIn(min = 280.dp, max = dialogMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
                shape = RoundedCornerShape(16.dp),
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md)
                ) {
                    content()
                }
            }
        }
    }
}
