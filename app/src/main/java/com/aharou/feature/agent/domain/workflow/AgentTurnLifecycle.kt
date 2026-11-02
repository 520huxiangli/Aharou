package com.aharou.feature.agent.domain.workflow

import com.aharou.core.util.FileLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 一轮 agent 的触发来源。
 *
 * 事件归属的第一件事是「这一轮是谁开的」：定时任务与托管续跑都是机器发起的一轮，没人盯着、中途
 * 也不会有人补一句话，判定侧（卡住提醒、结果归属）本可以区别对待；用户在输入框手打的才该按
 * 交互式一轮处理。来源不确定时一律记 [UNKNOWN]——按「没声明」处理，不自作猜测。
 *
 * [wire] 是日志里的稳定字面量，改动它等于破坏既有日志的 grep 口径。
 */
enum class AgentTurnTrigger(val wire: String) {
    USER("user"),
    SCHEDULED_TASK("scheduled_task"),
    STEWARD_CONTINUANCE("steward_continuance"),
    UNKNOWN("unknown"),
}

/**
 * 一次运行的归属戳：哪个会话、第几代、谁触发。
 *
 * [generation] 在同一会话里每开新一轮 +1，用来识别「上一轮迟到的产出」。用户按停止后立刻重发、
 * 托管或定时任务在同一会话上接着跑，都会出现旧协程还活着并在稍后继续吐事件的情况；没有世代号，
 * 这些迟到事件与新一轮的事件长得一模一样，会被算进新一轮的进度、卡住与完成判定。
 */
internal data class AgentTurnStamp(
    val turnId: String,
    val sessionId: String?,
    val generation: Long,
    val trigger: AgentTurnTrigger,
)

/**
 * 会话级轮次世代登记表。
 *
 * 只存「该会话当前是第几代、活着的轮次是哪一个」，进程内有效：重启后所有协程都没了，迟到的
 * 事件也随之消失，因此不需要落库。会话数远小于上限时不清表，超过就整体清空（只防长期累积，
 * 清空的最坏后果是某会话的世代号从头计，迟到判定退化成「可能误收」，不会误杀在跑的轮次）。
 */
internal object AgentTurnLifecycle {
    private const val TAG = "AgentTurn"
    private const val MAX_TRACKED_SESSIONS = 512

    private class Slot {
        var generation: Long = 0
        var activeTurnId: String? = null
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** 无会话 id 的调用（子代理、一次性任务）共用一个槽，世代号在它们之间仍然可比。 */
    private fun key(sessionId: String?): String = sessionId ?: ""

    private fun slotFor(sessionId: String?): Slot {
        val key = key(sessionId)
        slots[key]?.let { return it }
        if (slots.size >= MAX_TRACKED_SESSIONS) slots.clear()
        return slots.computeIfAbsent(key) { Slot() }
    }

    /** 开一轮：世代号 +1，并把本轮记为该会话当前活着的轮次。 */
    fun begin(sessionId: String?, trigger: AgentTurnTrigger): AgentTurnStamp {
        val slot = slotFor(sessionId)
        val turnId = UUID.randomUUID().toString().substring(0, 8)
        val generation = synchronized(slot) {
            slot.generation += 1
            slot.activeTurnId = turnId
            slot.generation
        }
        FileLogger.d(TAG, "turn begin turn=$turnId session=${sessionId ?: "-"} gen=$generation trigger=${trigger.wire}")
        return AgentTurnStamp(turnId, sessionId, generation, trigger)
    }

    /** 一轮结束（正常收尾、失败或被取消都算）：只在它仍是当前活着的轮次时摘掉。 */
    fun finish(stamp: AgentTurnStamp) {
        val slot = slots[key(stamp.sessionId)] ?: return
        synchronized(slot) {
            if (slot.activeTurnId == stamp.turnId) slot.activeTurnId = null
        }
    }

    /** 该轮是否仍然活着：世代号仍是该会话最新的一代，且它没被摘掉（没结束、没被新一轮顶掉）。 */
    fun isLive(stamp: AgentTurnStamp): Boolean {
        val slot = slots[key(stamp.sessionId)] ?: return false
        return synchronized(slot) { slot.generation == stamp.generation && slot.activeTurnId == stamp.turnId }
    }
}

/**
 * 这条事件是否「相对给定轮次已经迟到」：世代号或会话对不上，就不该参与那一轮的判断，也不该把
 * 它的结果算进新一轮。没打过戳的事件（压缩回调、测试里直接构造的）不判为迟到——它不属于任何
 * 轮次，是否采用由调用方自己决定。
 */
internal fun AgentEvent.isStaleFor(turn: AgentTurnStamp): Boolean {
    val own = this.turn ?: return false
    return own.generation != turn.generation || own.sessionId != turn.sessionId
}

/**
 * 存活判定白名单：这批事件证明这一轮真的有产出。只有它们能当「还活着」的证据。
 *
 * 为什么排除另外几类：`ContextUsage`、`CompactionStarted` / `CompactionFinished`、`ModeChanged`、
 * `Retrying`、`KeySwitched` 都是**这一轮内部机制的旁白**——上下文占用统计、压缩、重试、换 Key
 * 在网络抖动时会自己循环产出，模型一个字都没多写，拿它们当存活信号等于把「原地打转」当成「在干活」。
 * `ToolCallPreparing`（工具名已知、参数还在流）、`SamplingLoopDetected`（检测到复读并截断）、
 * `CompactionFailed`、`Failed`、`Completed` 同理不是产出：前两者是过程噪音，后三者是终态或报错。
 */
internal fun AgentEvent.isSubstantiveActivity(): Boolean = when (this) {
    // 正文有实质文字才算；但带工具调用或有附件时，即使正文本轮为空也是真实产出（模型做了决定 / 出了图）。
    is AgentEvent.AssistantText -> content.hasSubstantiveText() || toolCalls.isNotEmpty() || attachments.isNotEmpty()
    is AgentEvent.AssistantDelta -> accumulated.hasSubstantiveText()
    is AgentEvent.ReasoningDelta -> accumulated.hasSubstantiveText()
    is AgentEvent.ToolCallStarted -> true
    is AgentEvent.ToolCallProgress -> accumulated.hasSubstantiveText()
    is AgentEvent.ToolCallFinished -> true
    else -> false
}

/**
 * 文本类的第二道筛子：空白、控制/格式字符、以及纯标点骨架（```、---、***）都不算有产出。
 *
 * 判据是「至少有一个字母数字或非 ASCII 可见字符」：CJK、全角标点、emoji 本身就带信息，而
 * 纯 ASCII 标点（Markdown 装饰、分隔线）撑不起「模型写出了东西」这个结论。
 */
internal fun String.hasSubstantiveText(): Boolean = any { ch ->
    !ch.isWhitespace() &&
        ch.category != CharCategory.CONTROL &&
        ch.category != CharCategory.FORMAT &&
        (ch.isLetterOrDigit() || ch.code > 0x2E7F)
}

/**
 * 给事件流盖上轮次归属：开始收集时开一轮（世代号 +1），流结束或取消时摘掉。
 *
 * 为什么只在流出口套一层而不是每个 emit 点带参数：事件出口只有 `executeEvents` 一处，
 * 17 类事件却有十几处 emit；逐点传参会把归属逻辑摊进主循环与工具执行路径，也让「迟到事件」
 * 的口径分成十几份。集中在这里之后，判定侧只需要读事件上的 [AgentEvent.turn]。
 */
internal fun Flow<AgentEvent>.attributedToTurn(
    sessionId: String?,
    trigger: AgentTurnTrigger,
): Flow<AgentEvent> = flow {
    val turn = AgentTurnLifecycle.begin(sessionId, trigger)
    try {
        collect { event -> emit(event.stampedWith(turn)) }
    } finally {
        AgentTurnLifecycle.finish(turn)
    }
}

/**
 * 给单条事件盖戳。
 *
 * 直接写字段而不是 `copy(...)`：`copy` 不复制基类上的非构造字段，盖出来还是 null；而且会多出
 * 一个与原件 equals 相等、只在归属上不同的副本，调用方手里的引用就分叉了。
 */
internal fun <T : AgentEvent> T.stampedWith(turn: AgentTurnStamp): T {
    this.turn = turn
    return this
}
