package com.aharou.feature.agent.domain.tool.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Base64OutputStream
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

    /** 流式读盘的块大小：够大以摊销系统调用，够小以不抬高峰值内存。 */
    private const val STREAM_CHUNK_BYTES = 64 * 1024

    /** 输出缓冲预分配上限：源文件异常巨大时不按它的长度申请，免得还没读就先 OOM。 */
    private const val MAX_PREALLOC_BYTES = 16 * 1024 * 1024

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

    /**
     * 分块流式读文件并编码成 NO_WRAP base64。
     *
     * 整份 `readBytes()` 再编码会让大图同时占着「原字节 + base64 字节 + 结果串」三份内存，
     * 相机直出十几 MB 的照片在低端机上直接顶爆。改成按块喂编码器后峰值只剩一个块缓冲，
     * 输出与 `Base64.encodeToString(bytes, NO_WRAP)` 逐字符一致（同字母表、无换行、末尾无补换行）。
     */
    fun rawBase64(file: File): String {
        val out = ByteArrayOutputStream(initialCapacity(file.length()))
        Base64OutputStream(out, Base64.NO_WRAP).use { encoder ->
            file.inputStream().use { input ->
                val buffer = ByteArray(STREAM_CHUNK_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    encoder.write(buffer, 0, read)
                }
            }
        }
        // base64 是纯 ASCII，按 US_ASCII 建串与逐字节比较等价，且不必再过一遍校验
        return String(out.toByteArray(), Charsets.US_ASCII)
    }

    /**
     * 输出缓冲的预分配长度：长度可算准（n/3*4 + 填充）时一次到位，省掉扩容时的整份拷贝。
     */
    private fun initialCapacity(byteCount: Long): Int {
        if (byteCount <= 0L) return 0
        val exact = byteCount / 3 * 4 + 4
        return exact.coerceAtMost(MAX_PREALLOC_BYTES.toLong()).toInt()
    }

    /**
     * 进请求用的 base64 与 mime：最长边超过 [HIGH_MAX_EDGE] 的大图优先走压缩管线（采样解码 + 逐档降质），
     * 只有「不需要缩放」时才按原格式流式直传（小图重编码只会更大，格式由调用方给的 [fallbackMime] 决定）；
     * 压不动（格式怪、解码失败）时也回退流式原字节，宁大不丢。两条路都不把整份原字节一起读进内存。
     *
     * 选图时与历史回放时共用同一套参数，保证同一张图在“刚发出去”与“下一轮重发历史”
     * 两种情形下发的是同一份字节。
     */
    fun encodeForRequest(file: File, fallbackMime: String): Pair<String, String> {
        val longEdge = decodeBounds(file)?.let { maxOf(it.width, it.height) } ?: 0
        if (longEdge > HIGH_MAX_EDGE) {
            runCatching {
                encodeToJpeg(file, HIGH_MAX_EDGE, HIGH_TARGET_BYTES)
            }.getOrNull()?.let { return it.base64Data to "image/jpeg" }
        }
        return rawBase64(file) to fallbackMime
    }

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
