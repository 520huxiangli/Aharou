package com.aharou.feature.agent.presentation

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.domain.runner.AgentTurnCompletion
import com.aharou.feature.agent.domain.runner.AgentTurnResult
import com.aharou.feature.agent.domain.schedule.ScheduledRunRequest
import com.aharou.feature.agent.domain.schedule.ScheduledTaskOutcome
import com.aharou.feature.agent.domain.session.SessionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

suspend fun runScheduledTask(
    request: ScheduledRunRequest,
    sessionUseCase: SessionUseCase,
    dao: ScheduledTaskDao,
    execute: (sessionId: String, projectRoot: String, completion: AgentTurnCompletion) -> Job,
) {
    var outcome = ScheduledTaskOutcome.FAILED
    var execution: Job? = null
    try {
        val existing = request.targetSessionId?.let { sessionUseCase.getSessionById(it) }
        val session = existing ?: run {
            val root = request.workspacePath?.takeIf { it.isNotBlank() }
            if (root == null) {
                FileLogger.w(TAG, "定时任务缺少工作区且无目标会话，跳过: ${request.taskName}")
                return
            }
            sessionUseCase.newSessionEntity(workspacePath = root).copy(title = request.taskName).also {
                sessionUseCase.upsertSession(it)
            }
        }
        val completion = AgentTurnCompletion()
        FileLogger.i(TAG, "定时任务开始运行: ${request.taskName} -> session=${session.id}")
        execution = execute(session.id, session.workspacePath, completion)
        completion.bind(execution)
        outcome = when (completion.await()) {
            AgentTurnResult.COMPLETED -> ScheduledTaskOutcome.COMPLETED
            AgentTurnResult.FAILED -> ScheduledTaskOutcome.FAILED
            AgentTurnResult.CANCELLED -> ScheduledTaskOutcome.FAILED
        }
    } catch (e: CancellationException) {
        execution?.cancel(e)
        outcome = ScheduledTaskOutcome.FAILED
        FileLogger.i(TAG, "定时任务已取消: ${request.taskName}")
        throw e
    } catch (e: Exception) {
        FileLogger.w(TAG, "定时任务执行失败: ${request.taskName}", e)
    } finally {
        withContext(NonCancellable) {
            execution?.join()
            runCatching { dao.recordOutcomeForRun(request.taskId, request.claimedAt, outcome, System.currentTimeMillis()) }
                .onFailure { FileLogger.w(TAG, "回写定时任务结果失败: ${request.taskName}", it) }
        }
    }
}

private const val TAG = "ScheduledTaskRunHelper"
