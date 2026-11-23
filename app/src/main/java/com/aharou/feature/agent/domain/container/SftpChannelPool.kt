package com.aharou.feature.agent.domain.container

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.sftp.SFTPClient

/** 空闲通道回收阈值：通道重建只要一个往返，宁短勿长。 */
private const val IDLE_TIMEOUT_MS = 30_000L

/** 空闲通道巡检间隔。 */
private const val SWEEP_INTERVAL_MS = 5_000L

/**
 * SFTP 通道池：在 SFTP 专用 transport 上按需开通道，以租约独占分配，空闲到点回收。
 *
 * 状态：
 * - [all] 池内全部通道，[idle] 是其中可借的子集；租出去的不在任何队列里；
 * - [creating] 正在新建的条数，计入容量上限（否则并发借会一起越过 [limit]）；
 * - [waiters] 达到上限后排队的借用人，收到通知（或报错）后重新判定一次现状。
 *
 * 借：有空闲通道就复用 → 未满上限就新建 → 否则排队。还：通道回空闲队列后叫醒排队者。
 * 通道已失效或池已清空则关闭并放掉一个名额。泄漏一个名额就会让后续操作永久排队，所以调用方
 * 必须在 finally 里归还（见 `RemoteSshConnection.withSftpOperation`）。
 *
 * 通道只经 [idle] 流转、不经 deferred 递手：等待方被取消时队列里可能还挂着它，
 * 把通道交给一个已取消的等待者会把名额永久卡死。
 */
internal class SftpChannelPool(
    private val limit: Int,
    private val createChannel: suspend () -> SFTPClient
) {
    /** 池内一条通道及其最近归还时间（毫秒，单调时钟）。 */
    private class Channel(val client: SFTPClient) {
        var lastUsedAt: Long = 0L
    }

    private sealed interface Decision {
        class Reuse(val channel: Channel) : Decision
        data object Create : Decision
        class Wait(val deferred: CompletableDeferred<Unit>) : Decision
    }

    /** 通道租约：持有期间通道归调用方独占（sshj 的 [SFTPClient] 非线程安全），用完必须 [release]。 */
    internal class Lease internal constructor(
        val client: SFTPClient,
        private val onRelease: suspend (SFTPClient) -> Unit
    ) {
        internal suspend fun release() = onRelease(client)
    }

    private val lock = Mutex()
    private val idle = ArrayDeque<Channel>()
    private val all = mutableSetOf<Channel>()
    private val waiters = ArrayDeque<CompletableDeferred<Unit>>()
    private var creating = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sweeper: Job? = null

    /** 借一条通道；无空闲通道且已达上限时挂起等待有人归还。 */
    suspend fun acquire(): Lease = Lease(obtain().client) { release(it) }

    /** 丢弃一条出问题的通道（传输层异常后调用），其余通道与 transport 保持不变。 */
    suspend fun invalidate(client: SFTPClient) {
        val removed = lock.withLock {
            val channel = all.firstOrNull { it.client === client } ?: return@withLock false
            all.remove(channel)
            idle.remove(channel)
            wakeOne()
            true
        }
        if (removed) runCatching { client.close() }
    }

    /** 关闭所有通道。[failWaiters] 为真时让排队者报错，否则让其重试（transport 要重建）。 */
    suspend fun closeAll(failWaiters: Boolean) {
        sweeper?.cancel()
        sweeper = null
        val channels = lock.withLock {
            val snapshot = all.toList()
            all.clear()
            idle.clear()
            while (true) {
                val waiter = waiters.removeFirstOrNull() ?: break
                if (failWaiters) {
                    waiter.completeExceptionally(java.io.IOException("SSH 连接已断开"))
                } else {
                    waiter.complete(Unit)
                }
            }
            snapshot
        }
        channels.forEach { runCatching { it.client.close() } }
    }

    private suspend fun obtain(): Channel {
        while (true) {
            val dead = mutableListOf<Channel>()
            val decision = lock.withLock { decide(dead) }
            // 关闭失效通道是 I/O，放到池锁外做
            dead.forEach { runCatching { it.client.close() } }
            when (decision) {
                is Decision.Reuse -> return decision.channel
                Decision.Create -> tryCreate()?.let { return it }
                is Decision.Wait -> decision.deferred.await()
            }
        }
    }

    /** 池锁内判定本次复用、新建还是排队；已关闭的空闲通道丢进 [dead] 待锁外关闭。 */
    private fun decide(dead: MutableList<Channel>): Decision {
        while (idle.isNotEmpty()) {
            val channel = idle.removeLast()
            if (isOpen(channel.client)) {
                channel.lastUsedAt = now()
                return Decision.Reuse(channel)
            }
            all.remove(channel)
            dead.add(channel)
        }
        if (all.size + creating < limit) {
            creating++
            return Decision.Create
        }
        return Decision.Wait(CompletableDeferred<Unit>().also { waiters.addLast(it) })
    }

    /** 新建一条通道并纳入池；期间池被清空或已满时关闭它并返回 null，由调用方重试。 */
    private suspend fun tryCreate(): Channel? {
        val channel = try {
            Channel(createChannel())
        } catch (e: Throwable) {
            lock.withLock {
                creating--
                wakeOne()
            }
            throw e
        }
        val accepted = lock.withLock {
            creating--
            val room = all.size < limit
            if (room) {
                channel.lastUsedAt = now()
                all.add(channel)
            }
            room
        }
        if (!accepted) {
            runCatching { channel.client.close() }
            return null
        }
        startSweeper()
        return channel
    }

    /** 交还通道：回空闲队列后叫醒一个排队者；通道已失效或池已清空时关闭并放掉名额。 */
    private suspend fun release(client: SFTPClient) {
        val closeAfter = lock.withLock {
            // 断开/重连时池已整体清空，迟到的归还不能把旧身份的通道塞回去
            val channel = all.firstOrNull { it.client === client } ?: return@withLock false
            if (!isOpen(client)) {
                all.remove(channel)
                wakeOne()
                return@withLock true
            }
            channel.lastUsedAt = now()
            idle.addLast(channel)
            // 先入队再叫人：被叫醒的一方回来自己拿，不靠 deferred 递手
            wakeOne()
            false
        }
        if (closeAfter) runCatching { client.close() }
    }

    private fun isOpen(client: SFTPClient): Boolean =
        runCatching { client.getSFTPEngine().getSubsystem().isOpen }.getOrDefault(false)

    /** 叫醒一个还在等的人。已取消的等待者会吃掉这次唤醒让别人空等，所以逐个跳过。 */
    private fun wakeOne() {
        while (true) {
            val waiter = waiters.removeFirstOrNull() ?: return
            if (waiter.complete(Unit)) return
        }
    }

    private fun startSweeper() {
        if (sweeper?.isActive == true) return
        sweeper = scope.launch {
            while (isActive) {
                delay(SWEEP_INTERVAL_MS)
                sweep()
            }
        }
    }

    /** 每轮最多回收一条：超上限时先关最旧空闲通道，否则回收空闲超时的，且至少留一条。 */
    private suspend fun sweep() {
        val stamp = now()
        val victim = lock.withLock {
            if (idle.isEmpty()) return@withLock null
            val overLimit = all.size > limit
            if (!overLimit && all.size <= 1) return@withLock null
            val channel = if (overLimit) {
                idle.first()
            } else {
                idle.firstOrNull { stamp - it.lastUsedAt >= IDLE_TIMEOUT_MS } ?: return@withLock null
            }
            idle.remove(channel)
            all.remove(channel)
            channel
        } ?: return
        runCatching { victim.client.close() }
    }

    private fun now(): Long = System.nanoTime() / 1_000_000
}
