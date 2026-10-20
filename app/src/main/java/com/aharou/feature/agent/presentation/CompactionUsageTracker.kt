package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.workflow.AgentEvent

/**
 * 压缩期间的上下文用量记账（按会话记录最近一次压缩是否失败）。
 *
 * [AgentEvent.CompactionFinished] 由压缩器在 finally 里发出，失败也会发，所以「结束后要不要
 * 清掉估算用量」不能只看这条事件：压缩失败时历史没有变化，清掉估算会让只拿得到估算值的会话
 * （上游不返回 token）外圈直接熄掉、误报「已腾出」。
 *
 * 失败标记由 [markFailed] 埋下，两个出口消费它：
 * - [afterCompaction]：压缩结束时读一次——有标记就保留估算；
 * - [report]：收到新的 [AgentEvent.ContextUsage] 时清掉——压缩在真正开始前就失败
 *   （固定开销吃满预算，只发 CompactionFailed、没有 CompactionFinished）时标记不会一直悬着，
 *   否则下一轮压缩成功也会被误判成失败、留着过期估算。
 *
 * 只在 ViewModel 的事件收集协程里访问，不需要额外同步。
 */
internal class CompactionUsageTracker {
    private val failedSessions = mutableSetOf<String>()

    fun markFailed(sessionId: String) {
        failedSessions += sessionId
    }

    /** 压缩结束后应当展示的用量表：成功则丢掉该会话的估算，失败则原样保留。 */
    fun afterCompaction(
        sessionId: String,
        usages: Map<String, AgentEvent.ContextUsage>
    ): Map<String, AgentEvent.ContextUsage> =
        if (failedSessions.remove(sessionId)) usages else usages - sessionId

    /** 记录一次新的用量上报；它同时清掉该会话的失败标记（新上报比上一轮失败更新）。 */
    fun report(
        sessionId: String,
        usage: AgentEvent.ContextUsage,
        usages: Map<String, AgentEvent.ContextUsage>
    ): Map<String, AgentEvent.ContextUsage> {
        failedSessions -= sessionId
        return usages + (sessionId to usage)
    }
}
