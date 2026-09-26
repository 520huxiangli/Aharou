package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 打字机速率与收尾行为。
 *
 * 重点是两条曾经踩过的坑：
 * 1. 显示速率被死封上限，模型吐得快时滞后只增不减（旧实现稳态滞后约 1.8×上游速率，
 *    动辄上百字），上游一结束整段一次跳出——所以这里断言滞后在常见上游速率下有界；
 * 2. 上游结束不能一帧补全，剩余文字要在一个常数级时长内匀速打完，不能拖。
 */
class TypewriterRateTest {

    private companion object {
        /** 模拟用的帧间隔（秒）：60fps。 */
        const val FRAME_SEC = 1f / 60f

        /** 常见上游吐字速率（字符/秒）：慢速模型到爆发式吐字。 */
        val TYPICAL_UPSTREAM_RATES = listOf(20f, 50f, 100f, 200f)
    }

    /**
     * 以「恒速上游 + 逐帧推进显示」模拟滞后收敛，返回 10 秒后的稳态滞后（字符）。
     * 与 UI 里的实现同构：上游按帧累积，显示按 [typewriterRate] 推进并封顶在上游进度。
     */
    private fun steadyStateLag(upstreamCps: Float): Float {
        var arrived = 0f
        var shown = 0f
        repeat(600) {
            arrived += upstreamCps * FRAME_SEC
            val lag = arrived - shown
            if (lag > 0f) {
                val rate = typewriterRate(lag = lag, arrivalRate = upstreamCps)
                shown = minOf(arrived, shown + rate * FRAME_SEC)
            }
        }
        return arrived - shown
    }

    /** 按 [typewriterDrainRate] 匀速打完 [lag] 个字符需要的帧数。 */
    private fun drainFrames(lag: Float): Int {
        val rate = typewriterDrainRate(lag)
        var remaining = lag
        var frames = 0
        while (remaining > 0f && frames < 10_000) {
            remaining -= rate * FRAME_SEC
            frames++
        }
        return frames
    }

    @Test
    fun noLag_zeroRate() {
        assertEquals(0f, typewriterRate(lag = 0f, arrivalRate = 100f), 0.001f)
        assertEquals(0f, typewriterRate(lag = -5f, arrivalRate = 100f), 0.001f)
    }

    @Test
    fun followsUpstreamRate_soLagCannotGrowUnbounded() {
        // 滞后很小、上游很快时也必须几乎跟上：跟随项 = 上游速率 × 0.95
        val rate = typewriterRate(lag = 1f, arrivalRate = 200f)
        assertTrue("rate=$rate 应不低于上游速率的 95%", rate >= 200f * 0.95f)
    }

    @Test
    fun lagBeyondTarget_breaksNormalCeiling() {
        // 滞后超标后允许突破常规上限，这是滞后有界的关键
        val rate = typewriterRate(lag = 40f, arrivalRate = 0f)
        assertTrue("rate=$rate 应突破常规上限", rate > 220f)
        assertTrue("rate=$rate 不应超过应急上限", rate <= 1600f)
    }

    @Test
    fun lagWithinTarget_keepsTypingFeel() {
        // 滞后很小时不该飙速：下限保证「看得见在打字」，上限保证不被拉爆
        val rate = typewriterRate(lag = 5f, arrivalRate = 0f)
        assertTrue("rate=$rate 不应超过常规上限", rate <= 220f)
        assertTrue("rate=$rate 不应低于最低速率", rate >= 30f)
    }

    @Test
    fun lagStaysSmall_atTypicalUpstreamRates() {
        TYPICAL_UPSTREAM_RATES.forEach { upstream ->
            val lag = steadyStateLag(upstream)
            // 旧实现稳态滞后约 1.8×上游（100 字符/秒 → 约 180 字），这里必须是个位数到十几字
            assertTrue("上游 ${upstream} 字符/秒时稳态滞后 $lag 字，过大", lag <= 14f)
        }
    }

    @Test
    fun lagStaysBounded_atBurstUpstreamRate() {
        // 爆发式吐字（如某些渠道一次攒一大段）：滞后可以大一些，但仍必须有界
        val lag = steadyStateLag(1000f)
        assertTrue("爆发式吐字时稳态滞后 $lag 字，应有界", lag <= 80f)
    }

    @Test
    fun drainFinishesShortLagWithinTargetWindow() {
        // 常见剩余量（十几字到几十字）应在目标时长（0.2 秒）附近打完，不能拖成一眼可见的停顿
        listOf(1f, 5f, 12f, 30f, 60f).forEach { lag ->
            val seconds = drainFrames(lag) * FRAME_SEC
            assertTrue("剩余 $lag 字收了 $seconds 秒，超出目标窗口", seconds <= 0.2f + FRAME_SEC)
        }
    }

    @Test
    fun drainNeverStalls_andHonoursMinRate() {
        // 剩余极少时也不能低于最低速率（否则会卡在半截等下一帧的取整）
        assertEquals(30f, typewriterDrainRate(0.01f), 0.001f)
        // 剩余很多时也不会一闪而过
        assertEquals(400f, typewriterDrainRate(10_000f), 0.001f)
        assertTrue(drainFrames(1f) in 1..3)
    }

    @Test
    fun drainRateDegradesGracefully_withHugeBacklog() {
        // 极端情况（切页回来发现积压了几百字）也要能打完，且不慢于最低速率
        val seconds = drainFrames(240f) * FRAME_SEC
        assertTrue("剩余 240 字收了 $seconds 秒", seconds <= 0.62f)
    }
}
