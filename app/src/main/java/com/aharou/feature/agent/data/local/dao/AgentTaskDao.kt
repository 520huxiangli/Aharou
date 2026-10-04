package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.AgentTaskEntity

@Dao
interface AgentTaskDao {

    /** 登记新任务；同一子会话重复派发时保留首条。返回新插入行的 rowid，已存在返回 -1。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(task: AgentTaskEntity): Long

    /**
     * 状态迁移（compare-and-set）：仅当当前状态恰为 [fromStatus] 时才写入，
     * 返回受影响行数；0 表示状态已被别的路径改写，本次迁移放弃，避免并发写互相覆盖。
     */
    @Query(
        "UPDATE agent_tasks SET status = :toStatus, result = :result, updatedAt = :updatedAt, finishedAt = :finishedAt " +
            "WHERE id = :taskId AND status = :fromStatus"
    )
    suspend fun compareAndSetStatus(
        taskId: String,
        fromStatus: String,
        toStatus: String,
        result: String?,
        updatedAt: Long,
        finishedAt: Long?
    ): Int

    @Query("SELECT * FROM agent_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: String): AgentTaskEntity?

    @Query("SELECT * FROM agent_tasks WHERE status = :status ORDER BY createdAt ASC")
    suspend fun getByStatus(status: String): List<AgentTaskEntity>
}
