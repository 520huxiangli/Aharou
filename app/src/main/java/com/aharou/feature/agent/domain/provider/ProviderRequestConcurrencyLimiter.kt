package com.aharou.feature.agent.domain.provider

import com.aharou.feature.settings.domain.model.DEFAULT_PROVIDER_MAX_CONCURRENT_REQUESTS
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.CompletableDeferred

/**
 * 按供应商维度的并发闸门：同一 providerId 同时在途的请求不超过配置上限，超出部分按先来先服务排队。
 *
 * 用「按 providerId 建门 + 门内计数」而不是全局信号量：限流是供应商侧的配额（中转站常按 Key 限并发），
 * 一家排队不该拖住其它家。门的 limit 允许随配置变化：没有在途请求时直接换一个新门，
 * 这样用户改完设置不必重启应用就生效。
 *
 * 名额一定在 finally 里归还：取消（用户打断、首字节超时）与异常都走同一条路径，
 * 漏还一档就会让该供应商永久少一个并发位。
 */
@Singleton
class ProviderRequestConcurrencyLimiter @Inject constructor() {
    private val lock = Any()
    private val gates = mutableMapOf<String, Gate>()

    /**
     * 取得 [providerId] 的一个并发名额后执行 [block]。
     *
     * [onQueued] 在需要排队时先回调，参数是前方仍在途/排队的请求数，供 UI 显示「前面还有 N 个」；
     * [onAdmitted] 在真正轮到本次请求时回调（未排队则不会触发）。
     * [providerId] 为空或 [maxConcurrentRequests] ≤ 0 表示不限制，直接执行。
     */
    suspend fun <T> withPermit(
        providerId: String,
        maxConcurrentRequests: Int = DEFAULT_PROVIDER_MAX_CONCURRENT_REQUESTS,
        onQueued: suspend (Int) -> Unit = {},
        onAdmitted: suspend () -> Unit = {},
        block: suspend () -> T
    ): T {
        if (providerId.isBlank() || maxConcurrentRequests <= 0) return block()

        val (gate, waiter, requestsAhead) = synchronized(lock) {
            val current = gates[providerId]
            val selected = if (current == null || current.users == 0 && current.limit != maxConcurrentRequests) {
                Gate(maxConcurrentRequests).also { gates[providerId] = it }
            } else {
                current
            }
            selected.users++
            if (selected.activeRequests < selected.limit && selected.waiters.isEmpty()) {
                selected.activeRequests++
                Triple(selected, null, 0)
            } else {
                val queued = Waiter().also(selected.waiters::addLast)
                Triple(selected, queued, selected.activeRequests + selected.waiters.size - 1)
            }
        }

        var hasPermit = waiter == null
        return try {
            if (waiter != null) {
                try {
                    onQueued(requestsAhead)
                    waiter.admitted.await()
                    hasPermit = true
                } catch (failure: Throwable) {
                    // 等待期间被取消：还没轮到自己就把占位从队列里摘掉，否则后面的人会白等一格。
                    synchronized(lock) {
                        if (waiter.granted) hasPermit = true else gate.waiters.remove(waiter)
                    }
                    throw failure
                }
                onAdmitted()
            }
            block()
        } finally {
            var next: Waiter? = null
            synchronized(lock) {
                if (hasPermit) {
                    gate.activeRequests--
                    next = gate.waiters.removeFirstOrNull()
                    next?.let {
                        it.granted = true
                        gate.activeRequests++
                    }
                } else {
                    waiter?.let(gate.waiters::remove)
                }
                gate.users--
                if (gate.users == 0) gates.remove(providerId, gate)
            }
            // 交接必须在锁外完成：await 的续体可能同步往下跑，持锁唤醒会把下一棒的 block 堵在锁上。
            next?.admitted?.complete(Unit)
        }
    }

    private class Gate(val limit: Int) {
        var activeRequests: Int = 0

        /** 门的使用者总数（在途 + 排队），归零后门可以被回收/换限。 */
        var users: Int = 0
        val waiters = ArrayDeque<Waiter>()
    }

    private class Waiter {
        val admitted = CompletableDeferred<Unit>()
        var granted: Boolean = false
    }
}
