package com.aharou.feature.agent.domain.mobileagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 归一化坐标 0..1000 → 像素的换算与越界拒绝（[MobileCoordinates]）。
 *
 * 重点不是换算公式，而是「越界必须拒绝、不许夹到边界」：模型把像素当归一化值填进来时，
 * 夹边界会变成默默点偏，用户看到的是「AI 点错地方」，排查时却发现不了。
 */
class MobileCoordinatesTest {

    @Test
    fun zero_maps_to_origin() {
        assertEquals(0, MobileCoordinates.toPixels(0.0, 1080))
        assertEquals(0, MobileCoordinates.toPixels(0.0, 1920))
    }

    @Test
    fun midpoint_maps_to_half_screen() {
        assertEquals(540, MobileCoordinates.toPixels(500.0, 1080))
        assertEquals(960, MobileCoordinates.toPixels(500.0, 1920))
    }

    @Test
    fun full_scale_maps_to_last_pixel_not_overflow() {
        // 1000 是「右/下边缘」，必须落在最后一格，不能算出 dimension（越界一个像素）
        assertEquals(1079, MobileCoordinates.toPixels(1000.0, 1080))
        assertEquals(1919, MobileCoordinates.toPixels(1000.0, 1920))
    }

    @Test
    fun out_of_range_is_rejected() {
        assertNull(MobileCoordinates.toPixels(-1.0, 1080))
        assertNull(MobileCoordinates.toPixels(-0.001, 1080))
        assertNull(MobileCoordinates.toPixels(1000.001, 1080))
        assertNull(MobileCoordinates.toPixels(1001.0, 1080))
    }

    @Test
    fun pixel_value_passed_by_mistake_is_rejected() {
        // 典型误用：模型把像素坐标直接填进来
        assertNull(MobileCoordinates.toPixels(1080.0, 1080))
        assertNull(MobileCoordinates.toPixels(1920.0, 1920))
    }

    @Test
    fun unknown_dimension_is_rejected() {
        assertNull(MobileCoordinates.toPixels(500.0, 0))
        assertNull(MobileCoordinates.toPixels(500.0, -1))
    }

    @Test
    fun rounding_is_closest_pixel() {
        // 1 个单位 ≈ 1.08px（1080 宽）
        assertEquals(1, MobileCoordinates.toPixels(1.0, 1080))
        assertEquals(2, MobileCoordinates.toPixels(2.0, 1080))
        // 999 个单位靠近右边缘但不越界
        assertEquals(1079, MobileCoordinates.toPixels(999.0, 1080))
    }
}
