package com.aharou.core.util

import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 取消路径的收尾必须「跑得完、且不依赖外部推进调度器」。
 *
 * 2026-11-01 的事故：这里原先接收调用方的调度器，取消路径的 `runBlocking` 会把线程堵住等收尾，
 * 而测试传进来的是虚拟时间调度器（任务要外部推进才跑）—— 双方互相等待，CI 的「Run unit tests」
 * 整整一格卡到平台超时（`hangingSummaryTimesOut…` 之后所有用例都不再有输出）。
 *
 * 现在收尾不换调度器，两个坑一起绕开。下面的用例都跑在虚拟时间调度器上：一旦收尾又按调度器排队
 * 再等它完成，`advanceUntilIdle()` 会直接挂死（而不是断言失败），CI 上就是那次事故的重演。
 */
class CancellationSafeTailTest {

    @Test
    fun activePathRunsBeforeReturning() = runTest {
        var ran = false
        runCancellationSafeTail { ran = true }
        // 没被取消时语义不变：block 跑完才返回，调用方不用自己推进调度器
        assertTrue(ran)
    }

    /**
     * 已取消的协程里调收尾：`runCancellationSafeTail` 返回时 block 必须已经跑完，
     * 而不是被排进调度器队列等别人推进。
     */
    @Test
    fun cancelledPathRunsInlineWithoutScheduler() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var ranWhenReturned = false
        var ran = false
        launch(dispatcher) {
            cancel()
            runCancellationSafeTail { ran = true }
            // 关键断言：收尾是就地跑完的，这里不需要任何 advance
            ranWhenReturned = ran
        }
        advanceUntilIdle()
        assertTrue(ranWhenReturned)
        assertTrue(ran)
    }

    @Test
    fun cancelledPathPropagatesBlockFailureToCaller() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        var threw = false
        launch(dispatcher) {
            cancel()
            // 取消路径不能把异常吞掉：调用方的 finally 里本来就有自己的兜底
            try {
                runCancellationSafeTail { error("boom") }
            } catch (_: IllegalStateException) {
                threw = true
            }
        }
        advanceUntilIdle()
        assertTrue(threw)
    }
}
