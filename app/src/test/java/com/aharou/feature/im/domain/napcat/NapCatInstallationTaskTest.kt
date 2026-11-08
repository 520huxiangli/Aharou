package com.aharou.feature.im.domain.napcat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NapCatInstallationTaskTest {
    // 不用 backgroundScope：测试框架会把后台协程的异常当未捕获上报，即使等待方已经接住。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `两个等待者只启动一次安装`() = runTest {
        val installation = NapCatInstallationTask(scope)
        val finish = CompletableDeferred<Unit>()
        var starts = 0
        val first = launch { installation.run(false) { starts++; finish.await() } }
        val second = launch { installation.run(false) { starts++; finish.await() } }
        runCurrent()
        assertEquals(1, starts)
        finish.complete(Unit)
        first.join()
        second.join()
        assertEquals(1, starts)
    }

    @Test
    fun `页面等待者取消不停止安装新页面可继续等待`() = runTest {
        val installation = NapCatInstallationTask(scope)
        val finish = CompletableDeferred<Unit>()
        var starts = 0
        var completed = false
        var cleaned = false
        val page = launch {
            installation.run(false) {
                starts++
                try { finish.await(); completed = true } finally { cleaned = true }
            }
        }
        runCurrent()
        page.cancelAndJoin()
        assertFalse(cleaned)
        val reopened = launch { installation.run(false) { starts++ } }
        runCurrent()
        assertEquals(1, starts)
        finish.complete(Unit)
        reopened.join()
        assertTrue(completed)
        assertTrue(cleaned)
    }

    @Test
    fun `显式取消会等待原任务清理后允许重试`() = runTest {
        val installation = NapCatInstallationTask(scope)
        val finish = CompletableDeferred<Unit>()
        var cleaned = false
        val page = launch {
            installation.run(false) { try { finish.await() } finally { cleaned = true } }
        }
        runCurrent()
        installation.cancel()
        page.join()
        assertTrue(cleaned)
        assertTrue(page.isCancelled)
        var restarted = false
        installation.run(false) { restarted = true }
        assertTrue(restarted)
    }

    @Test
    fun `安装失败传给等待者并允许下一次重试`() = runTest {
        val installation = NapCatInstallationTask(scope)
        val failure = async { runCatching { installation.run(false) { error("dependency failed") } }.exceptionOrNull() }
        assertEquals("dependency failed", failure.await()?.message)
        var restarted = false
        installation.run(false) { restarted = true }
        assertTrue(restarted)
    }

    @Test
    fun `安装中请求更新被拒绝且不取消原安装`() = runTest {
        val installation = NapCatInstallationTask(scope)
        val finish = CompletableDeferred<Unit>()
        var updated = false
        val installing = launch { installation.run(false) { finish.await() } }
        runCurrent()
        val error = runCatching { installation.run(true) { updated = true } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertFalse(updated)
        assertTrue(installing.isActive)
        finish.complete(Unit)
        installing.join()
    }

    @Test
    fun `取消空闲任务不会影响后续用户发起安装`() = runTest {
        val installation = NapCatInstallationTask(scope)
        installation.cancel()
        var runs = 0
        installation.run(false) { runs++ }
        installation.cancel()
        installation.run(true) { runs++ }
        assertEquals(2, runs)
    }
}
