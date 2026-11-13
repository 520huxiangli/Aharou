package com.aharou.feature.agent.data.local.task

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.AgentTaskEntity
import com.aharou.feature.agent.data.local.entity.AgentTaskStatus
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentRun
import com.aharou.feature.agent.domain.subagent.SubAgentRunLifecycle
import com.aharou.feature.agent.domain.subagent.SubAgentRunRejectedException
import com.aharou.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** 子代理轮次必须等待恢复与状态落库，不能把生命周期提交寄托在有界事件广播上。 */
@Singleton
class SubAgentTaskTracker @Inject constructor(
    private val agentTaskDao: AgentTaskDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus,
    private val chatSessionDao: ChatSessionDao
) : SubAgentRunLifecycle {
    companion object {
        private const val TAG = "SubAgentTaskTracker"
        private const val INSTRUCTION_SNIPPET_MAX = 120
        private const val INTERRUPTED_RESULT = "应用进程被关闭，任务未完成"
        private const val DELETED_RESULT = "子代理会话已被删除"
    }

    private val ready = CompletableDeferred<Unit>()
    private val started = AtomicBoolean()
    private val sessionLocks = ConcurrentHashMap<String, Mutex>()

    private fun lock(sessionId: String): Mutex = sessionLocks.getOrPut(sessionId) { Mutex() }

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            try {
                recoverInterruptedTasks()
                ready.complete(Unit)
            } catch (error: Throwable) {
                ready.completeExceptionally(error)
                FileLogger.e(TAG, "断点恢复失败", error)
            }
        }
    }

    override suspend fun beginRun(sessionId: String, parentSessionId: String, expectedRunId: String?): SubAgentRun? {
        ready.await()
        return lock(sessionId).withLock {
            val session = chatSessionDao.getById(sessionId) ?: return@withLock null
            if (session.parentId != parentSessionId) return@withLock null
            val old = agentTaskDao.getById(sessionId)
            val version = nextVersion(old?.updatedAt)
            val reserved = eventBus.currentRun(sessionId)?.takeIf { it.version == 0L }
            val run = try {
                eventBus.beginRun(sessionId, parentSessionId, version, expectedRunId)
            } catch (rejected: SubAgentRunRejectedException) {
                if (reserved != null) {
                    try {
                        if (old == null) agentTaskDao.insertIfAbsent(AgentTaskEntity(
                            id = sessionId, parentSessionId = parentSessionId,
                            instruction = eventBus.instruction(sessionId), status = AgentTaskStatus.FAILED.name,
                            result = rejected.message, createdAt = version, updatedAt = version, finishedAt = version,
                        ))
                        eventBus.stopReserved(SubAgentEvent(sessionId, parentSessionId, SubAgentEventType.FAILED,
                            rejected.message.orEmpty(), accepted = false, runId = reserved.runId))
                    } finally {
                        eventBus.releaseReserved(reserved)
                    }
                }
                throw rejected
            } ?: return@withLock null
            try {
                if (old == null) {
                    check(agentTaskDao.insertIfAbsent(AgentTaskEntity(
                        id = sessionId,
                        parentSessionId = parentSessionId,
                        instruction = eventBus.instruction(sessionId),
                        status = AgentTaskStatus.RUNNING.name,
                        createdAt = version,
                        updatedAt = version,
                    )) >= 0)
                } else {
                    check(agentTaskDao.beginRun(sessionId, old.updatedAt, version) > 0)
                }
                check(eventBus.runStarted(run))
                run
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try {
                        commitTerminal(run, SubAgentEventType.FAILED, error.message.orEmpty(), false, true)
                    } finally {
                        eventBus.releaseRun(run)
                    }
                }
                throw error
            }
        }
    }

    override suspend fun finishRun(run: SubAgentRun, type: SubAgentEventType, detail: String, accepted: Boolean?): Boolean {
        ready.await()
        return lock(run.sessionId).withLock {
            try {
                commitTerminal(run, type, detail, accepted, true)
            } finally {
                eventBus.releaseRun(run)
            }
        }
    }

    override suspend fun stopRun(
        sessionId: String,
        parentSessionId: String,
        deleted: Boolean,
        broadcast: Boolean,
        expectedRun: SubAgentRun?,
    ): Boolean {
        ready.await()
        return lock(sessionId).withLock {
            val current = eventBus.currentRun(sessionId)
            if (current?.runId != expectedRun?.runId) return@withLock false
            val type = if (deleted) SubAgentEventType.DELETED else SubAgentEventType.STOPPED
            if (current != null && eventBus.isCurrentRun(current)) {
                return@withLock try {
                    commitTerminal(current, type, if (deleted) DELETED_RESULT else "", false, broadcast)
                } finally {
                    eventBus.releaseRun(current)
                }
            }
            val event = SubAgentEvent(sessionId, parentSessionId, type, runId = current?.runId)
            val old = agentTaskDao.getById(sessionId)
            if (old == null && current != null) {
                val now = nextVersion(null)
                agentTaskDao.insertIfAbsent(AgentTaskEntity(
                    id = sessionId, parentSessionId = parentSessionId, instruction = eventBus.instruction(sessionId),
                    status = AgentTaskStatus.CANCELLED.name, result = if (deleted) DELETED_RESULT else null,
                    createdAt = now, updatedAt = now, finishedAt = now,
                ))
            }
            if (old?.status == AgentTaskStatus.RUNNING.name) {
                val now = nextVersion(old.updatedAt)
                agentTaskDao.compareAndSetStatus(sessionId, old.status, AgentTaskStatus.CANCELLED.name,
                    if (deleted) DELETED_RESULT else null, now, now, old.updatedAt)
            }
            eventBus.stopReserved(event, broadcast)
        }
    }

    private suspend fun commitTerminal(
        run: SubAgentRun,
        type: SubAgentEventType,
        detail: String,
        accepted: Boolean?,
        broadcast: Boolean,
    ): Boolean {
        require(type == SubAgentEventType.COMPLETED || type == SubAgentEventType.FAILED ||
            type == SubAgentEventType.STOPPED || type == SubAgentEventType.DELETED)
        if (!eventBus.isCurrentRun(run)) return false
        val status = when (type) {
            SubAgentEventType.COMPLETED -> AgentTaskStatus.COMPLETED
            SubAgentEventType.FAILED -> AgentTaskStatus.FAILED
            else -> AgentTaskStatus.CANCELLED
        }
        val now = nextVersion(run.version)
        if (agentTaskDao.compareAndSetStatus(
            run.sessionId, AgentTaskStatus.RUNNING.name, status.name,
            if (type == SubAgentEventType.COMPLETED) null else detail.ifBlank { null },
            now, now, run.version,
        ) == 0) return false
        return eventBus.finishRun(run, SubAgentEvent(run.sessionId, run.parentSessionId, type,
            detail, accepted, runId = run.runId), broadcast)
    }

    internal fun nextVersion(previous: Long?, now: Long = System.currentTimeMillis()): Long =
        maxOf(now, previous?.plus(1) ?: now)

    suspend fun recoverInterruptedTasks() {
        val stale = agentTaskDao.getByStatus(AgentTaskStatus.RUNNING.name)
        if (stale.isEmpty()) return
        val now = System.currentTimeMillis()
        var interrupted = 0
        stale.forEach { task ->
            val updated = agentTaskDao.compareAndSetStatus(
                taskId = task.id,
                fromStatus = AgentTaskStatus.RUNNING.name,
                toStatus = AgentTaskStatus.INTERRUPTED.name,
                result = INTERRUPTED_RESULT,
                updatedAt = nextVersion(task.updatedAt, now),
                finishedAt = now,
                expectedUpdatedAt = task.updatedAt,
            )
            if (updated == 0) return@forEach
            interrupted++
            agentMessageDao.insert(interruptedNotification(task, now))
        }
        if (interrupted > 0) FileLogger.i(TAG, "断点恢复：$interrupted 个子代理任务标记为中断")
    }

    private fun interruptedNotification(task: AgentTaskEntity, now: Long): AgentMessageEntity =
        AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = task.parentSessionId,
            role = MessageRole.USER.name,
            content = buildString {
                appendLine(BACKGROUND_NOTIFICATION_PREFIX)
                appendLine("这是一条子代理任务中断通知，不是来自用户的消息。")
                appendLine()
                appendLine("上次派发的子代理任务未完成就被中断（应用进程已关闭），子会话 id：${task.id}")
                instructionSnippet(task.instruction)?.let { appendLine("当时的任务指令：$it") }
                append("请先调用 task(action=\"read\", id=\"${task.id}\") 查看它的现状与已有产出，再决定重新派发还是接手完成。")
            },
            timestamp = now
        )

    private fun instructionSnippet(instruction: String): String? {
        val clean = instruction.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        return if (clean.length <= INSTRUCTION_SNIPPET_MAX) clean
        else clean.take(INSTRUCTION_SNIPPET_MAX) + "…"
    }
}
