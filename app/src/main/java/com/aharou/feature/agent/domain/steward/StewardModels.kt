package com.aharou.feature.agent.domain.steward

/**
 * 主管模型对「用户最初那句任务做完了没」的判定结果。
 *
 * [reason] 只进日志与界面说明，不回灌会话主链路；[nextInstruction] 是未完成时的下一步指令。
 */
data class StewardJudgement(
    val completed: Boolean,
    val reason: String = "",
    val nextInstruction: String? = null,
)

/**
 * 托管状态六态：空闲 → 监控中 →（判断中 → 自动发送中）循环 → 已完成 / 已停止。
 */
enum class StewardStatus {
    Idle,
    Monitoring,
    Judging,
    AutoSending,
    Completed,
    Stopped,
}

/** 托管在界面上的状态快照。 */
data class StewardState(
    val enabled: Boolean = false,
    /** 是否正在跑一次判定。 */
    val checking: Boolean = false,
    /** 已自动续发的轮数。 */
    val loopCount: Int = 0,
    val status: StewardStatus = StewardStatus.Idle,
)