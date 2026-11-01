package com.aharou.core.util

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * 取消路径上的收尾：未取消时就地挂起执行，已取消时就地阻塞执行。两条路径都**不换调度器**。
 *
 * 为什么已取消时不能 `withContext`：取消中的协程里再做一次 `withContext` 会挂起并在恢复点让
 * ART 的 AssertNoPendingException 看到那个还没消化的取消异常，直接把进程 abort
 * （Abort message: No pending exception expected: JobCancellationException:
 * StandaloneCoroutine was cancelled）。debug 构建跑解释器必现，release 经过 R8 优化后不经过那个
 * 检查点，所以只在测试包上看得到。于是取消路径退回 `runBlocking`：同步跑完再返回，
 * 「取消时也要把话说清楚」（回复快照、调用统计）这层保证不受影响。
 *
 * 为什么**不接收调用方的调度器**（本次事故的根因，2026-11-01）：
 * 曾经让调用方把自己的后台调度器传进来，测试里传的是虚拟时间调度器
 * （`StandardTestDispatcher`）。取消路径的 `runBlocking` 会把唯一线程堵住等收尾跑完，而虚拟时间
 * 调度器的任务只有靠外部推进才会跑 —— 谁也没法推进，双向等待，CI 的「Run unit tests」整整一格
 * 卡到平台超时（`hangingSummaryTimesOut…` 之后所有用例都不再有输出）。
 * 换成「独立作用域异步 launch」能解死锁，但会把「取消时必须落库」变成不确定（进程被杀就丢），
 * 得不偿失。所以两个坑一起绕开：**不换调度器**，收尾就在调用方线程上跑。
 *
 * 不换调度器在真实环境同样成立：收尾里只有 suspend 的 DAO / 持久化调用（Room 自己切执行器）
 * 与少量 FileLogger 写盘，没有在主线程上做重活。
 *
 * [block] 内部抛出的异常不会被吞：未取消时照旧向上抛（调用方的 finally 自己兜）；取消路径下
 * 也照旧抛给 `runBlocking`，由调用方原有的 try/catch 处理。
 */
suspend fun runCancellationSafeTail(block: suspend () -> Unit) {
    if (currentCoroutineContext().isActive) {
        withContext(NonCancellable) { block() }
    } else {
        // 已取消：就地阻塞。runBlocking 自带事件循环，block 里的挂起点（Room 的 suspend DAO 等）
        // 由它自己推进，不依赖任何外部调度器。
        runBlocking(NonCancellable) { block() }
    }
}
