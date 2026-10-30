package com.aharou.feature.backup.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 网络备份目标（WebDAV / S3）持久化行。
 *
 * [type] 存 [com.aharou.feature.backup.domain.remote.BackupTargetType] 的枚举名（参照 RemoteConnectionEntity 的枚举直存先例）。
 * [password] / [secretKey] / [sessionToken] 是敏感字段，落库前由 KeystoreCipher 加密（见 BackupTargetRepository）。
 */
@Entity(tableName = "backup_targets")
data class BackupTargetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val endpoint: String = "",
    val basePath: String = "",
    val username: String = "",
    val password: String = "",
    val accessKey: String = "",
    val secretKey: String = "",
    val sessionToken: String = "",
    val bucket: String = "",
    val region: String = "us-east-1",
    val pathStyle: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0
)
