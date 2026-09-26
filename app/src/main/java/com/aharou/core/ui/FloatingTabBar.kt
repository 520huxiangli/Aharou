package com.aharou.core.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.Spacing
import com.aharou.core.theme.semanticColors
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底部悬浮 tab 栏的单个 tab 项：图标 + 文案。 */
data class FloatingTabItem(
    val icon: ImageVector,
    val label: String
)

/**
 * 底部悬浮液态玻璃 Tab 栏（PagerState 联动版）：
 * - 滑动页面主体时，底栏指示器 100% 毫秒级跟手同步平移；
 * - 长按底栏左右拖拽时，上方页面 1:1 同步平滑滑动，松手弹簧吸附；
 * - 点击 Tab 项平滑滚动至对应页面。
 */
@Composable
fun FloatingTabBar(
    pagerState: PagerState,
    items: List<FloatingTabItem>,
    maskColor: Color,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false
) {
    val coroutineScope = rememberCoroutineScope()
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val contentAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.35f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabbar-content-alpha"
    )

    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val surfaceVariantColor = colorScheme.surfaceVariant
    val outlineVariantColor = colorScheme.outlineVariant
    val primaryContainerColor = colorScheme.primaryContainer
    val cardSurfaceColor = MaterialTheme.semanticColors.cardSurface
    val mutedSurfaceColor = MaterialTheme.semanticColors.mutedSurface
    val pageBackgroundColor = MaterialTheme.semanticColors.pageBackground

    // 材质与描边画笔
    val glassBackgroundBrush = remember(isLight, surfaceColor, surfaceVariantColor, cardSurfaceColor, mutedSurfaceColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(cardSurfaceColor.copy(alpha = 0.94f), mutedSurfaceColor.copy(alpha = 0.90f))
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    surfaceVariantColor.copy(alpha = 0.92f),
                    surfaceColor.copy(alpha = 0.88f)
                )
            )
        }
    }

    val glassBorderBrush = remember(isLight, outlineVariantColor, pageBackgroundColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.14f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    outlineVariantColor.copy(alpha = 0.55f),
                    outlineVariantColor.copy(alpha = 0.20f),
                    pageBackgroundColor.copy(alpha = 0.60f)
                )
            )
        }
    }

    val tabBounds = remember { mutableStateMapOf<Int, Rect>() }
    val density = LocalDensity.current

    // 计算 Pager 连续位置（支持页面滑动与底栏双向插值）
    val continuousPosition by remember {
        derivedStateOf {
            (pagerState.currentPage + pagerState.currentPageOffsetFraction).coerceIn(0f, items.lastIndex.toFloat())
        }
    }

    val floorIndex = continuousPosition.toInt().coerceIn(0, items.lastIndex)
    val ceilIndex = (floorIndex + 1).coerceIn(0, items.lastIndex)
    val fraction = continuousPosition - floorIndex

    val floorBounds = tabBounds[floorIndex]
    val ceilBounds = tabBounds[ceilIndex]

    val indicatorX = if (floorBounds != null && ceilBounds != null) {
        floorBounds.left + (ceilBounds.left - floorBounds.left) * fraction
    } else {
        tabBounds[pagerState.currentPage]?.left ?: 0f
    }

    // 果冻拉伸：在位移时根据 offsetFraction 产生平滑微拉伸
    val stretchScaleX = 1f + 0.14f * (1f - abs(fraction - 0.5f) * 2f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(
                Brush.verticalGradient(
                    listOf(maskColor.copy(alpha = 0f), maskColor.copy(alpha = 0.98f))
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .graphicsLayer { alpha = contentAlpha }
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(Radius.pill),
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color(0xFF040A14).copy(alpha = 0.35f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color(0xFF040A14).copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(Radius.pill))
                .background(glassBackgroundBrush)
                .border(1.dp, glassBorderBrush, RoundedCornerShape(Radius.pill))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .pointerInput(items) {
                    detectDragGesturesAfterLongPress(
                        onDrag = { change, _ ->
                            change.consume()
                            val x = change.position.x
                            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f
                            if (tabWidth > 0f && items.size > 1) {
                                val minX = tabBounds.values.minOfOrNull { it.left } ?: 0f
                                val maxX = (tabBounds.values.maxOfOrNull { it.right } ?: tabWidth) - tabWidth
                                val totalDistance = (maxX - minX).coerceAtLeast(1f)
                                val boundedX = (x - tabWidth / 2f).coerceIn(minX, maxX)
                                val progress = (boundedX - minX) / totalDistance
                                val targetPageFloat = (progress * items.lastIndex).coerceIn(0f, items.lastIndex.toFloat())

                                coroutineScope.launch {
                                    val page = targetPageFloat.roundToInt().coerceIn(0, items.lastIndex)
                                    val offsetFraction = (targetPageFloat - page).coerceIn(-0.5f, 0.5f)
                                    pagerState.scrollToPage(page, offsetFraction)
                                }
                            }
                        },
                        onDragEnd = {
                            coroutineScope.launch {
                                val targetPage = (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
                                pagerState.animateScrollToPage(
                                    page = targetPage.coerceIn(0, items.lastIndex),
                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                                )
                            }
                        },
                        onDragCancel = {
                            coroutineScope.launch {
                                val targetPage = (pagerState.currentPage + pagerState.currentPageOffsetFraction).roundToInt()
                                pagerState.animateScrollToPage(
                                    page = targetPage.coerceIn(0, items.lastIndex),
                                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                                )
                            }
                        }
                    )
                }
        ) {
            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f

            if (tabWidth > 0f) {
                val indicatorWidthDp = with(density) { tabWidth.toDp() }
                val indicatorBrush = remember(isLight, primaryContainerColor) {
                    if (isLight) {
                        Brush.verticalGradient(
                            listOf(primaryContainerColor.copy(alpha = 0.95f), primaryContainerColor.copy(alpha = 0.85f))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(primaryContainerColor.copy(alpha = 0.90f), primaryContainerColor.copy(alpha = 0.72f))
                        )
                    }
                }

                val indicatorBorderBrush = remember(isLight, primaryContainerColor) {
                    Brush.verticalGradient(
                        listOf(
                            if (isLight) Color.Black.copy(alpha = 0.08f) else primaryContainerColor.copy(alpha = 0.35f),
                            if (isLight) Color.Black.copy(alpha = 0.02f) else primaryContainerColor.copy(alpha = 0.08f)
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(indicatorX.roundToInt(), 0) }
                        .graphicsLayer {
                            scaleX = stretchScaleX
                            scaleY = (2f - stretchScaleX).coerceIn(0.94f, 1f)
                        }
                        .width(indicatorWidthDp)
                        .height(44.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(Radius.pill),
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(indicatorBrush)
                        .border(1.dp, indicatorBorderBrush, RoundedCornerShape(Radius.pill))
                )
            }

            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == pagerState.currentPage
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val tabScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "tab-press-scale"
                    )

                    val fgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tab-fg-color"
                    )

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab-icon-scale"
                    )

                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { tabBounds[index] = it.boundsInParent() }
                            .widthIn(min = 84.dp)
                            .graphicsLayer {
                                scaleX = tabScale
                                scaleY = tabScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = {
                                    coroutineScope.launch {
                                        pagerState.animateScrollToPage(
                                            page = index,
                                            animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                                        )
                                    }
                                }
                            )
                            .padding(horizontal = Spacing.md, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            tint = fgColor
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = fgColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

/**
 * 底部悬浮液态玻璃 Tab 栏（基础选中索引版）：
 * 用于非 Pager 页面的向后兼容。
 */
@Composable
fun FloatingTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    items: List<FloatingTabItem>,
    maskColor: Color,
    modifier: Modifier = Modifier,
    isScrolling: Boolean = false
) {
    val contentAlpha by animateFloatAsState(
        targetValue = if (isScrolling) 0.35f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "tabbar-content-alpha"
    )

    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val colorScheme = MaterialTheme.colorScheme
    val surfaceColor = colorScheme.surface
    val surfaceVariantColor = colorScheme.surfaceVariant
    val outlineVariantColor = colorScheme.outlineVariant
    val primaryContainerColor = colorScheme.primaryContainer
    val cardSurfaceColor = MaterialTheme.semanticColors.cardSurface
    val mutedSurfaceColor = MaterialTheme.semanticColors.mutedSurface
    val pageBackgroundColor = MaterialTheme.semanticColors.pageBackground

    val glassBackgroundBrush = remember(isLight, surfaceColor, surfaceVariantColor, cardSurfaceColor, mutedSurfaceColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(cardSurfaceColor.copy(alpha = 0.94f), mutedSurfaceColor.copy(alpha = 0.90f))
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    surfaceVariantColor.copy(alpha = 0.92f),
                    surfaceColor.copy(alpha = 0.88f)
                )
            )
        }
    }

    val glassBorderBrush = remember(isLight, outlineVariantColor, pageBackgroundColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(
                    Color.Black.copy(alpha = 0.12f),
                    Color.Black.copy(alpha = 0.06f),
                    Color.Black.copy(alpha = 0.14f)
                )
            )
        } else {
            Brush.verticalGradient(
                listOf(
                    outlineVariantColor.copy(alpha = 0.55f),
                    outlineVariantColor.copy(alpha = 0.20f),
                    pageBackgroundColor.copy(alpha = 0.60f)
                )
            )
        }
    }

    val tabBounds = remember { mutableStateMapOf<Int, Rect>() }
    val currentSelected by rememberUpdatedState(selected)
    val density = LocalDensity.current

    var dragX by remember { mutableFloatStateOf(Float.NaN) }
    val isDragging = !dragX.isNaN()

    val indicatorTarget = if (isDragging) dragX else tabBounds[selected]?.left ?: 0f

    val indicatorX by animateFloatAsState(
        targetValue = indicatorTarget,
        animationSpec = if (isDragging) tween(0) else spring(
            dampingRatio = 0.76f,
            stiffness = 380f
        ),
        label = "indicator-x"
    )

    val stretchScale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        stretchScale.snapTo(1.10f)
        stretchScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.65f, stiffness = Spring.StiffnessMediumLow)
        )
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(68.dp)
            .background(
                Brush.verticalGradient(
                    listOf(maskColor.copy(alpha = 0f), maskColor.copy(alpha = 0.98f))
                )
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Box(
            modifier = Modifier
                .padding(bottom = 6.dp)
                .graphicsLayer { alpha = contentAlpha }
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(Radius.pill),
                    ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color(0xFF040A14).copy(alpha = 0.35f),
                    spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color(0xFF040A14).copy(alpha = 0.50f)
                )
                .clip(RoundedCornerShape(Radius.pill))
                .background(glassBackgroundBrush)
                .border(1.dp, glassBorderBrush, RoundedCornerShape(Radius.pill))
                .padding(horizontal = 6.dp, vertical = 3.dp)
                .pointerInput(items) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { start ->
                            val w = tabBounds.values.firstOrNull()?.width ?: 0f
                            dragX = start.x - w / 2f
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val x = change.position.x
                            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f
                            if (tabWidth > 0f) {
                                val minX = tabBounds.values.minOfOrNull { it.left } ?: 0f
                                val maxX = (tabBounds.values.maxOfOrNull { it.right } ?: tabWidth) - tabWidth
                                dragX = (x - tabWidth / 2f).coerceIn(minX, maxX)
                            }
                            val target = items.indices.minByOrNull { abs((tabBounds[it]?.center?.x ?: x) - x) }
                            if (target != null && target != currentSelected) onSelect(target)
                        },
                        onDragEnd = { dragX = Float.NaN },
                        onDragCancel = { dragX = Float.NaN }
                    )
                }
        ) {
            val tabWidth = tabBounds.values.firstOrNull()?.width ?: 0f

            if (tabWidth > 0f) {
                val indicatorWidthDp = with(density) { tabWidth.toDp() }
                val indicatorBrush = remember(isLight, primaryContainerColor) {
                    if (isLight) {
                        Brush.verticalGradient(
                            listOf(primaryContainerColor.copy(alpha = 0.95f), primaryContainerColor.copy(alpha = 0.85f))
                        )
                    } else {
                        Brush.verticalGradient(
                            listOf(primaryContainerColor.copy(alpha = 0.90f), primaryContainerColor.copy(alpha = 0.72f))
                        )
                    }
                }

                val indicatorBorderBrush = remember(isLight, primaryContainerColor) {
                    Brush.verticalGradient(
                        listOf(
                            if (isLight) Color.Black.copy(alpha = 0.08f) else primaryContainerColor.copy(alpha = 0.35f),
                            if (isLight) Color.Black.copy(alpha = 0.02f) else primaryContainerColor.copy(alpha = 0.08f)
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset(indicatorX.roundToInt(), 0) }
                        .graphicsLayer {
                            scaleX = if (isDragging) 1.05f else stretchScale.value
                            scaleY = if (isDragging) 0.98f else (2f - stretchScale.value).coerceIn(0.95f, 1f)
                        }
                        .width(indicatorWidthDp)
                        .height(44.dp)
                        .shadow(
                            elevation = 2.dp,
                            shape = RoundedCornerShape(Radius.pill),
                            ambientColor = Color.Black.copy(alpha = 0.06f),
                            spotColor = Color.Black.copy(alpha = 0.08f)
                        )
                        .clip(RoundedCornerShape(Radius.pill))
                        .background(indicatorBrush)
                        .border(1.dp, indicatorBorderBrush, RoundedCornerShape(Radius.pill))
                )
            }

            Row(
                modifier = Modifier,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                items.forEachIndexed { index, item ->
                    val isSelected = index == selected
                    val interactionSource = remember(index) { MutableInteractionSource() }
                    val isPressed by interactionSource.collectIsPressedAsState()

                    val tabScale by animateFloatAsState(
                        targetValue = if (isPressed) 0.94f else 1f,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "tab-press-scale"
                    )

                    val fgColor by animateColorAsState(
                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                        label = "tab-fg-color"
                    )

                    val iconScale by animateFloatAsState(
                        targetValue = if (isSelected) 1.08f else 1f,
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        label = "tab-icon-scale"
                    )

                    Column(
                        modifier = Modifier
                            .onGloballyPositioned { tabBounds[index] = it.boundsInParent() }
                            .widthIn(min = 84.dp)
                            .graphicsLayer {
                                scaleX = tabScale
                                scaleY = tabScale
                            }
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                onClick = { onSelect(index) }
                            )
                            .padding(horizontal = Spacing.md, vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = item.icon,
                            contentDescription = null,
                            modifier = Modifier
                                .size(20.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                },
                            tint = fgColor
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = fgColor,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}
