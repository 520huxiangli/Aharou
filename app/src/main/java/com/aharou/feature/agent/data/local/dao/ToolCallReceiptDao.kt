package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ToolCallReceiptDao {

    @Insert
    suspend fun insert(receipt: ToolCallReceiptEntity): Long

    /** 会话活动面板的数据源：[limit] 只取最近的条目，越靠前越新。 */
    @Query(
        "SELECT * FROM tool_call_receipts WHERE sessionId = :sessionId " +
            "ORDER BY createdAt DESC, id DESC LIMIT :limit"
    )
    fun observeSession(sessionId: String, limit: Int): Flow<List<ToolCallReceiptEntity>>

    /** 保留窗口外的旧回执由调用方按时间清掉，避免表无限增长。 */
    @Query("DELETE FROM tool_call_receipts WHERE createdAt < :before")
    suspend fun deleteOlderThan(before: Long): Int

    @Query("DELETE FROM tool_call_receipts WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String): Int
}
