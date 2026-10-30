package com.aharou.feature.agent.domain.tool.question

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** 待答问题的存取（表结构见 [PendingUserQuestionEntity]）。 */
@Dao
interface PendingUserQuestionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PendingUserQuestionEntity)

    /** 只取最近一条 PENDING：同一时刻面板最多一个问题，旧问题由恢复逻辑判失效。 */
    @Query("SELECT * FROM pending_user_questions WHERE status = 'PENDING' ORDER BY createdAt DESC LIMIT 1")
    suspend fun latestPending(): PendingUserQuestionEntity?

    @Query(
        "UPDATE pending_user_questions SET status = :status, updatedAt = :updatedAt, " +
            "answeredAt = :answeredAt, answerJson = :answerJson, resolveReason = :reason WHERE id = :id"
    )
    suspend fun markResolved(
        id: String,
        status: String,
        updatedAt: Long,
        answeredAt: Long?,
        answerJson: String?,
        reason: String?
    )

    /** 清掉过期的终态行，避免表随提问次数无限增长。 */
    @Query("DELETE FROM pending_user_questions WHERE status != 'PENDING' AND updatedAt < :before")
    suspend fun deleteResolvedBefore(before: Long)

    /** 已作答但还没送回会话的行：进程死掉时 agent 拿不到的那部分答案。 */
    @Query(
        "SELECT * FROM pending_user_questions WHERE sessionId = :sessionId AND status = :status " +
            "AND answerJson IS NOT NULL ORDER BY answeredAt ASC"
    )
    suspend fun answeredForSession(sessionId: String, status: String): List<PendingUserQuestionEntity>

    @Query("DELETE FROM pending_user_questions WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)
}
