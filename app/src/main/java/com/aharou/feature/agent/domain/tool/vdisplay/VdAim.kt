package com.aharou.feature.agent.domain.tool.vdisplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `zoom`：把屏幕上的一块区域裁出来放大，并在图上画准星。
 *
 * 为什么要它：对着整屏缩略图估坐标，按钮越小偏差越大；模型卡在原地反复点时，多半是
 * 「看不清 -> 估偏 -> 猜了个近似位置」。把区域放大 2~4 倍能显著降低这一环的误差。
 *
 * 归一化坐标沿用手机操作子代理那套 0..1000（相对整屏宽高），像素坐标则是 tap/swipe 用的那套。
 */
internal object VdAim {

    /** 归一化坐标的上界：与多步任务子代理、tap_target 的提示词保持一致。 */
    private const val NORM_MAX = 1000

    /** 区域边界，全部是屏幕像素（与 tap 同一坐标系）。 */
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** 参数解析结果：给了合法区域 / 给了但不合法（带原因）/ 根本没给。 */
    sealed interface Resolve {
        data class Ok(val region: Region) : Resolve
        data class Fail(val message: String) : Resolve
        data object None : Resolve
    }

    /**
     * 解析区域参数：优先 `x/y/w/h`（像素），其次 `nx/ny/nw/nh`（0..1000 归一化）。
     *
     * 一组四个必须齐全：只给一半时猜不出另一半是什么，直接报错比按默认值点歪强。
     */
    fun resolve(args: Map<String, JsonElement>, screenW: Int, screenH: Int): Resolve {
        if (screenW <= 0 || screenH <= 0) return Resolve.Fail("当前拿不到屏幕尺寸，无法裁区域")
        val px = listOf("x", "y", "w", "h").map { args[it]?.jsonPrimitive?.intOrNull }
        val nm = listOf("nx", "ny", "nw", "nh").map { args[it]?.jsonPrimitive?.intOrNull }
        val pixelGiven = px.any { it != null }
        val normGiven = nm.any { it != null }
        if (!pixelGiven && !normGiven) return Resolve.None
        if (pixelGiven && px.all { it != null }) {
            return validate(px[0]!!, px[1]!!, px[0]!! + px[2]!!, px[1]!! + px[3]!!, screenW, screenH)
        }
        if (normGiven && nm.all { it != null }) {
            val left = nm[0]!! * screenW / NORM_MAX
            val top = nm[1]!! * screenH / NORM_MAX
            val right = (nm[0]!! + nm[2]!!) * screenW / NORM_MAX
            val bottom = (nm[1]!! + nm[3]!!) * screenH / NORM_MAX
            return validate(left, top, right, bottom, screenW, screenH)
        }
        return Resolve.Fail(
            "zoom 需要一组完整的区域：x/y/w/h（像素）或 nx/ny/nw/nh（0-$NORM_MAX 归一化），四种要么都给要么都不给",
        )
    }

    private fun validate(left: Int, top: Int, right: Int, bottom: Int, screenW: Int, screenH: Int): Resolve {
        if (right <= left || bottom <= top) return Resolve.Fail("zoom 区域为空（宽高必须大于 0）")
        val l = left.coerceIn(0, screenW - 1)
        val t = top.coerceIn(0, screenH - 1)
        val r = right.coerceIn(l + 1, screenW)
        val b = bottom.coerceIn(t + 1, screenH)
        return Resolve.Ok(Region(l, t, r, b))
    }

    /** 裁剪放大并画准星/边框后的图与它的尺寸。 */
    data class Zoomed(val image: Bitmap, val width: Int, val height: Int)

    /**
     * 裁出 [region] 并缩放到长边不超过 [maxEdge]；[scaleCap] 是放大倍数上限（不传则只受 maxEdge 约束）。
     * 小区域会被放大、大区域会被缩小，Aspect 比例始终不变。
     *
     * 只画边框与中心准星，不叠整屏网格：放大图的用途是看清细节，网格线只会增加噪声。
     */
    fun render(src: Bitmap, region: Region, scaleCap: Int?, maxEdge: Int): Zoomed {
        val regionW = region.width.coerceAtLeast(1)
        val regionH = region.height.coerceAtLeast(1)
        val longSide = maxOf(regionW, regionH)
        val targetLong = if (scaleCap != null && scaleCap > 0) {
            minOf(maxEdge, longSide * scaleCap)
        } else {
            maxEdge
        }
        val factor = targetLong.toFloat() / longSide
        val outW = (regionW * factor).toInt().coerceIn(1, maxEdge)
        val outH = (regionH * factor).toInt().coerceIn(1, maxEdge)
        val cropped = Bitmap.createBitmap(src, region.left, region.top, regionW, regionH)
        val scaled = if (outW == regionW && outH == regionH) cropped
        else Bitmap.createScaledBitmap(cropped, outW, outH, true)
        if (scaled !== cropped) cropped.recycle()
        val canvasBitmap = if (scaled.isMutable) scaled else {
            val copy = scaled.copy(Bitmap.Config.ARGB_8888, true)
                ?: error("放大图无法绘制准星（内存不足）")
            scaled.recycle()
            copy
        }
        drawAimMarks(canvasBitmap)
        return Zoomed(canvasBitmap, canvasBitmap.width, canvasBitmap.height)
    }

    /** 边框 + 中心十字：黑粗线打底、白细线压中，深浅背景上都看得见。 */
    private fun drawAimMarks(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val outer = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 6f
            color = 0xCC000000.toInt()
        }
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.WHITE
        }
        val inset = 3f
        canvas.drawRect(inset, inset, w - inset, h - inset, outer)
        canvas.drawRect(inset, inset, w - inset, h - inset, inner)

        val cx = w / 2f
        val cy = h / 2f
        val armX = w * 0.12f
        val armY = h * 0.12f
        for (paint in listOf(outer, inner)) {
            canvas.drawLine(cx - armX, cy, cx + armX, cy, paint)
            canvas.drawLine(cx, cy - armY, cx, cy + armY, paint)
        }
    }
}
