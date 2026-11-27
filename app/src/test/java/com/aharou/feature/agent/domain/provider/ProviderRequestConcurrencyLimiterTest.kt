package com.aharou.feature.agent.domain.provider

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 供应商并发闸门：不限流直通、按供应商排队、排队/在途两条取消路径都归还名额、异常也归还。
 * 名额泄漏只会表现为「该供应商永久少一个并发位」，不会报错，所以每条归还路径都要有用例压着。
 */
class ProviderRequestConcurrencyLimiterTest {
    @Test
    fun zeroMeansUnlimited() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 0) {
                releaseFirst.await()
            }
        }
        limiter.withPermit("provider-a", maxConcurrentRequests = 0) {
            secondEntered.complete(Unit)
        }

        assertTrue(secondEntered.isCompleted)
        releaseFirst.complete(Unit)
        first.await()
    }

    @Test
    fun queuesRequestsPerProviderAndAllowsOtherProvidersToProceed() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val secondQueuePosition = CompletableDeferred<Int>()
        val admitted = mutableListOf<String>()

        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1) {
                releaseFirst.await()
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit(
                "provider-a",
                maxConcurrentRequests = 1,
                onQueued = { secondQueuePosition.complete(it) },
                onAdmitted = { admitted += "provider-a" }
            ) {
                secondEntered.complete(Unit)
            }
        }
        var otherProviderEntered = false
        limiter.withPermit("provider-b", maxConcurrentRequests = 1) {
            otherProviderEntered = true
        }

        assertFalse(secondEntered.isCompleted)
        assertEquals(1, secondQueuePosition.await())
        assertTrue(otherProviderEntered)

        releaseFirst.complete(Unit)
        first.await()
        second.await()
        assertTrue(secondEntered.isCompleted)
        assertEquals(listOf("provider-a"), admitted)
    }

    @Test
    fun cancellingQueuedRequestRemovesItFromTheQueue() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val releaseFirst = CompletableDeferred<Unit>()
        val queued = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1) { releaseFirst.await() }
        }
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1, onQueued = { queued.complete(Unit) }) { }
        }

        queued.await()
        cancelled.cancelAndJoin()
        releaseFirst.complete(Unit)
        first.await()

        var nextEntered = false
        limiter.withPermit("provider-a", maxConcurrentRequests = 1) { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun cancellingAdmittedRequestReleasesThePermit() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val admittedAndHeld = CompletableDeferred<Unit>()
        val holder = async(start = CoroutineStart.UNDISPATCHED) {
            limiter.withPermit("provider-a", maxConcurrentRequests = 1) {
                admittedAndHeld.complete(Unit)
                awaitCancellation()
            }
        }

        admittedAndHeld.await()
        holder.cancelAndJoin()

        var nextEntered = false
        limiter.withPermit("provider-a", maxConcurrentRequests = 1) { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun exceptionInsideBlockReleasesThePermit() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        val failed = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { limiter.withPermit("provider-a", maxConcurrentRequests = 1) { error("boom") } }
        }
        assertTrue(failed.await().isFailure)

        var nextEntered = false
        limiter.withPermit("provider-a", maxConcurrentRequests = 1) { nextEntered = true }
        assertTrue(nextEntered)
    }

    @Test
    fun raisingTheLimitTakesEffectOnceTheGateIsIdle() = runTest {
        val limiter = ProviderRequestConcurrencyLimiter()
        limiter.withPermit("provider-a", maxConcurrentRequests = 1) { }

        val releaseA = CompletableDeferred<Unit>()
        val releaseB = CompletableDeferred<Unit>()
        var entered = 0
        val running = listOf(releaseA, releaseB).map { release ->
            async(start = CoroutineStart.UNDISPATCHED) {
                limiter.withPermit("provider-a", maxConcurrentRequests = 2) {
                    entered++
                    release.await()
                }
            }
        }

        // 上一轮上限是 1，新一轮按 2 开：两个请求必须同时拿到名额，否则说明门没随配置换掉。
        assertEquals(2, entered)
        releaseA.complete(Unit)
        releaseB.complete(Unit)
        running.forEach { it.await() }
    }
}
