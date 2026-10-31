package com.aharou.core.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 取消路径上的收尾：未取消时照常切到 IO 后台执行，已取消时改成阻塞执行。
 *
 * 已经处于取消中的协程里再做 `withContext` 会挂起一次，恢复点上 ART 的
 * AssertNoPendingException 会看到那个还没消化的取消异常，直接把进程 abort
 * （Abort message: No pending exception expected: JobCancellationException:
 * StandaloneCoroutine was cancelled）。debug 构建跑解释器必现，release 经过 R8
 * 优化后不经过那个检查点，所以只在测试包上看得到。
 *
 * 阻塞只在取消路径上发生一次，代价是调用线程停一下；正常路径行为不变。
 */
suspend fun runCancellationSafeTail(block: suspend () -> Unit) {
    if (currentCoroutineContext().isActive) {
        withContext(NonCancellable + Dispatchers.IO) { block() }
    } else {
        runBlocking(NonCancellable + Dispatchers.IO) { block() }
    }
}
