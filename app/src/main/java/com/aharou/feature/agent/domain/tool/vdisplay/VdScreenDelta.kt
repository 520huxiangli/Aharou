package com.aharou.feature.agent.domain.tool.vdisplay

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.abs
import java.io.File

/**
 * 动作前后的「屏幕变了没有」判定。
 *
 * 做法：把截图降采样后切成 [GRID]×[GRID] 块，取每块平均亮度做签名；两次动作的签名对比，
 * 有块差异超过阈值或整体均值差异超过阈值就算变了。
 *
 * 为什么不用整屏像素哈希：[ScreenFingerprint] 那种逐点量化对状态栏时钟、光标闪烁也敏感，
 * 单步动作后 500ms 的对比会频繁误报「变了」。分块亮度平均把这些小噪声抹掉，只留真实布局变化。
 *
 * 为什么按 500ms 后取一次：轮询会拉长每次点击的耗时，单步操作不需要等界面完全静默，
 * 只需要「有没有反应」这个粗信号。
 */
internal object VdScreenDelta {

    /** 分块边长：8×8 共 64 块，够分辨「整块区域换了」又不至于被一两个字的变化带偏。 */
    private const val GRID = 8

    /** 解码时把长边压到这一档（再大对分块平均没有意义，却要多占内存）。 */
    private const val DECODE_LONG_EDGE = 256

    /** 单块亮度差达到这个值（0..255）就算这一块换了。 */
    private const val BLOCK_DELTA = 10

    /** 全图平均亮度差达到这个值也算变了（整屏渐变、淡入淡出这类没有尖锐块差的变化）。 */
    private const val MEAN_DELTA = 2.0

    /** 一次判定结果：[changed] 为 null 表示没拿到前后帧（未知），不能当成「没变」。 */
    data class Receipt(val changed: Boolean?, val note: String)

    /** 从截图文件算亮度分块签名；文件不可读或解码失败返回 null（调用方按未知处理）。 */
    fun signature(file: File?): IntArray? {
        if (file == null || !file.exists() || file.length() <= 0) return null
        val bitmap = decodeSmall(file) ?: return null
        var scaled: Bitmap? = null
        return try {
            val grid = if (bitmap.width == GRID && bitmap.height == GRID) bitmap
            else Bitmap.createScaledBitmap(bitmap, GRID, GRID, true).also { scaled = it }
            val pixels = IntArray(GRID * GRID)
            grid.getPixels(pixels, 0, GRID, 0, 0, GRID, GRID)
            IntArray(pixels.size) { index ->
                val pixel = pixels[index]
                val r = (pixel shr 16) and 0xff
                val g = (pixel shr 8) and 0xff
                val b = pixel and 0xff
                (r * 299 + g * 587 + b * 114) / 1000
            }
        } catch (e: Exception) {
            null
        } finally {
            scaled?.recycle()
            bitmap.recycle()
        }
    }

    /** 前后签名对比，给出三态结果与一句人话说明。任一侧为空都算未知。 */
    fun receipt(before: IntArray?, after: IntArray?): Receipt {
        if (before == null || after == null || before.size != after.size) {
            return Receipt(
                changed = null,
                note = "没观测到前后帧，无法判断屏幕有没有变化——这是未知，不代表没点中",
            )
        }
        var sum = 0
        var maxDelta = 0
        for (index in before.indices) {
            val delta = abs(before[index] - after[index])
            sum += delta
            if (delta > maxDelta) maxDelta = delta
        }
        val changed = maxDelta >= BLOCK_DELTA || sum.toDouble() / before.size >= MEAN_DELTA
        return if (changed) {
            Receipt(true, "屏幕画面已经变了（对照动作后 500ms 取的后帧）")
        } else {
            Receipt(
                changed = false,
                note = "有前后帧、但画面没有变化：原地动作（点已选中的项、点空白处）本来就不会变；" +
                    "如果这一下本该有反应，说明没点中或点在无效区域",
            )
        }
    }

    /**
     * 降采样解码：先只读边界算 inSampleSize，再按采样率解码，避免把整屏大图原样读进内存。
     * 只用 RGB_565：分块平均亮度不需要 alpha 与 8bit 精度。
     */
    private fun decodeSmall(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }
}
