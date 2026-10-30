package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "session_checkpoints",
    indices = [Index(value = ["sessionId"])]
)
data class CheckpointEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val userMessageId: String,
    val promptSnippet: String,
    val createdAt: Long = System.currentTimeMillis()
)
