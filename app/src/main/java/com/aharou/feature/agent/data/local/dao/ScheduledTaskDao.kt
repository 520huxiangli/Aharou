package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
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

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :id")
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
        "UPDATE scheduled_tasks SET nextRunAt = :nextRunAt, lastRunAt = :now, runCount = runCount + 1, " +
            "updatedAt = :now WHERE id = :id AND nextRunAt = :expectedNextRunAt"
    )
    suspend fun claim(id: String, expectedNextRunAt: Long, nextRunAt: Long, now: Long): Int

    /** 记下这次运行的结果（时间已由 [claim] 推进）。 */
    @Query("UPDATE scheduled_tasks SET lastOutcome = :outcome, updatedAt = :now WHERE id = :id")
    suspend fun recordOutcome(id: String, outcome: String, now: Long)
}
