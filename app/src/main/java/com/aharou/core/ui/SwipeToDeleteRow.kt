package com.aharou.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.R
import com.aharou.core.theme.semanticColors
import compose.icons.FeatherIcons
import compose.icons.feathericons.Trash2
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** 删除按钮的布局宽度上限：视觉宽度由滑动进度在 graphicsLayer 里缩放得出。 */
private val DeleteButtonMaxWidth = 104.dp

/** 按钮左端与表层之间的留白：露出距离不足它时视为未滑开。 */
private val DeleteButtonGap = 8.dp

/**
 * 全项目统一的左滑删除行容器：底层红色删除按钮固定在右端，随左滑露出（带缩放/透明度渐变），
 * 表层内容整体左滑，滑开时点击表层收回。
 *
 * 手势与动画参数全项目保持一致（露出宽度 -112dp、按钮上限 104dp、回弹弹簧参数），改这里即全局生效。
 *
 * 底层按钮常驻组合，宽度 / 透明度 / 缩放全部在 `graphicsLayer { }` 里按动画值现算，
 * 组合期不读动画状态——否则滑动时每一帧都会重组整行，长列表掉帧。
 *
 * @param onDelete 点按删除按钮后触发（先收回再回调）。是否弹二次确认由调用方决定。
 * @param onClick 表层未滑开时的点击回调；null 表示整行不可点击。
 * @param onLongClick 表层长按回调；null 表示不接长按。
 * @param deleteEnabled false 时删除按钮可露出但点击无效（如未下载完成的镜像不可删）。
 * @param selectionMode 多选态：左滑删除停用、点击改为切换选中、行底色随 [selected] 变化。
 * @param selected 多选态下本行是否已选中（非多选态忽略）。
 * @param onToggleSelect 多选态下点击本行时触发；由调用方决定切哪一个 id。
 * @param selectable false 表示本行不参与多选（如内置项），多选态下点击无效。
 * @param content 表层行内容；背景由本组件提供，内边距由调用方内容自带（与既有各页用法一致）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    deleteEnabled: Boolean = true,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onToggleSelect: () -> Unit = {},
    selectable: Boolean = true,
    content: @Composable () -> Unit
) {
    val rowBackground = MaterialTheme.semanticColors.cardSurface
    // 选中底色用品牌主色的低透明度叠加，跟随主题与动态取色；不硬编码色值。
    val selectionTint = if (selectionMode && selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    } else {
        null
    }
    val density = LocalDensity.current
    val revealPx = remember(density) { with(density) { -112.dp.toPx() } }
    // 与原先「按钮宽度 > 8dp 才可点」等价：露出不足一个留白时，常驻的按钮不接删除
    val minRevealPx = remember(density) { with(density) { DeleteButtonGap.toPx() } }
    val offsetX = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    // 进入多选态时把已滑开的行收回去：多选态下手势禁用，留着露出状态会挡住点击
    LaunchedEffect(selectionMode) {
        if (selectionMode && offsetX.value != 0f) offsetX.animateTo(0f)
    }

    // 不可删除时按钮呈灰色，与可删状态区分
    val buttonBg = if (deleteEnabled) Color(0xFFEF4444) else Color(0xFF9CA3AF)
    val buttonBorder = if (deleteEnabled) Color(0xFFF87171) else Color(0xFFD1D5DB)

    Box(modifier = modifier.fillMaxWidth()) {
        // 底层删除按钮：常驻组合，滑动进度只在绘制阶段换算成宽度与透明度
        Row(
            modifier = Modifier
                .matchParentSize()
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(DeleteButtonMaxWidth)
                    .graphicsLayer {
                        val maxWidthPx = DeleteButtonMaxWidth.toPx()
                        val revealedPx = -offsetX.value
                        val widthPx = (revealedPx - DeleteButtonGap.toPx()).coerceIn(0f, maxWidthPx)
                        val progress = widthPx / maxWidthPx
                        // 以右边缘为锚点横向缩放：等价于原先「右端固定、左端随滑动左移」的宽度变化
                        transformOrigin = TransformOrigin(1f, 0.5f)
                        scaleX = progress
                        scaleY = 0.7f + 0.3f * progress
                        alpha = (progress * 1.2f).coerceIn(0f, 1f)
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(buttonBg)
                    .border(1.dp, buttonBorder, RoundedCornerShape(10.dp))
                    .clickable(enabled = deleteEnabled && !selectionMode) {
                        // 按钮常驻后收起状态也占着位置：表层没接点击时事件会落到这里，
                        // 所以按当前位移判断是否真露出来了，避免误删
                        if (-offsetX.value > minRevealPx) {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                                onDelete()
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.requiredWidth(DeleteButtonMaxWidth),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = FeatherIcons.Trash2,
                        contentDescription = stringResource(R.string.common_delete),
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.common_delete),
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        ),
                        color = Color.White
                    )
                }
            }
        }

        // 表层分组行：手势回弹与滑动展开；滑开状态下点击先收回、不触发 onClick
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                .fillMaxWidth()
                .background(rowBackground)
                .then(
                    if (selectionTint != null) Modifier.background(selectionTint) else Modifier
                )
                .pointerInput(selectionMode) {
                    // 多选态下整行只作选中开关，左滑删除不可用
                    if (selectionMode) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = {
                            coroutineScope.launch { offsetX.stop() }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                if (offsetX.value < revealPx / 2) {
                                    offsetX.animateTo(
                                        targetValue = revealPx,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioLowBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                } else {
                                    offsetX.animateTo(
                                        targetValue = 0f,
                                        animationSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMedium
                                        )
                                    )
                                }
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                offsetX.animateTo(0f)
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            coroutineScope.launch {
                                val newOffset = (offsetX.value + dragAmount).coerceIn(revealPx * 1.15f, 0f)
                                offsetX.snapTo(newOffset)
                            }
                        }
                    )
                }
                .then(
                    when {
                        // 多选态：点击与长按都切换选中；不可选中的行不接手势
                        selectionMode -> if (selectable) {
                            Modifier.combinedClickable(
                                onClick = onToggleSelect,
                                onLongClick = onToggleSelect
                            )
                        } else {
                            Modifier
                        }
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            onClick = {
                                if (offsetX.value < -10f) {
                                    coroutineScope.launch {
                                        offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                    }
                                } else {
                                    onClick()
                                }
                            },
                            onLongClick = onLongClick
                        )
                        onClick != null -> Modifier.clickable {
                            if (offsetX.value < -10f) {
                                coroutineScope.launch {
                                    offsetX.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
                                }
                            } else {
                                onClick()
                            }
                        }
                        else -> Modifier
                    }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            content()
        }
    }
}
