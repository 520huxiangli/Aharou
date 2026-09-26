package com.aharou.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "checkpoint_file_snapshots",
    indices = [Index(value = ["checkpointId"])]
)
data class CheckpointFileSnapshotEntity(
    @PrimaryKey val id: String,
    val checkpointId: String,
    val filePath: String,
    val snapshotRelativePath: String,
    val changeType: String,
    val createdAt: Long = System.currentTimeMillis()
)
