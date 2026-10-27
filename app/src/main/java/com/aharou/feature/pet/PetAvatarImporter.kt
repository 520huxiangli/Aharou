package com.aharou.feature.pet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.aharou.core.util.FileLogger
import java.io.File
import java.io.IOException
import kotlin.math.min

/**
 * 用户在导入预览框里调好的位置与缩放。
 *
 * 坐标是预览框的像素：`scale` 相对于「图片刚好塞进预览框」的初始大小，`offset` 是图片中心相对
 * 预览框中心的位移。落盘时按同一套比例换算到画布坐标，用户看到什么就存成什么。
 */
data class CropSpec(
    val viewportPx: Float,
    val scale: Float,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

/**
 * 把一张图片变成能直接用的桌宠立绘。
 *
 * 渲染层（[PetOverlay]）是按 bitmap 直接画的，存「变换参数」还得改渲染层，所以这里把用户调好的
 * 位置/缩放**烘焙进图片**：解码 → 画到正方形画布 → 裁掉四周多余的透明边 → 落盘成
 * `filesDir/pet/<id>/front.png`。
 *
 * 只做领域逻辑，不碰 Compose：设置页与以后的 AI 工具（生成图后直接导入）共用这一个入口。
 * 缺的姿态不用管，[PetOverlay] 会自动用 front 顶上。
 */
class PetAvatarImporter(private val context: Context) {

    /**
     * 导入一张立绘，返回新角色的 id。
     *
     * @param name 角色显示名（同名不会互相覆盖，[PetUserRoles.create] 会加序号）
     * @param crop 预览里调好的位置/缩放；传 null 表示自动归一化（居中、按长边塞满画布），
     *             AI 生成的方图走这条。
     */
    fun importImage(bytes: ByteArray, name: String, crop: CropSpec? = null): Result<String> =
        runCatching {
            val source = decode(bytes) ?: throw IOException("decode-failed")
            var baked: Bitmap? = null
            var created: String? = null
            try {
                baked = bake(source, crop)
                val id = PetUserRoles.create(context, name)
                created = id
                val target = File(PetUserRoles.dir(context, id), FRONT_NAME)
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    if (!baked.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        throw IOException("png-encode-failed")
                    }
                }
                id
            } catch (e: Throwable) {
                created?.let { PetUserRoles.discard(context, it) }
                throw e
            } finally {
                baked?.recycle()
                source.recycle()
            }
        }.onFailure { FileLogger.w(TAG, "导入立绘失败：$name", it) }

    /** 删掉一个用户角色；内置角色（assets 里带着的）拒绝。 */
    fun remove(id: String): Boolean =
        !PetUserRoles.isBuiltin(context, id) && PetUserRoles.delete(context, id)

    /** 解码成 ARGB_8888（带透明通道），大图按 2 的幂降采样，别把几千万像素的图整张读进内存。 */
    fun decode(bytes: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (bounds.outWidth / sample > DECODE_MAX_PX || bounds.outHeight / sample > DECODE_MAX_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    private fun bake(source: Bitmap, crop: CropSpec?): Bitmap {
        val canvas = Bitmap.createBitmap(CANVAS_PX, CANVAS_PX, Bitmap.Config.ARGB_8888)
        val viewport = crop?.viewportPx?.takeIf { it > 0f } ?: CANVAS_PX.toFloat()
        val scale = (crop?.scale ?: 1f).coerceIn(MIN_SCALE, MAX_SCALE)
        // 预览框里「刚好塞满」的比例，再乘用户缩放，最后按 画布/预览框 换算到画布坐标
        val fit = min(viewport / source.width, viewport / source.height)
        val factor = fit * scale * (CANVAS_PX / viewport)
        val centerX = CANVAS_PX / 2f + (crop?.offsetX ?: 0f) * (CANVAS_PX / viewport)
        val centerY = CANVAS_PX / 2f + (crop?.offsetY ?: 0f) * (CANVAS_PX / viewport)
        val matrix = Matrix().apply {
            setTranslate(-source.width / 2f, -source.height / 2f)
            postScale(factor, factor)
            postTranslate(centerX, centerY)
        }
        Canvas(canvas).drawBitmap(
            source,
            matrix,
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return trim(canvas)
    }

    /**
     * 裁掉四周完全透明的区域：画布是正方形的，不裁的话桌宠按「图高」缩到 190dp 时会缩水一圈。
     * 整张全透明说明用户在框里把图拖出去了，直接当失败。
     */
    private fun trim(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        var left = width
        var top = height
        var right = -1
        var bottom = -1
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                if (pixels[row + x] ushr 24 > ALPHA_THRESHOLD) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                }
            }
        }
        if (right < left || bottom < top) {
            source.recycle()
            throw IOException("empty-image")
        }
        val boxWidth = right - left + 1
        val boxHeight = bottom - top + 1
        if (boxWidth < MIN_SIDE_PX || boxHeight < MIN_SIDE_PX) {
            source.recycle()
            throw IOException("image-too-small")
        }
        if (boxWidth == width && boxHeight == height) return source
        val trimmed = Bitmap.createBitmap(source, left, top, boxWidth, boxHeight)
        source.recycle()
        return trimmed
    }

    companion object {
        /** 落盘画布边长：桌宠最大档 190dp，3 倍屏 ≈ 570px。 */
        const val CANVAS_PX = 570

        private const val TAG = "PetAvatarImporter"
        private const val FRONT_NAME = "front.png"
        private const val DECODE_MAX_PX = 2048
        private const val MIN_SCALE = 0.2f
        private const val MAX_SCALE = 5f
        private const val ALPHA_THRESHOLD = 8
        private const val MIN_SIDE_PX = 16
    }
}
