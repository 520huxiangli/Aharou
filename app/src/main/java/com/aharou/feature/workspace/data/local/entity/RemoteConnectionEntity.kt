package com.aharou.feature.workspace.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.aharou.feature.workspace.domain.model.RemoteProtocol
import java.util.UUID

@Entity(tableName = "remote_connections")
data class RemoteConnectionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val authType: String = "password", // 'password' or 'key'
    val authData: String, // password or private key path
    val passphrase: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long = 0,
    /** 用户拖拽排序的显式顺序，越小越靠前；新建时分配 max+1，存量行默认 0 由 created_at 兜底。 */
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)
