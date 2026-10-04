package com.aharou.feature.agent.domain.subagent

/**
 * 子代理的兜底预算：限制单轮运行最多走多少轮工具调用、最多花多长时间。
 *
 * 子代理是自己跑循环的，一旦陷进「漫游式只读遍历」或跑飞，它会一直占着并发名额
 * （同时最多 [SubAgentEventBus.MAX_RUNNING] 个）、持续烧 token，主代理只能干等。
 * 这里给它一条硬边：到量就不再调工具，以明确原因收尾。主会话不受本预算约束
 * （它有自己的总次数熔断，见 StatefulAgentWorkflow 的 MAX_TOTAL_TOOL_CALLS）。
 */
object SubAgentBudget {

    /**
     * 单轮运行的轮数上限（一轮 = 一次工具批次的执行）。
     * 定在 24：一个边界清楚的子任务（读几个文件、改几处、写份报告）通常十轮以内就收，
     * 24 轮留足了多文件作业的余量，而跑飞的遍历两三分钟就能撞线。
     */
    const val MAX_TOOL_ROUNDS = 24

    /**
     * 单轮运行的时长上限（毫秒，20 分钟）。
     * 子代理占用的是 5 个并发位之一，正常任务不会单轮跑过 20 分钟；
     * 撞线说明它在原地打转，该让位给别的子代理了。
     */
    const val MAX_DURATION_MS = 20 * 60 * 1000L

    /** 收尾时写进错误码：便于主代理与日志区分「预算用尽」与普通报错。 */
    const val STOP_CODE = "SUBAGENT_BUDGET_EXCEEDED"

    /** 触发的预算维度。 */
    enum class Limit {
        /** 工具调用轮数到顶。 */
        TOOL_ROUNDS,

        /** 运行时长到顶。 */
        DURATION
    }

    /** 超限则返回命中的维度，未超限返回 null。轮数优先判定，便于文案给出更具体的原因。 */
    fun exceeded(toolRounds: Int, elapsedMs: Long): Limit? = when {
        toolRounds >= MAX_TOOL_ROUNDS -> Limit.TOOL_ROUNDS
        elapsedMs >= MAX_DURATION_MS -> Limit.DURATION
        else -> null
    }

    /** 收尾原因：既是子代理最后一条助手消息，也是随失败事件传给主代理的说明。 */
    fun stopReason(limit: Limit, toolRounds: Int, elapsedMs: Long): String {
        val used = "已用 $toolRounds 轮工具调用、耗时 ${elapsedMs / 60_000} 分 ${elapsedMs % 60_000 / 1000} 秒"
        val capped = when (limit) {
            Limit.TOOL_ROUNDS -> "达到轮数上限（$MAX_TOOL_ROUNDS 轮）"
            Limit.DURATION -> "达到时长上限（${MAX_DURATION_MS / 60_000} 分钟）"
        }
        return "【子代理预算用尽】$used，$capped，现已停止继续调用工具。" +
            "本次任务未能完成，请主代理据此收尾：缩小任务范围后重新派发，或直接采用已有的部分输出。"
    }
}
