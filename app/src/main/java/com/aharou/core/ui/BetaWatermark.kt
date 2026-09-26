package com.aharou.core.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import kotlin.math.hypot

/**
 * 全屏斜向平铺的「Beta」水印。
 *
 * 仅当 applicationId 以 `.beta` 结尾（见 beta buildType）时显示，供用户一眼区分测试包与正式版。
 * 无触摸手势，叠加在根布局最上层即可，不拦截交互。
 */
@Composable
fun BetaWatermark(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isBeta = remember(context) { context.applicationContext.packageName.endsWith(".beta") }
    if (!isBeta) return

    val textSizePx = with(LocalDensity.current) { 44.sp.toPx() }
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f).toArgb()

    Canvas(modifier = modifier.fillMaxSize()) {
        if (size.minDimension <= 0f) return@Canvas
        val paint = Paint().apply {
            isAntiAlias = true
            this.textSize = textSizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            this.color = color
        }
        val stepX = textSizePx * 4.5f
        val stepY = textSizePx * 3.2f
        // 画布绕中心旋转后四角会移出可视区，平铺范围按对角线加余量扩展，保证铺满全屏。
        val reach = hypot(size.width, size.height) / 2f + textSizePx * 2f
        val canvas = drawContext.canvas.nativeCanvas
        rotate(degrees = -30f) {
            var y = center.y - reach
            while (y <= center.y + reach) {
                var x = center.x - reach
                while (x <= center.x + reach) {
                    canvas.drawText("Beta", x, y, paint)
                    x += stepX
                }
                y += stepY
            }
        }
    }
}