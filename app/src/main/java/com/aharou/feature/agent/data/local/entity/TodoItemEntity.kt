package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.aharou.feature.agent.domain.model.TodoItem
import com.aharou.feature.agent.domain.model.TodoStatus

@Entity(
    tableName = "todo_items",
    // (sessionId, order) 覆盖 DAO 里「按会话过滤 + 按 order/priority 排序」的查询。
    indices = [Index(value = ["sessionId", "order"])]
)
data class TodoItemEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val subject: String,
    val description: String = "",
    val status: String = "PENDING",
    val priority: Int = 0,
    val order: Int = 0,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun toDomain(): TodoItem = TodoItem(
        id = id,
        sessionId = sessionId,
        subject = subject,
        description = description,
        status = try { TodoStatus.valueOf(status) } catch (_: Exception) { TodoStatus.PENDING },
        priority = priority,
        order = order,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    companion object {
        fun fromDomain(item: TodoItem): TodoItemEntity = TodoItemEntity(
            id = item.id,
            sessionId = item.sessionId,
            subject = item.subject,
            description = item.description,
            status = item.status.name,
            priority = item.priority,
            order = item.order,
            createdAt = item.createdAt,
            updatedAt = item.updatedAt
        )
    }
}
