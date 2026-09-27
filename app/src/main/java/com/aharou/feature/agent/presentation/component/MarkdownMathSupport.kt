package com.aharou.feature.agent.presentation.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.aharou.core.util.FileLogger
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 数学感知的 [ImageTransformer]：识别 [MarkdownPreprocessor] 生成的数学链接，用 jlatexmath 渲染成位图；
 * 其余（普通图片）链接一律委托给 [delegate]。
 *
 * @param delegate 处理非数学图片链接的下游 transformer（通常是本地图片渲染器或 NoOp）。
 * @param baseTextSizeSp 行内公式的基准字号（sp），与所在文本正文字号对齐。
 */
private object LatexBitmapCache {
    private data class Key(
        val link: String,
        val colorArgb: Int,
        val textSizePx: Float,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val bitmaps = object : LinkedHashMap<Key, Deferred<Bitmap?>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Deferred<Bitmap?>>?): Boolean = size > 64
    }
    fun getOrStartBitmap(
        link: String,
        colorArgb: Int,
        textSizePx: Float,
        render: suspend () -> Bitmap?
    ): Deferred<Bitmap?> = synchronized(bitmaps) {
        val key = Key(link, colorArgb, textSizePx)
        bitmaps[key] ?: scope.async { render() }.also { deferred ->
            bitmaps[key] = deferred
            deferred.invokeOnCompletion {
                if (deferred.getCompletionExceptionOrNull() != null) {
                    synchronized(bitmaps) {
                        if (bitmaps[key] === deferred) bitmaps.remove(key)
                    }
                }
            }
        }
    }
}

internal class MathImageTransformer(
    private val delegate: ImageTransformer,
    private val baseTextSizeSp: Float,
) : ImageTransformer {

    @Composable
    override fun transform(link: String): ImageData? {
        val spec = MarkdownPreprocessor.decodeMathLink(link) ?: return delegate.transform(link)
        val (latex, block) = spec

        val density = LocalDensity.current
        val colorArgb = LocalContentColor.current.toArgb()
        // 块级公式与正文使用同一字号，避免块级公式无必要地放大。
        val textSizePx = with(density) { baseTextSizeSp.sp.toPx() }
        // 首次组合时同步取得真实尺寸，避免用字符长度估算造成公式忽大忽小、宽度过大时
        // 从中间开始显示。尺寸一旦确定就不再异步替换，因此不会改写 LazyColumn 锚点。
        val layoutSize = remember(link, textSizePx) {
            measureLatex(latex, textSizePx)
        }
        val renderTask = remember(link, colorArgb, textSizePx) {
            LatexBitmapCache.getOrStartBitmap(link, colorArgb, textSizePx) {
                renderLatex(latex, textSizePx, colorArgb)
            }
        }
        val bitmap by produceState<Bitmap?>(initialValue = null, renderTask) {
            value = renderTask.await()
        }
        // 位图完成前后始终使用同一个布局尺寸。否则 Markdown 的 inline placeholder 会
        // 从 0 高度变为公式高度，快速 fling 时每个公式完成都会改写 LazyColumn 的锚点。
        val painter = remember(bitmap, layoutSize) {
            FixedLatexPainter(bitmap?.asImageBitmap(), layoutSize.width, layoutSize.height)
        }
        val widthDp = with(density) { layoutSize.width.toDp() }
        val heightDp = with(density) { layoutSize.height.toDp() }

        val base = if (block) {
            Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())
        } else {
            Modifier
        }
        return ImageData(
            painter = painter,
            modifier = base.then(Modifier.size(widthDp, heightDp)),
            contentScale = ContentScale.Fit,
        )
    }

    private fun measureLatex(latex: String, textSizePx: Float): android.util.Size {
        return try {
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .padding(2)
                .build()
            android.util.Size(
                drawable.intrinsicWidth.coerceAtLeast(1),
                drawable.intrinsicHeight.coerceAtLeast(1)
            )
        } catch (e: Exception) {
            android.util.Size(textSizePx.roundToInt().coerceAtLeast(1), textSizePx.roundToInt().coerceAtLeast(1))
        }
    }
    private class FixedLatexPainter(
        private val bitmap: androidx.compose.ui.graphics.ImageBitmap?,
        widthPx: Int,
        heightPx: Int,
    ) : Painter() {
        override val intrinsicSize: Size = Size(widthPx.toFloat(), heightPx.toFloat())

        override fun DrawScope.onDraw() {
            bitmap?.let { image ->
                val scale = minOf(
                    size.width / image.width,
                    size.height / image.height,
                )
                val drawWidth = (image.width * scale).roundToInt().coerceAtLeast(1)
                val drawHeight = (image.height * scale).roundToInt().coerceAtLeast(1)
                drawImage(
                    image = image,
                    dstSize = IntSize(drawWidth, drawHeight),
                    dstOffset = androidx.compose.ui.unit.IntOffset(
                        ((size.width - drawWidth) / 2f).roundToInt(),
                        ((size.height - drawHeight) / 2f).roundToInt(),
                    ),
                )
            }
        }
    }

    private fun renderLatex(latex: String, textSizePx: Float, colorArgb: Int): Bitmap? {
        return try {
            val drawable = JLatexMathDrawable.builder(latex)
                .textSize(textSizePx)
                .color(colorArgb)
                .padding(2)
                .build()
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas)
            bitmap
        } catch (e: Exception) {
            FileLogger.w(TAG, "LaTeX 渲染失败: $latex", e)
            null
        }
    }

    private companion object {
        const val TAG = "MarkdownMath"
    }
}
