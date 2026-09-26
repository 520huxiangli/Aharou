package com.aharou.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片查看器的缩放数学。都是纯函数，不碰 android.*，无需 Robolectric。
 */
class ImageViewerZoomTest {

    private val eps = 0.001f

    @Test
    fun fitInside_wideImage_limitedByWidth() {
        val fitted = fitInside(IntSize(4000, 2000), IntSize(1000, 1000))
        assertEquals(1000f, fitted.width, eps)
        assertEquals(500f, fitted.height, eps)
    }

    @Test
    fun fitInside_tallImage_limitedByHeight() {
        val fitted = fitInside(IntSize(2000, 4000), IntSize(1000, 1000))
        assertEquals(500f, fitted.width, eps)
        assertEquals(1000f, fitted.height, eps)
    }

    @Test
    fun fitInside_zeroSize_isZero() {
        assertEquals(Size.Zero, fitInside(IntSize(0, 0), IntSize(1000, 1000)))
        assertEquals(Size.Zero, fitInside(IntSize(100, 100), IntSize.Zero))
    }

    @Test
    fun maxPan_notZoomed_isZero() {
        // 未放大时图钉死在正中，一点位移都不给，否则松手会漂
        assertEquals(0f, maxPan(contentPx = 1000f, containerPx = 1000f, scale = 1f), eps)
        // 短边方向本来就没铺满容器，同样不给位移
        assertEquals(0f, maxPan(contentPx = 500f, containerPx = 1000f, scale = 1f), eps)
    }

    @Test
    fun maxPan_zoomed_isHalfOverflow() {
        assertEquals(500f, maxPan(contentPx = 1000f, containerPx = 1000f, scale = 2f), eps)
        assertEquals(250f, maxPan(contentPx = 500f, containerPx = 1000f, scale = 3f), eps)
    }

    @Test
    fun clampPan_outOfBounds_isClamped() {
        val clamped = clampPan(
            offset = Offset(9999f, -9999f),
            content = Size(1000f, 1000f),
            container = IntSize(1000, 1000),
            scale = 2f
        )
        assertEquals(500f, clamped.x, eps)
        assertEquals(-500f, clamped.y, eps)
    }

    @Test
    fun clampPan_notZoomed_isAlwaysZero() {
        val clamped = clampPan(
            offset = Offset(120f, -80f),
            content = Size(1000f, 1000f),
            container = IntSize(1000, 1000),
            scale = 1f
        )
        // 逐轴比较而不是直接和 Offset.Zero 比：夹到零边界时负向那一轴会得到 -0.0f，
        // 而 Offset 是打包两个 float 的 value class，-0.0f 与 0.0f 的位模式不同、equals 不成立。
        assertEquals(0f, clamped.x, eps)
        assertEquals(0f, clamped.y, eps)
    }

    @Test
    fun zoomAround_pureTranslation_addsPan() {
        val moved = zoomAround(
            centroid = Offset(10f, 20f),
            containerCenter = Offset(500f, 500f),
            oldScale = 2f,
            newScale = 2f,
            oldOffset = Offset(30f, 40f),
            pan = Offset(5f, 6f)
        )
        assertEquals(35f, moved.x, eps)
        assertEquals(46f, moved.y, eps)
    }

    @Test
    fun zoomAround_centerAnchor_scalesOffset() {
        val moved = zoomAround(
            centroid = Offset(500f, 500f),
            containerCenter = Offset(500f, 500f),
            oldScale = 1f,
            newScale = 2f,
            oldOffset = Offset(10f, 20f),
            pan = Offset.Zero
        )
        assertEquals(20f, moved.x, eps)
        assertEquals(40f, moved.y, eps)
    }

    @Test
    fun zoomAround_cornerAnchor_keepsPixelUnderFinger() {
        val centroid = Offset(100f, 200f)
        val center = Offset(500f, 500f)
        val oldScale = 1.5f
        val newScale = 3f
        val oldOffset = Offset(40f, -30f)

        val moved = zoomAround(centroid, center, oldScale, newScale, oldOffset, Offset.Zero)

        // 手势前落在 centroid 处的内容点，手势后应当还在原处
        val v = centroid - center
        val point = (v - oldOffset) / oldScale
        val after = point * newScale + moved
        assertEquals(v.x, after.x, 0.01f)
        assertEquals(v.y, after.y, 0.01f)
    }

    @Test
    fun zoomAround_zeroOldScale_returnsOldOffset() {
        val oldOffset = Offset(7f, 9f)
        val moved = zoomAround(Offset(1f, 2f), Offset.Zero, 0f, 2f, oldOffset, Offset(3f, 4f))
        assertEquals(oldOffset, moved)
    }

    @Test
    fun inSampleSize_longScreenshot_isDownsampled() {
        // 回归用例：判定条件写成 && 时约束的是短边，4000×300 会整个逃过采样、按全尺寸解码
        assertTrue(calculateInSampleSize(4000, 300, 1024) >= 2)
    }

    @Test
    fun inSampleSize_normalImage_fitsUnderMaxEdge() {
        val sample = calculateInSampleSize(2048, 1536, 1024)
        assertEquals(2, sample)
        assertTrue(2048 / sample <= 1024 && 1536 / sample <= 1024)
    }

    @Test
    fun inSampleSize_smallImage_isOne() {
        assertEquals(1, calculateInSampleSize(800, 600, 1024))
    }

    @Test
    fun inSampleSize_invalidBounds_isOne() {
        assertEquals(1, calculateInSampleSize(0, 0, 1024))
        assertEquals(1, calculateInSampleSize(100, 100, 0))
    }

    @Test
    fun inSampleSize_maxPixelsCap_applies() {
        // 最大边没超限，但总像素 24MP，靠像素兜底那一层挡住
        val sample = calculateInSampleSize(6000, 4000, maxEdge = 8192, maxPixels = 4_000_000L)
        assertTrue(sample > 1)
        assertTrue((6000L / sample) * (4000L / sample) <= 4_000_000L)
    }
}
