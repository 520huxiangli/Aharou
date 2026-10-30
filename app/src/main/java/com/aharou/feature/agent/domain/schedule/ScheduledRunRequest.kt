package com.aharou.feature.agent.domain.schedule

/**
 * 一条待运行的定时任务：认领后交给执行服务，或由服务起不来时落库、等会话层就绪后捡起。
 *
 * @param targetSessionId 目标会话；null 表示按 [workspacePath] 新建一个会话再跑。
 * @param workspacePath 目标会话的工作区；[targetSessionId] 非空时以会话自身的为准。
 */
data class ScheduledRunRequest(
    val taskId: String,
    val taskName: String,
    val prompt: String,
    val targetSessionId: String?,
    val workspacePath: String?,
)
