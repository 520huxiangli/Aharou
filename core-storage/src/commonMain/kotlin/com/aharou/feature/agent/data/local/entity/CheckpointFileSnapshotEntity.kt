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
    /**
     * AI 成功写入该文件后的内容摘要（SHA-256 十六进制）。
     * null 表示快照之后 AI 没写入过，此时无从判断文件是否被检查点之外的操作改过。
     */
    val writtenHash: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
