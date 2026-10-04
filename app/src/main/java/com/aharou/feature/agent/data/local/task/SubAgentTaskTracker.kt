package com.aharou.feature.agent.data.local.task

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.AgentTaskEntity
import com.aharou.feature.agent.data.local.entity.AgentTaskStatus
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 子代理任务状态机：把只存在于内存的子代理生命周期事件落成持久化任务记录，
 * 并在启动时收尾上次进程被杀留下的 running 任务。
 *
 * 全部靠订阅 [SubAgentEventBus.events] 实现，不侵入 TaskTool / AgentTurnRunner / ViewModel
 * 的派发与停止路径。
 */
@Singleton
class SubAgentTaskTracker @Inject constructor(
    private val agentTaskDao: AgentTaskDao,
    private val agentMessageDao: AgentMessageDao,
    private val eventBus: SubAgentEventBus
) {
    companion object {
        private const val TAG = "SubAgentTaskTracker"

        /** 中断通知里回引指令片段的长度上限。 */
        private const val INSTRUCTION_SNIPPET_MAX = 120

        private const val INTERRUPTED_RESULT = "应用进程被关闭，任务未完成"
    }

    /**
     * 启动常驻订阅：先收尾上次进程遗留的 running 任务，再逐条消费事件写状态。
     * 恢复必须排在订阅之前——事件只在内存总线上投递，先订阅会把「恢复期间到达的事件」
     * 和「恢复前就已留下的旧状态」混在一起。调用方需传入 IO 作用域，不阻塞主线程。
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            runCatching { recoverInterruptedTasks() }
                .onFailure { FileLogger.e(TAG, "断点恢复失败", it) }
            eventBus.events.collect { event ->
                runCatching { handleEvent(event) }
                    .onFailure { FileLogger.w(TAG, "处理子代理事件失败：${event.type}", it) }
            }
        }
    }

    /**
     * 断点恢复：上次进程被杀时仍在 running 的任务不可能还在跑，逐条以
     * compare-and-set 标为 interrupted（输给并发迁移的任务跳过），并给父会话插一条
     * 系统通知，提示主代理先读现状或重新派发。
     */
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
                updatedAt = now,
                finishedAt = now
            )
            if (updated == 0) return@forEach
            interrupted++
            agentMessageDao.insert(interruptedNotification(task, now))
        }
        if (interrupted > 0) FileLogger.i(TAG, "断点恢复：$interrupted 个子代理任务标记为中断")
    }

    private suspend fun handleEvent(event: SubAgentEvent) {
        when (event.type) {
            SubAgentEventType.SPAWNED -> onCreate(event)
            SubAgentEventType.COMPLETED -> onTerminal(event, AgentTaskStatus.COMPLETED, null)
            SubAgentEventType.FAILED -> onTerminal(event, AgentTaskStatus.FAILED, event.detail.ifBlank { null })
            SubAgentEventType.STOPPED -> onTerminal(event, AgentTaskStatus.CANCELLED, event.detail.ifBlank { null })
            // 收发消息不改变任务状态。
            SubAgentEventType.MESSAGE_FROM_PARENT, SubAgentEventType.MESSAGE_FROM_SUB -> Unit
        }
    }

    private suspend fun onCreate(event: SubAgentEvent) {
        val now = System.currentTimeMillis()
        val inserted = agentTaskDao.insertIfAbsent(
            AgentTaskEntity(
                id = event.subSessionId,
                parentSessionId = event.parentSessionId,
                instruction = event.detail,
                status = AgentTaskStatus.RUNNING.name,
                createdAt = now,
                updatedAt = now
            )
        )
        // 已存在说明该子会话此前已登记过（重复派发 / 事件重放），保留首条，不覆盖既有状态。
        if (inserted < 0) return
        FileLogger.i(TAG, "任务登记：sub=${event.subSessionId} parent=${event.parentSessionId}")
    }

    private suspend fun onTerminal(event: SubAgentEvent, toStatus: AgentTaskStatus, result: String?) {
        val now = System.currentTimeMillis()
        val updated = agentTaskDao.compareAndSetStatus(
            taskId = event.subSessionId,
            fromStatus = AgentTaskStatus.RUNNING.name,
            toStatus = toStatus.name,
            result = result,
            updatedAt = now,
            finishedAt = now
        )
        if (updated > 0 || agentTaskDao.getById(event.subSessionId) != null) return
        // 事件经有界 SharedFlow 的 tryEmit 投递，SPAWNED 可能被丢弃；此时补一条终态记录，
        // 免得「任务跑完但库里没这条」，日后被当成没派发过。仅补缺，不覆盖已有状态。
        agentTaskDao.insertIfAbsent(
            AgentTaskEntity(
                id = event.subSessionId,
                parentSessionId = event.parentSessionId,
                instruction = "",
                status = toStatus.name,
                result = result,
                createdAt = now,
                updatedAt = now,
                finishedAt = now
            )
        )
    }

    /**
     * 中断通知：以系统通知前缀 + USER 角色落库，UI 会渲染成轻量提示条，
     * 主代理回放上下文时能读到「任务被中断、需重新派发或先读现状」。
     */
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
