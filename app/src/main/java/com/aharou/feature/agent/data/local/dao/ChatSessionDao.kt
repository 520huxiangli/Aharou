package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getRootSessionsByWorkspace(workspacePath: String): Flow<List<ChatSessionEntity>>

    /** 全量根会话（跨工作区）。侧边栏按 workspacePath 分组渲染成文件夹，需要一次拿到所有工作区的会话。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllRootSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getAllSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY isPinned DESC, updatedAt DESC")
    suspend fun getRootSessionsByWorkspaceOnce(workspacePath: String): List<ChatSessionEntity>

    /** 最近更新的根会话（忽略置顶，仅按 updatedAt 降序），供启动时「打开最近会话」。 */
    @Query("SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getLatestRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?

    /** 指定父会话的全部子会话（子代理），按最近更新降序。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    fun getSubSessionsByParent(parentId: String): Flow<List<ChatSessionEntity>>

    /** 指定父会话的全部子会话（子代理），一次性查询。 */
    @Query("SELECT * FROM chat_sessions WHERE parentId = :parentId ORDER BY updatedAt DESC")
    suspend fun getSubSessionsByParentOnce(parentId: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions")
    suspend fun getAllOnce(): List<ChatSessionEntity>

    /** 分页读取（keyset：按 updatedAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM chat_sessions WHERE updatedAt > :lastUpdatedAt OR (updatedAt = :lastUpdatedAt AND id > :lastId) ORDER BY updatedAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastUpdatedAt: Long, lastId: String, limit: Int): List<ChatSessionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(sessions: List<ChatSessionEntity>)

    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    suspend fun getById(id: String): ChatSessionEntity?

    /** 单个会话的实时流（根会话与子会话通用）。 */
    @Query("SELECT * FROM chat_sessions WHERE id = :id")
    fun getByIdFlow(id: String): Flow<ChatSessionEntity?>

    @Query("UPDATE chat_sessions SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: String, title: String)

    @Query("UPDATE chat_sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long)

    @Query("UPDATE chat_sessions SET isPinned = :pinned WHERE id = :id")
    suspend fun updatePinned(id: String, pinned: Boolean)

    /** 切换会话级隐身模式。 */
    @Query("UPDATE chat_sessions SET ghostMode = :ghost WHERE id = :id")
    suspend fun updateGhostMode(id: String, ghost: Boolean)

    @Query("DELETE FROM chat_sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM chat_sessions WHERE workspacePath = :workspacePath")
    suspend fun deleteByWorkspace(workspacePath: String)

    /** 工作区改名后批量迁移会话归属，返回受影响的行数。 */
    @Query("UPDATE chat_sessions SET workspacePath = :newPath WHERE workspacePath = :oldPath")
    suspend fun updateWorkspacePath(oldPath: String, newPath: String): Int

    @Query("UPDATE chat_sessions SET providerId = :providerId, model = :model, lastInputTokens = 0 WHERE id = :id")
    suspend fun updateProviderModel(id: String, providerId: String?, model: String?)

    @Query("UPDATE chat_sessions SET reasoningEffort = :effort WHERE id = :id")
    suspend fun updateReasoningEffort(id: String, effort: String)

    @Query("UPDATE chat_sessions SET totalInputTokens = totalInputTokens + :inputTokens, totalOutputTokens = totalOutputTokens + :outputTokens WHERE id = :id")
    suspend fun addTokenUsage(id: String, inputTokens: Int, outputTokens: Int)

    @Query("UPDATE chat_sessions SET lastInputTokens = :lastInputTokens WHERE id = :id")
    suspend fun updateLastInputTokens(id: String, lastInputTokens: Int)

    /** 设定/修改目标：同时重置轮数与 token 基线。 */
    @Query("UPDATE chat_sessions SET goal = :goal, goalStatus = :status, goalTurnCount = 0, goalStartInputTokens = :startInput, goalStartOutputTokens = :startOutput WHERE id = :id")
    suspend fun updateGoal(id: String, goal: String?, status: String?, startInput: Int, startOutput: Int)

    @Query("UPDATE chat_sessions SET goalStatus = :status WHERE id = :id")
    suspend fun updateGoalStatus(id: String, status: String?)

    @Query("UPDATE chat_sessions SET goalTurnCount = goalTurnCount + 1 WHERE id = :id")
    suspend fun incrementGoalTurn(id: String)

    /**
     * 工作区里最近更新、且一条消息都还没有的根会话（排除隐身会话）。
     *
     * 用子查询代替「取全部根会话再逐条 hasMessages」：后者是 O(会话数) 次查库，
     * 会话多了会明显拖慢「新建会话」这个高频操作。
     */
    @Query(
        "SELECT * FROM chat_sessions WHERE workspacePath = :workspacePath AND parentId IS NULL " +
            "AND ghostMode = 0 AND id NOT IN (SELECT DISTINCT sessionId FROM agent_messages) " +
            "ORDER BY updatedAt DESC LIMIT 1"
    )
    suspend fun getEmptyRootSessionByWorkspace(workspacePath: String): ChatSessionEntity?
}
