package com.aharou.feature.agent.domain.tool.vdisplay

import android.os.SystemClock

/**
 * 「同一个位置反复戳」守卫。
 *
 * 为什么按位置分桶而不是按参数判重：模型陷进死循环时每次坐标都会抖一两个像素，参数级判重
 * 永远对不上，于是看起来像在换招、其实一直点同一处。把屏幕按 [BUCKET_SIZE] 像素切格子，
 * 同一格子里的点击在 [WINDOW_MS] 窗口内达到上限就拒绝，才认得出「还是那一个点」。
 *
 * 这不是调用次数配额（不做全局上限）：换个位置、按文字点（target）、放大看清再点都不受限，
 * 只拦原地重复。swipe 与 target 文字点击由调用方豁免，不进这里。
 */
internal class VdTapGuard(
    private val bucketSize: Int = BUCKET_SIZE,
    private val windowMs: Long = WINDOW_MS,
    private val maxTapsPerBucket: Int = MAX_TAPS_PER_BUCKET,
) {
    /** 桶键 → 该桶内仍在窗口里的点击时刻（有序，队首最旧）。 */
    private val taps = HashMap<Long, ArrayDeque<Long>>()

    /**
     * 记一次点击并回答能不能执行：窗口内该格子已经有 [maxTapsPerBucket] - 1 次点击时返回 false。
     *
     * 被拒绝的这次不记账：否则计数只增不减，用户等多久都出不来；不记账则窗口滑过后自然放行。
     */
    fun allow(x: Int, y: Int, now: Long = SystemClock.elapsedRealtime()): Boolean {
        purge(now)
        val list = taps.getOrPut(bucketKey(x, y)) { ArrayDeque() }
        if (list.size >= maxTapsPerBucket - 1) return false
        list.addLast(now)
        return true
    }

    /** 换屏（start / stop / launch）时清空：同一坐标在新界面上是另一个元素，旧账不能算到它头上。 */
    fun reset() = taps.clear()

    private fun purge(now: Long) {
        val iterator = taps.entries.iterator()
        while (iterator.hasNext()) {
            val list = iterator.next().value
            while (list.isNotEmpty() && now - list.first() >= windowMs) list.removeFirst()
            if (list.isEmpty()) iterator.remove()
        }
    }

    private fun bucketKey(x: Int, y: Int): Long =
        (Math.floorDiv(x, bucketSize).toLong() shl 32) or
            (Math.floorDiv(y, bucketSize).toLong() and 0xffffffffL)

    companion object {
        /** 40 像素一格：比手指精度粗一档，又不至于把相邻两个按钮并成一个。 */
        const val BUCKET_SIZE = 40

        /** 300 秒窗口：够长到跨过一次慢加载，又不至于把几分钟前的正常操作算进来。 */
        const val WINDOW_MS = 300_000L

        /** 同一格子第 7 次点击起拒绝。 */
        const val MAX_TAPS_PER_BUCKET = 7

        /** 拒绝时给模型的换策略提示。 */
        const val REJECT_MESSAGE =
            "同一个位置已经点了很多次，换个真正不同的办法：重新读控件、用 target 按文字点、或用 zoom 看清后点；不行就问用户"
    }
}
