package com.aharou.feature.agent.domain.subagent

/**
 * 子代理收场时的判定结论。
 *
 * 此前只区分「还在跑 / 已结束」，于是「没工具调用就收场」会被当作完成。这里把能观察到的
 * 事实（结论是否为空、写入有没有被拦或最终失败、是否自述未完成）落成明确的终止原因。
 */
enum class SubAgentTermination {
    /** 有可交付结论，且没有未落盘的写入。 */
    DELIVERED,

    /** 没有产出结论文本（空回复，或最后一轮只剩工具调用）。 */
    EMPTY_CONCLUSION,

    /** 结论里自述任务未完成 / 交由主代理。 */
    INCOMPLETE_SELF_REPORTED,

    /** 有写入被写租约拦截，产物并未真正落盘。 */
    WRITE_LEASE_BLOCKED,

    /** 写入尝试过但最终失败，声称的产物并不存在。 */
    WRITE_FAILED,

    /** 自报 complete，但 claim 验收项未被核验通过，已按降级状态处理。 */
    CLAIM_DOWNGRADED
}

data class SubAgentVerdict(
    val termination: SubAgentTermination,
    val accepted: Boolean,
    val reason: String
)

/** 判定所用的执行期证据（从子会话消息里提炼，不依赖关键词猜测）。 */
data class SubAgentEvidence(
    /** 被写租约拦截的写入目标。 */
    val blockedWrites: List<String> = emptyList(),
    /** 写过但最后一次尝试失败的写入目标（未被后续成功覆盖）。 */
    val unresolvedWriteFailures: List<String> = emptyList()
)

object SubAgentConclusionJudge {

    /**
     * 只收第一人称交接 / 未完成语义。刻意不收「写入失败」「需要审批」这类现象描述词——
     * 只读审计任务的合法结论里（如「检查为什么 X 服务写入失败」）它们描述的是被审计对象，
     * 按字面判未完成会误伤；那两种情形由 [SubAgentEvidence] 的结构化证据覆盖。
     */
    private val INCOMPLETE_MARKERS = listOf(
        "尚未完成", "未能完成", "无法完成", "没有完成", "未完成",
        "交由主代理", "交由主智能体", "交给主代理", "请主代理", "需主代理", "由主代理完成",
        "unable to complete", "could not complete", "was not completed"
    )

    fun judge(conclusion: String, evidence: SubAgentEvidence, claimDowngraded: Boolean): SubAgentVerdict {
        if (conclusion.isBlank()) {
            return SubAgentVerdict(
                SubAgentTermination.EMPTY_CONCLUSION, false,
                "没有产出结论文本，不能据此判定任务完成"
            )
        }
        if (evidence.blockedWrites.isNotEmpty()) {
            return SubAgentVerdict(
                SubAgentTermination.WRITE_LEASE_BLOCKED, false,
                "${evidence.blockedWrites.size} 次写入被写租约拦截：${evidence.blockedWrites.take(5).joinToString("、")}"
            )
        }
        if (evidence.unresolvedWriteFailures.isNotEmpty()) {
            return SubAgentVerdict(
                SubAgentTermination.WRITE_FAILED, false,
                "${evidence.unresolvedWriteFailures.size} 个写入目标最终仍未成功落盘：" +
                    evidence.unresolvedWriteFailures.take(5).joinToString("、")
            )
        }
        val marker = INCOMPLETE_MARKERS.firstOrNull { conclusion.contains(it, ignoreCase = true) }
        if (marker != null) {
            return SubAgentVerdict(
                SubAgentTermination.INCOMPLETE_SELF_REPORTED, false,
                "结论自述任务未完成（命中「$marker」）"
            )
        }
        if (claimDowngraded) {
            return SubAgentVerdict(
                SubAgentTermination.CLAIM_DOWNGRADED, false,
                "自报的完成主张有验收项核验不上，已按降级状态处理"
            )
        }
        return SubAgentVerdict(SubAgentTermination.DELIVERED, true, "")
    }
}
