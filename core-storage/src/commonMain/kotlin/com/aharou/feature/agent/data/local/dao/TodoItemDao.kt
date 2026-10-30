package com.aharou.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aharou.feature.agent.data.local.entity.TodoItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoItemDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TodoItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TodoItemEntity>)

    @Query("SELECT * FROM todo_items WHERE sessionId = :sessionId ORDER BY `order` ASC, priority DESC")
    fun getBySession(sessionId: String): Flow<List<TodoItemEntity>>

    @Query("SELECT * FROM todo_items WHERE sessionId = :sessionId ORDER BY `order` ASC, priority DESC")
    suspend fun getBySessionOnce(sessionId: String): List<TodoItemEntity>

    @Query("SELECT * FROM todo_items")
    suspend fun getAllOnce(): List<TodoItemEntity>

    /** 分页读取（keyset：按 createdAt,id 字典序取 [limit] 条），供备份流式导出。 */
    @Query("SELECT * FROM todo_items WHERE createdAt > :lastCreatedAt OR (createdAt = :lastCreatedAt AND id > :lastId) ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getPageAfter(lastCreatedAt: Long, lastId: String, limit: Int): List<TodoItemEntity>

    /** 按会话分页读取（keyset），供单会话备份流式导出。 */
    @Query("SELECT * FROM todo_items WHERE sessionId = :sessionId AND (createdAt > :lastCreatedAt OR (createdAt = :lastCreatedAt AND id > :lastId)) ORDER BY createdAt ASC, id ASC LIMIT :limit")
    suspend fun getBySessionPageAfter(sessionId: String, lastCreatedAt: Long, lastId: String, limit: Int): List<TodoItemEntity>

    @Query("DELETE FROM todo_items WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM todo_items WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("SELECT MAX(`order`) FROM todo_items WHERE sessionId = :sessionId")
    suspend fun getMaxOrder(sessionId: String): Int?
}
