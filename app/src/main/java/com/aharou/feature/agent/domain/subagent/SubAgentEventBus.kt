package com.aharou.feature.agent.domain.subagent

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理事件类型。前五种为生命周期事件；后两种为收发消息事件，只投递内容，不影响活跃集合。
 */
enum class SubAgentEventType {
    SPAWNED, COMPLETED, FAILED, STOPPED,

    /**
     * 子代理会话已被 task(action="del") 删除（含全部消息）。与 STOPPED 的区别：
     * 收集方不得再给该会话补送通知、起后续轮次（会话已不存在，只会白烧一轮 LLM 并落孤儿消息），
     * 而应清掉它的运行时状态。
     */
    DELETED,

    /** 主会话向子代理发来一条消息（发送方是父会话）。 */
    MESSAGE_FROM_PARENT,

    /** 子代理向主会话发来一条消息（发送方是子会话）。 */
    MESSAGE_FROM_SUB
}

/**
 * 子代理生命周期事件。
 *
 * @property subSessionId 子代理会话 id。
 * @property parentSessionId 父会话 id（子会话记录里 parentId）。
 * @property type 事件类型。
 * @property detail 附加说明：SPAWNED 为任务指令；COMPLETED/FAILED 为子代理收尾的终态判定（终止状态 + 交付判定 + claim 裁定 + token 用量），FAILED 另带错误信息；
 *   MESSAGE_FROM_PARENT / MESSAGE_FROM_SUB 为消息正文。
 */
data class SubAgentEvent(
    val subSessionId: String,
    val parentSessionId: String,
    val type: SubAgentEventType,
    val detail: String = "",

    /**
     * 子代理收尾时是否被交付判定接受（与 `task` 读取路径同一份结论）；
     * null 表示未判定（例如消息读取失败），消费方按事件类型处理。
     */
    val accepted: Boolean? = null,

    /**
     * MESSAGE_FROM_SUB 的发送方是不是收件人的同批队友（子代理→子代理，`task(action="tell")`）。
     * false 时发送方向是「子代理→父会话」（`messageParent`），两者回复方式不同：
     * 队友间回信用 tell，向上回信用 send。
     */
    val fromSibling: Boolean = false
)

/**
 * 子代理事件总线：TaskTool 发出 SPAWNED（子代理已创建），ViewModel 收集后自动
 * 在子会话上启动 AI 工作流；子会话工作流结束时 ViewModel 再发 COMPLETED/FAILED，
 * 父会话据此注入后台通知（类比 terminal 的 notify=true 异步回调）。
 *
 * 同时维护活跃子代理会话 id 集合，供 TaskTool 查询并发上限（默认最多 5 个运行中，可在设置里调整）。
 */
@Singleton
class SubAgentEventBus @Inject constructor(
    private val maxRunningSource: MaxRunningSource,
    private val communicationSource: CommunicationEnabledSource,
) {
    /** 子代理之间的对等通信（team / tell）是否开启；工具层与手册层都据此决定说不说、做不做。 */
    fun communicationEnabled(): Boolean = communicationSource.communicationEnabled()
    private val _events = MutableSharedFlow<SubAgentEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SubAgentEvent> = _events.asSharedFlow()

    /** 当前活跃（运行中）的子代理会话 id 集合。 */
    private val _activeSubSessionIds = MutableStateFlow<Set<String>>(emptySet())
    val activeSubSessionIds: StateFlow<Set<String>> = _activeSubSessionIds.asStateFlow()

    /**
     * 子会话 → 父会话的内存登记表，供兄弟消息路由与「团队名单」。总线在 SPAWNED 时同步登记
     * （不依赖订阅者，投递失败也登记成功）；只覆盖本进程，跨进程场景由会话记录的 parentId 兜底。
     * emit 可能被多协程并发调用，故用并发 Map。
     */
    private val parentOfSub = ConcurrentHashMap<String, String>()

    /** 最近一批子会话的终态事件，供晚到的等待方回捞（见 [terminalEvent]）。只留终态，量级与一次派的子代理数同阶。 */
    private val terminalCache = ConcurrentHashMap<String, SubAgentEvent>()

    /**
     * 活跃集合的读-改-写锁：[emit] 与 [release] 都可能被不同协程同时调用，
     * 各自直接改 `_activeSubSessionIds.value` 会互相覆盖（丢一次移除 → 名额永久泄漏）。
     */
    private val activeLock = Any()

    /** 运行中的子代理数量。 */
    val activeCount: Int get() = _activeSubSessionIds.value.size

    /** 当前并发上限，读用户配置（默认 [DEFAULT_MAX_RUNNING]）。整个应用共享，不按父会话分组。 */
    val maxRunning: Int get() = maxRunningSource.maxRunning()

    /** 是否已达并发上限。 */
    val isFull: Boolean get() = activeCount >= maxRunning

    /**
     * 直接把某个子代理移出活跃集合，不广播事件；返回它此前是否处于活跃状态。
     *
     * 用户在界面上手动停止或删除运行中的子会话时走这条路径：不能改用 [emit]，
     * 因为 ViewModel 收到 STOPPED 事件后又会回调 stopAgentSession，形成无限循环。
     * 返回值同时用于区分「用户手动终止」与「TaskTool 已处理过」（后者返回 false），
     * 避免向父代理重复投递通知。
     */
    fun release(subSessionId: String): Boolean = unmarkActive(subSessionId)

    /** 把 [subSessionId] 加入活跃集合；与 [unmarkActive] 共用 [activeLock]。 */
    private fun markActive(subSessionId: String) {
        synchronized(activeLock) {
            _activeSubSessionIds.value = _activeSubSessionIds.value + subSessionId
        }
    }

    /** 把 [subSessionId] 移出活跃集合，返回它此前是否在集合里。 */
    private fun unmarkActive(subSessionId: String): Boolean = synchronized(activeLock) {
        val current = _activeSubSessionIds.value
        if (subSessionId !in current) return@synchronized false
        _activeSubSessionIds.value = current - subSessionId
        true
    }

    fun emit(event: SubAgentEvent) {
        // 同步维护活跃集合
        when (event.type) {
            SubAgentEventType.SPAWNED -> {
                markActive(event.subSessionId)
                parentOfSub[event.subSessionId] = event.parentSessionId
            }
            SubAgentEventType.COMPLETED, SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED, SubAgentEventType.DELETED -> {
                unmarkActive(event.subSessionId)
                if (event.type.isTerminal()) terminalCache[event.subSessionId] = event
            }
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> {
                // 收发消息不改变运行状态，活跃集合保持不变。
            }
        }
        _events.tryEmit(event)
    }

    private fun SubAgentEventType.isTerminal(): Boolean =
        this == SubAgentEventType.COMPLETED || this == SubAgentEventType.FAILED ||
            this == SubAgentEventType.STOPPED

    /**
     * 回捞某个子会话已经发过的终态事件。
     *
     * 为什么需要：`events` 是无 replay 的 SharedFlow，只发给"此刻已订阅"的收集者。pipeline 要等
     * 一整层子代理派发完毕才开始等待终态，期间秒完成的子代理事件早已过去，单靠 `first{}` 会一路等到
     * 30 分钟超时。这里留一份最近终态的短期缓存，让晚到的等待方能补上。
     */
    fun terminalEvent(subSessionId: String): SubAgentEvent? = terminalCache[subSessionId]

    companion object {
        /** 同时运行的子代理上限的默认值。实际值由 [SubAgentSettings] 给，工具层只读实例值。 */
        const val DEFAULT_MAX_RUNNING = 5
    }

    // ── 兄弟（同父）消息路由 ──────────────────────────────────
    // 与「父 → 子」「子 → 父」两条旧通道并存，行为不变：这里只新增一条兄弟可见的通道。

    /** 与 [subSessionId] 同父的其它子代理 id（本进程登记过的），供「团队名单」与兄弟路由使用。 */
    fun siblingsOf(subSessionId: String): List<String> {        val parent = parentOfSub[subSessionId] ?: return emptyList()
        return parentOfSub.entries.filter { it.value == parent && it.key != subSessionId }.map { it.key }
    }

    /** 两个子代理是否同父（互为兄弟）；同一会话或任一方未登记都算否。 */
    private fun areSiblings(a: String, b: String): Boolean {
        if (a == b) return false
        val pa = parentOfSub[a] ?: return false
        val pb = parentOfSub[b] ?: return false
        return pa == pb
    }

    /**
     * 兄弟消息路由：把 [fromSessionId] 的消息投递给同父的 [toSessionId]。
     *
     * 复用既有的子代理消息投递通道（MESSAGE_FROM_SUB，收件人取 parentSessionId）：收件人忙碌时搭车送达、
     * 空闲或已完成时被唤醒起新一轮，与「子 → 父」通道共用同一套分发逻辑。非兄弟、消息为空或收件人未登记
     * 时返回 false，由调用方决定提示；本方法不抛异常，任何失败都不影响主流程。
     */
    /**
     * 用数据库已证实的兄弟关系补登记内存表。
     *
     * 内存表只在 SPAWNED 事件时写入，进程重启后为空。调用方既然已从会话记录的 parentId 确认了
     * 二者同父，这里补一笔即可，否则重启后第一次 `tell` 会被当成「不是队友」直接拒掉。
     */
    fun registerSibling(subSessionId: String, parentSessionId: String) {
        parentOfSub[subSessionId] = parentSessionId
    }

    fun sendToSibling(fromSessionId: String, toSessionId: String, message: String): Boolean {
        if (message.isBlank() || !areSiblings(fromSessionId, toSessionId)) return false
        emit(
            SubAgentEvent(
                subSessionId = fromSessionId,
                parentSessionId = toSessionId,
                type = SubAgentEventType.MESSAGE_FROM_SUB,
                detail = message,
                fromSibling = true
            )
        )
        return true
    }
}
