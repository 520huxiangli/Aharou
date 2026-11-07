package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    /** 一次性读取：配置通道等非流式消费方用。 */
    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt ASC")
    suspend fun getAllOnce(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Transaction
    suspend fun upsertPreservingPending(task: ScheduledTaskEntity, preserveNextRunAt: Boolean = true) {
        val current = getById(task.id)
        val sameSchedule = current != null && current.triggerType == task.triggerType &&
            current.scheduleType == task.scheduleType && current.intervalMinutes == task.intervalMinutes &&
            current.dailyMinuteOfDay == task.dailyMinuteOfDay && current.onceAtMillis == task.onceAtMillis
        upsert(task.copy(
            pendingRunPrompt = if (task.enabled) current?.pendingRunPrompt else null,
            lastRunAt = current?.lastRunAt,
            lastOutcome = current?.lastOutcome,
            runCount = current?.runCount ?: 0,
            nextRunAt = if (preserveNextRunAt && sameSchedule) current!!.nextRunAt else task.nextRunAt,
        ))
    }

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        "UPDATE scheduled_tasks SET enabled = :enabled, " +
            "pendingRunPrompt = CASE WHEN :enabled = 0 THEN NULL ELSE pendingRunPrompt END, " +
            "updatedAt = :updatedAt WHERE id = :id"
    )
    suspend fun setEnabled(id: String, enabled: Boolean, updatedAt: Long)

    /** 到点、未停用、且未超运行上限的任务。 */
    @Query(
        "SELECT * FROM scheduled_tasks WHERE enabled = 1 AND nextRunAt <= :now " +
            "AND (maxRuns = 0 OR runCount < maxRuns) ORDER BY nextRunAt ASC"
    )
    suspend fun due(now: Long): List<ScheduledTaskEntity>

    /** 启用中的任务数：为 0 时没必要让 WorkManager 周期唤醒。 */
    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE enabled = 1")
    suspend fun countEnabled(): Int

    /** 启用中的某类触发任务：事件总线按类型取候选（时间类不走这条路）。 */
    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1 AND triggerType = :type ORDER BY createdAt ASC")
    suspend fun enabledByTriggerType(type: String): List<ScheduledTaskEntity>

    /**
     * 事件投递：只把 nextRunAt 拨到事件时刻，不动别的列。
     * 不复用 upsert 整行回写——事件总线读到任务行之后，设置页改名或调度器可能已经改过同一行，
     * 整行回写会把那些改动一并吃掉。返回 0 表示任务已不存在。
     */
    @Query("UPDATE scheduled_tasks SET nextRunAt = :nextRunAt, updatedAt = :now WHERE id = :id")
    suspend fun armEvent(id: String, nextRunAt: Long, now: Long): Int

    /**
     * 认领一次运行：仅当 [nextRunAt] 仍是调用方读到的 [expectedNextRunAt] 时才推进时间并计数，
     * 返回受影响行数；0 表示已被别的路径认领，本次放弃——避免同一个到点时刻跑两遍。
     */
    @Query(
        "UPDATE scheduled_tasks SET nextRunAt = :nextRunAt, lastRunAt = :now, lastOutcome = 'dispatched', " +
            "runCount = runCount + 1, " +
            "updatedAt = :now WHERE id = :id AND nextRunAt = :expectedNextRunAt AND enabled = 1 " +
            "AND (maxRuns = 0 OR runCount < maxRuns)"
    )
    suspend fun claim(id: String, expectedNextRunAt: Long, nextRunAt: Long, now: Long): Int

    /** 记下这次运行的结果（时间已由 [claim] 推进）。 */
    @Query("UPDATE scheduled_tasks SET lastOutcome = :outcome, updatedAt = :now WHERE id = :id")
    suspend fun recordOutcome(id: String, outcome: String, now: Long)

    @Query(
        "UPDATE scheduled_tasks SET lastOutcome = :outcome, updatedAt = :now " +
            "WHERE id = :id AND lastRunAt IS :claimedAt"
    )
    suspend fun recordOutcomeForRun(id: String, claimedAt: Long?, outcome: String, now: Long): Int

    @Query("UPDATE scheduled_tasks SET enabled = 0, updatedAt = :now WHERE id = :id AND lastRunAt = :now")
    suspend fun disableClaimedOnce(id: String, now: Long): Int

    /** 服务投递失败时原子落库并停用单次任务；用户已停用时不再挂起。 */
    @Query(
        "UPDATE scheduled_tasks SET pendingRunPrompt = :prompt, lastOutcome = 'dispatched', " +
            "enabled = CASE WHEN :disableOnce THEN 0 ELSE enabled END, updatedAt = :now " +
            "WHERE id = :id AND enabled = 1 AND lastRunAt = :now"
    )
    suspend fun markPendingRun(id: String, prompt: String, now: Long, disableOnce: Boolean = false): Int

    /** 已挂起、还没被捡走的任务（会话层启动时扫一遍）。 */
    @Query(
        "SELECT * FROM scheduled_tasks WHERE pendingRunPrompt IS NOT NULL AND pendingRunPrompt != '' " +
            "ORDER BY updatedAt ASC"
    )
    suspend fun pendingRuns(): List<ScheduledTaskEntity>

    /** 捡走：清掉挂起标记，避免下次启动再跑一遍。 */
    @Query(
        "UPDATE scheduled_tasks SET pendingRunPrompt = NULL, updatedAt = :now " +
            "WHERE id = :id AND pendingRunPrompt = :expectedPrompt AND lastRunAt IS :claimedAt"
    )
    suspend fun clearPendingRun(id: String, expectedPrompt: String, claimedAt: Long?, now: Long): Int

    /** 还有多少挂起任务（日志观测用）。 */
    @Query("SELECT COUNT(*) FROM scheduled_tasks WHERE pendingRunPrompt IS NOT NULL AND pendingRunPrompt != ''")
    suspend fun pendingRunsCount(): Int
}
