package com.aharou.feature.agent.domain.schedule

/**
 * 定时任务执行结果的取值。
 *
 * 放在独立 object 里是因为 Worker 的 companion 是私有的，会话层（AIAgentViewModel）要回写结果
 * 时拿不到那些常量；两边共用这一份定义，避免字面量各写一份。跳过类结果仍见 Worker 自己的常量。
 */
object ScheduledTaskOutcome {
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}
