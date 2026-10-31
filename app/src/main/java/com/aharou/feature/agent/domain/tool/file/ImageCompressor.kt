package com.aharou.feature.agent.domain.tool.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * 图片压缩管线：按最长边降采样 + JPEG 逐档降质，产出可直接进请求的 base64。
 *
 * 两处共用同一套参数：`viewImage` 工具按 detail 档位挑边宽，聊天附件统一走 high 档。
 * 附件此前是原样读字节进 base64（实测 57KB 的 JPEG 在请求里就是 76K 字符），
 * 走这里能压到约 1/3 且肉眼与文字可读性无损。
 */
object ImageCompressor {
    const val LOW_MAX_EDGE = 512
    const val HIGH_MAX_EDGE = 1536
    const val LOW_TARGET_BYTES = 96 * 1024
    const val HIGH_TARGET_BYTES = 512 * 1024

    private val JPEG_QUALITIES = listOf(90, 86, 78, 70, 62)

    data class Bounds(val width: Int, val height: Int)

    data class Encoded(
        val base64Data: String,
        val width: Int,
        val height: Int,
        val encodedBytes: Long
    )

    /** 只读尺寸，不解码像素；拿不到尺寸（非图片/损坏）返回 null。 */
    fun decodeBounds(file: File): Bounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) Bounds(width, height) else null
    }

    /**
     * 压到最长边 [maxEdge] 内并编码成 JPEG base64。解码失败时抛 IllegalArgumentException，
     * 交给调用方决定是报错还是回退原图。
     */
    fun encodeToJpeg(file: File, maxEdge: Int, targetBytes: Int): Encoded {
        val bounds = decodeBounds(file) ?: throw IllegalArgumentException("无法识别图片格式: ${file.name}")
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.width, bounds.height, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("无法解码图片: ${file.name}")

        try {
            val scaled = scaleToMaxEdge(decoded, maxEdge)
            try {
                val bytes = compressJpeg(scaled, targetBytes)
                return Encoded(
                    base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP),
                    width = scaled.width,
                    height = scaled.height,
                    encodedBytes = bytes.size.toLong()
                )
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    fun rawBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, targetBytes: Int): ByteArray {
        var best = ByteArray(0)
        for (quality in JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            best = bytes
            if (bytes.size <= targetBytes) break
        }
        return best
    }
}
