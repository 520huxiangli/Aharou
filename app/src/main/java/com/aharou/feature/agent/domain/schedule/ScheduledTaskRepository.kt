package com.aharou.feature.agent.domain.schedule

import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * 定时任务的读写入口（设置页与调度层共用）。
 *
 * 两条不变量在这里收口：周期不得低于 WorkManager 的下限；停用再启用时把下次运行时间推到
 * 「从现在起一个周期」，否则一启用就会因为 nextRunAt 早已过期而被当成错过。
 */
@Singleton
class ScheduledTaskRepository @Inject constructor(
    private val dao: ScheduledTaskDao
) {

    fun observeAll(): Flow<List<ScheduledTaskEntity>> = dao.observeAll()

    /** 一次性读取（配置通道等非流式消费方用）。 */
    suspend fun all(): List<ScheduledTaskEntity> = dao.getAllOnce()

    suspend fun get(id: String): ScheduledTaskEntity? = dao.getById(id)

    suspend fun upsert(task: ScheduledTaskEntity) = dao.upsert(task)

    suspend fun delete(id: String) = dao.deleteById(id)

    /**
     * 新建或更新一个任务。周期收敛到下限；新建时下次运行 = 现在 + 一个周期。
     * 更新时：改了周期就按新周期重排下次运行；只改名称/指令等不影响调度，保留原计划。
     */
    suspend fun save(
        existing: ScheduledTaskEntity?,
        name: String,
        prompt: String,
        targetSessionId: String?,
        workspacePath: String?,
        intervalMinutes: Int,
        maxRuns: Int,
        enabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): ScheduledTaskEntity {
        val interval = ScheduledTaskScheduler.normalizeInterval(intervalMinutes)
        val intervalChanged = existing == null || existing.intervalMinutes != interval
        val task = ScheduledTaskEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
            targetSessionId = targetSessionId,
            workspacePath = workspacePath,
            intervalMinutes = interval,
            enabled = enabled,
            nextRunAt = when {
                existing == null -> ScheduledTaskScheduler.nextRunAfter(now, interval)
                intervalChanged -> ScheduledTaskScheduler.nextRunAfter(now, interval)
                else -> existing.nextRunAt
            },
            lastRunAt = existing?.lastRunAt,
            lastOutcome = existing?.lastOutcome,
            runCount = existing?.runCount ?: 0,
            maxRuns = maxRuns,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
        dao.upsert(task)
        return task
    }

    /** 启用/停用；重新启用时把时间推到下一个周期，避免立刻补跑一次。 */
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val task = dao.getById(id) ?: return
        if (enabled && task.nextRunAt <= now) {
            dao.upsert(
                task.copy(
                    enabled = true,
                    nextRunAt = ScheduledTaskScheduler.nextRunAfter(now, task.intervalMinutes),
                    updatedAt = now
                )
            )
        } else {
            dao.setEnabled(id, enabled, now)
        }
    }
}
