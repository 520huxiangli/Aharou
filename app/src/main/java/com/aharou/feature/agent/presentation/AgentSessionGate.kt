package com.aharou.feature.agent.presentation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 等同一会话前一个任务/停止占位结束的上限。
 *
 * 停止收尾若卡在不可取消的段里（落库的 NonCancellable、压缩收尾、不响应取消的阻塞调用），
 * 无上限的 await/join 会把之后每条消息永远排在一个不会完成的 Deferred 后面：界面什么都点得动，
 * 发出去的话却一直不动，只能杀进程重启。宁可与旧任务短暂并行，也不能让会话永久卡死。
 */
internal const val SESSION_GATE_TIMEOUT_MS = 10_000L

/** 等前一个任务与停止占位走完；两者各有上限，超时即视为已结束。
 *
 * [softStopped] 为真时不等待：软停后用户要能立刻插话，旧任务靠纪元比对自己退出，
 * 不能把新消息压在它后面。
 */
internal suspend fun awaitPreviousSessionWork(
    stopping: CompletableDeferred<Unit>?,
    previous: Job?,
    current: Job,
    softStopped: Boolean = false,
    timeoutMs: Long = SESSION_GATE_TIMEOUT_MS,
) {
    if (softStopped) return
    if (stopping != null) withTimeoutOrNull(timeoutMs) { stopping.await() }
    if (previous == null || previous === current || previous.isCompleted) return
    withTimeoutOrNull(timeoutMs) { previous.join() }
}
