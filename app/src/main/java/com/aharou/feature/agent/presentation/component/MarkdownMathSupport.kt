package com.aharou.feature.agent.presentation.component

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aharou.core.util.FileLogger
import com.mikepenz.markdown.model.ImageData
import com.mikepenz.markdown.model.ImageTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * 数学感知的 [ImageTransformer]：识别 [MarkdownPreprocessor] 生成的数学链接，用 jlatexmath 渲染成位图；
 * 其余（普通图片）链接一律委托给 [delegate]。
 *
 * @param delegate 处理非数学图片链接的下游 transformer（通常是本地图片渲染器或 NoOp）。
 * @param baseTextSizeSp 行内公式的基准字号（sp），与所在文本正文字号对齐。
 */
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
        // 块级公式略放大，行内与正文字号一致
        val textSizePx = with(density) { (baseTextSizeSp * if (block) 1.25f else 1f).sp.toPx() }

        val bitmap by produceState<Bitmap?>(initialValue = null, link, colorArgb, textSizePx) {
            value = withContext(Dispatchers.Default) { renderLatex(latex, textSizePx, colorArgb) }
        }
        val bmp = bitmap ?: return null
        val image = bmp.asImageBitmap()
        val widthDp = with(density) { image.width.toDp() }
        val heightDp = with(density) { image.height.toDp() }

        // ImageData 不含尺寸参数，用 Modifier.size 固定为按 density 换算后的 dp 尺寸，保证物理大小一致
        val base = if (block) {
            Modifier.padding(vertical = 4.dp).horizontalScroll(rememberScrollState())
        } else {
            Modifier
        }
        return ImageData(
            painter = BitmapPainter(image),
            modifier = base.then(Modifier.size(widthDp, heightDp)),
            contentScale = ContentScale.Fit,
        )
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
