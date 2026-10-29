package com.aharou.feature.agent.domain.schedule

import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import com.aharou.feature.agent.domain.schedule.trigger.TriggerType
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
     * 新建或更新一个任务。周期收敛到下限；新建时下次运行由调度类型决定。
     * 更新时：只改了名称/指令等不影响调度的字段就保留原计划，改了调度方式才重排。
     *
     * 事件类触发（见 [TriggerType.isTimeTrigger]）没有「到点」可言：它的 nextRunAt 只是
     * 「事件已投递进来」的标记，静息时统一停放到 [PARKED_NEXT_RUN_AT]，绝不按时间算——
     * 否则一到点就会被当成时间触发自己跑掉。
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
        scheduleType: String = ScheduledTaskEntity.TYPE_INTERVAL,
        dailyMinuteOfDay: Int = 9 * 60,
        onceAtMillis: Long = 0L,
        triggerType: String = ScheduledTaskEntity.TYPE_TIME,
        triggerPackage: String = "",
        triggerBatteryBelow: Int = -1,
        triggerOnCharging: Boolean = false,
        triggerSsid: String = "",
        conditionWindowFrom: Int = -1,
        conditionWindowTo: Int = -1,
        condRequireCharging: Boolean = false,
        condMinBattery: Int = -1,
        debounceMinutes: Int = 10,
        now: Long = System.currentTimeMillis()
    ): ScheduledTaskEntity {
        val interval = ScheduledTaskScheduler.normalizeInterval(intervalMinutes)
        // 两端都填 00:00 的窗口没有实用价值（任务时刻本身就是 00:00 的话不需要窗口），落库前抹成「不限」，
        // 否则它会变成一道每天只开一分钟的隐形闸门（2026-10-29 群友踩过）。
        val unlimitedWindow = conditionWindowFrom == 0 && conditionWindowTo == 0
        val timeTriggered = TriggerType.isTimeTrigger(triggerType)
        val scheduleChanged = existing == null ||
            existing.triggerType != triggerType ||
            existing.scheduleType != scheduleType ||
            existing.intervalMinutes != interval ||
            existing.dailyMinuteOfDay != dailyMinuteOfDay ||
            existing.onceAtMillis != onceAtMillis
        val task = ScheduledTaskEntity(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name,
            prompt = prompt,
            targetSessionId = targetSessionId,
            workspacePath = workspacePath,
            intervalMinutes = interval,
            scheduleType = scheduleType,
            dailyMinuteOfDay = dailyMinuteOfDay.coerceIn(0, 24 * 60 - 1),
            onceAtMillis = if (scheduleType == ScheduledTaskEntity.TYPE_ONCE) onceAtMillis else 0L,
            triggerType = triggerType,
            triggerPackage = triggerPackage,
            triggerBatteryBelow = triggerBatteryBelow.coerceIn(-1, 100),
            triggerOnCharging = triggerOnCharging,
            triggerSsid = triggerSsid,
            conditionWindowFrom = if (unlimitedWindow) -1 else windowBound(conditionWindowFrom),
            conditionWindowTo = if (unlimitedWindow) -1 else windowBound(conditionWindowTo),
            condRequireCharging = condRequireCharging,
            condMinBattery = condMinBattery.takeIf { it > 0 }?.coerceIn(1, 100) ?: -1,
            debounceMinutes = debounceMinutes.coerceAtLeast(0),
            runMode = existing?.runMode ?: ScheduledTaskEntity.RUN_SINGLE,
            enabled = enabled,
            nextRunAt = when {
                // 事件类：只有「这次投递还没被认领」时才保留原值，改个名字不该把刚发生的事件吃掉。
                !timeTriggered -> existing
                    ?.takeIf { !TriggerType.isTimeTrigger(it) && it.nextRunAt <= now }
                    ?.nextRunAt
                    ?: PARKED_NEXT_RUN_AT
                scheduleChanged -> when (scheduleType) {
                    ScheduledTaskEntity.TYPE_DAILY ->
                        ScheduledTaskScheduler.nextDailyOccurrence(dailyMinuteOfDay, now)
                    ScheduledTaskEntity.TYPE_ONCE -> onceAtMillis
                    else -> ScheduledTaskScheduler.nextRunAfter(now, interval)
                }
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

    /**
     * 启用/停用；重新启用时把时间推到下一个周期，避免立刻补跑一次。
     * 事件类任务没有周期可推：静息值与尚未被认领的那次事件投递都原样保留，只翻启用开关。
     */
    suspend fun setEnabled(id: String, enabled: Boolean, now: Long = System.currentTimeMillis()) {
        val task = dao.getById(id) ?: return
        if (enabled && TriggerType.isTimeTrigger(task) && task.nextRunAt <= now) {
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

    /** 时段边界：-1 表示不限，其余收敛到当天分钟数范围。 */
    private fun windowBound(minuteOfDay: Int): Int =
        if (minuteOfDay < 0) -1 else minuteOfDay.coerceIn(0, 24 * 60 - 1)

    private companion object {
        /** 事件类任务静息时的 nextRunAt：远期值，等下一次事件再来改写。 */
        const val PARKED_NEXT_RUN_AT = Long.MAX_VALUE
    }
}
