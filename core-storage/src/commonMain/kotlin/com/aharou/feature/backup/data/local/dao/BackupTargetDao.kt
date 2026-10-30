package com.aharou.feature.backup.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aharou.feature.backup.data.local.entity.BackupTargetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackupTargetDao {
    @Query("SELECT * FROM backup_targets ORDER BY created_at ASC, rowid ASC")
    fun getAll(): Flow<List<BackupTargetEntity>>

    @Query("SELECT * FROM backup_targets WHERE id = :id")
    suspend fun getById(id: String): BackupTargetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(target: BackupTargetEntity)

    @Query("DELETE FROM backup_targets WHERE id = :id")
    suspend fun deleteById(id: String)
}
