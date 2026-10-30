package com.aharou.core.util

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * 会话级停止纪元。
 *
 * 停止不能只靠「取消协程」：正在执行的容器命令、挂在 AIDL 上的调用未必响应取消，
 * 而排队中、还没 fork 出进程的命令更是取消失效。改成按纪元比对——每次用户按下停止就把
 * 该会话的纪元加一，命令在启动前与每个等待点比对启动时的纪元，变了就判定「用户已停止」，
 * 不必真的有一个进程可杀。
 */
object SessionStopEpochs {
    private val epochs = ConcurrentHashMap<String, AtomicLong>()
    private val stoppers = ConcurrentHashMap<String, MutableSet<() -> Unit>>()

    fun current(sessionId: String): Long = epochs[sessionId]?.get() ?: 0L

    /**
     * 注册该会话正在跑的进程的终止回调：命令阻塞在 readLine 上时不会走到纪元比对点，
     * 只能由停止方直接杀进程来打破阻塞。返回注销函数，命令结束时必须调用。
     */
    fun registerStopper(sessionId: String, stopper: () -> Unit): () -> Unit {
        val set = stoppers.computeIfAbsent(sessionId) { java.util.Collections.synchronizedSet(mutableSetOf()) }
        set += stopper
        return { set.remove(stopper) }
    }

    /** 用户按下停止：推进纪元，让所有拿旧纪元在跑的操作立刻看到「已停止」。 */
    fun bump(sessionId: String): Long {
        val next = epochs.computeIfAbsent(sessionId) { AtomicLong() }.incrementAndGet()
        stoppers[sessionId]?.toList()?.forEach { runCatching { it() } }
        return next
    }

    fun isStopped(sessionId: String, epoch: Long): Boolean = current(sessionId) != epoch
}

/**
 * 把「本条工具调用所属会话与启动时的纪元」带进协程上下文，让底层的容器/命令执行
 * 不必逐层加 sessionId 参数就能判断自己是否已被用户停止。
 */
data class ToolStopEpoch(
    val sessionId: String,
    val epoch: Long,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolStopEpoch>

    fun stopped(): Boolean = SessionStopEpochs.isStopped(sessionId, epoch)
}

/** 把会话的当前纪元包成可叠加进协程上下文的元素；没有会话时不附加任何东西。 */
fun toolStopContext(sessionId: String?): CoroutineContext =
    sessionId?.let { ToolStopEpoch(it, SessionStopEpochs.current(it)) } ?: kotlin.coroutines.EmptyCoroutineContext

/**
 * 把当前协程所属会话的停止事件接到一个终止动作上，返回注销函数。
 *
 * 进程阻塞在阻塞读上时走不到纪元比对点，只能由停止方直接动手杀。不在工具停止上下文里
 * （如终端页发起的命令）时返回 null，行为不变。
 */
suspend fun linkProcessToSessionStop(onStop: () -> Unit): (() -> Unit)? {
    val epoch = kotlin.coroutines.coroutineContext[ToolStopEpoch] ?: return null
    return SessionStopEpochs.registerStopper(epoch.sessionId, onStop)
}

/**
 * 在当前作用域里挂一个「取消或会话停止就执行 [onStop]」的钩子。
 *
 * 必须用 awaitCancellation + finally：命令卡在阻塞的 readLine 上时协程不响应取消，
 * job 停在 cancelling 永不完成，用 invokeOnCompletion 永远等不到回调。
 */
fun CoroutineScope.launchKillOnStop(onStop: () -> Unit): Job = launch {
    val unregister = linkProcessToSessionStop(onStop)
    try {
        awaitCancellation()
    } finally {
        unregister?.invoke()
        onStop()
    }
}
