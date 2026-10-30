package com.aharou.feature.agent.domain.schedule

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao

/**
 * 把「已认领但没跑起来」的定时任务捡起来跑。
 *
 * Worker 派发走内存总线，应用在后台或被杀时没人收，指令会随 `pendingRunPrompt` 落库；
 * 会话层（ViewModel）就绪时扫一遍，按任务定义重新组装运行请求。清标记放在执行前：
 * 跑失败也不该再捡一遍，真实结果由 `runScheduledTask` 回写。
 */
class ScheduledPendingRunner(
    private val dao: ScheduledTaskDao,
    private val runScheduledTask: suspend (ScheduledRunRequest) -> Unit,
) {
    suspend fun drain() {
        dao.pendingRuns().forEach { task ->
            dao.clearPendingRun(task.id, System.currentTimeMillis())
            runScheduledTask(
                ScheduledRunRequest(
                    taskId = task.id,
                    taskName = task.name,
                    prompt = task.pendingRunPrompt.orEmpty(),
                    targetSessionId = task.targetSessionId,
                    workspacePath = task.workspacePath,
                )
            )
        }
        if (dao.pendingRunsCount() > 0) {
            FileLogger.w(TAG, "捡跑定时任务时仍有挂起未清，下轮会话再试")
        }
    }

    private companion object {
        const val TAG = "ScheduledPendingRunner"
    }
}
