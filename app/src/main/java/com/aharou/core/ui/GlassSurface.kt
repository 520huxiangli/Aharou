package com.aharou.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.aharou.core.theme.Radius
import com.aharou.core.theme.semanticColors

/**
 * 液态玻璃容器：半透明渐变底色 + 斜向 specular 高光 + 顶亮/底暗描边 + 外投影。
 *
 * Compose 没有 backdrop blur，材质只能靠这几层叠出来（与底栏 [FloatingTabBar] 同一套参数，
 * 两处观感才对得上）。顶栏胶囊、配置入口卡片、设置分组卡片都走这里，不要在调用处各画一套。
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    shadowElevation: Dp = 6.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit
) {
    // Radius.xl 是 @Composable getter，不能写在默认参数里，只能在这里兜底
    val surfaceShape = shape ?: RoundedCornerShape(Radius.xl)
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val colorScheme = MaterialTheme.colorScheme
    val cardSurfaceColor = MaterialTheme.semanticColors.cardSurface
    val mutedSurfaceColor = MaterialTheme.semanticColors.mutedSurface
    val surfaceColor = colorScheme.surface
    val surfaceVariantColor = colorScheme.surfaceVariant
    val outlineVariantColor = colorScheme.outlineVariant
    val pageBackgroundColor = MaterialTheme.semanticColors.pageBackground

    val backgroundBrush = remember(isLight, surfaceColor, surfaceVariantColor, cardSurfaceColor, mutedSurfaceColor) {
        if (isLight) {
            Brush.verticalGradient(
                listOf(cardSurfaceColor.copy(alpha = 0.74f), mutedSurfaceColor.copy(alpha = 0.62f))
            )
        } else {
            Brush.verticalGradient(
                listOf(surfaceVariantColor.copy(alpha = 0.74f), surfaceColor.copy(alpha = 0.62f))
            )
        }
    }

    val highlightBrush = remember(isLight) {
        Brush.linearGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = if (isLight) 0.50f else 0.16f),
                0.42f to Color.White.copy(alpha = if (isLight) 0.08f else 0.03f),
                1f to Color.White.copy(alpha = if (isLight) 0.02f else 0.08f)
            ),
            start = Offset.Zero,
            end = Offset.Infinite
        )
    }

    val borderBrush = remember(isLight, outlineVariantColor, pageBackgroundColor) {
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

    Box(
        modifier = modifier
            .shadow(
                elevation = shadowElevation,
                shape = surfaceShape,
                ambientColor = if (isLight) Color.Black.copy(alpha = 0.08f) else Color(0xFF040A14).copy(alpha = 0.35f),
                spotColor = if (isLight) Color.Black.copy(alpha = 0.14f) else Color(0xFF040A14).copy(alpha = 0.50f)
            )
            .clip(surfaceShape)
            .background(backgroundBrush)
            .drawWithContent {
                drawContent()
                // 光从上方来：顶边一条亮线、底边一条暗线，玻璃的「厚度」就靠这两笔
                val stroke = 1.dp.toPx()
                drawLine(
                    color = Color.White.copy(alpha = if (isLight) 0.55f else 0.22f),
                    start = Offset(size.width * 0.10f, stroke / 2),
                    end = Offset(size.width * 0.90f, stroke / 2),
                    strokeWidth = stroke
                )
                drawLine(
                    color = Color.Black.copy(alpha = if (isLight) 0.05f else 0.18f),
                    start = Offset(size.width * 0.10f, size.height - stroke / 2),
                    end = Offset(size.width * 0.90f, size.height - stroke / 2),
                    strokeWidth = stroke
                )
            }
            .border(1.dp, borderBrush, surfaceShape)
            .padding(contentPadding)
    ) {
        // specular 高光整层铺在内容之下：左上偏亮、右下微亮，模拟表面反射
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(highlightBrush)
        )
        content()
    }
}
