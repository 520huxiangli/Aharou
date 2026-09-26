package com.aharou.feature.credentials.data

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.credentials.data.repository.FileCredentialRepository
import com.aharou.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性把旧 Room `git_credentials` 表迁移到凭据文件，然后删表。
 *
 * 背景：凭证真源从 Room 迁到 `git-credentials` 文件（每主机一条）。Room 实体从 [AgentDatabase] 移除后
 * 旧表仍残留在数据库文件里（Room 不会自动删未管理表），由本类在 App 启动时读取 → 写入文件 → DROP。
 * 表不存在或已迁移过时直接跳过（幂等）。
 */
@Singleton
class LegacyCredentialMigrator @Inject constructor(
    private val database: AgentDatabase,
    private val fileCredentialRepository: FileCredentialRepository
) {
    private companion object {
        const val TAG = "LegacyCredentialMigrator"
        const val TABLE = "git_credentials"
    }

    /** 读旧表（每 host 取默认/首条）写入凭据文件后删表。可重复调用，表不存在/已删时幂等跳过。 */
    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val db = database.openHelper.writableDatabase
            // SupportSQLiteDatabase 无 rawQuery，用 query 执行原生 SQL；返回的 Cursor 非 AutoCloseable，手动 close。
            var hasTable = false
            val tableCursor = db.query(
                "SELECT name FROM sqlite_master WHERE type='table' AND name='$TABLE'", emptyArray()
            )
            try {
                hasTable = tableCursor.moveToFirst()
            } finally {
                tableCursor.close()
            }
            if (!hasTable) return@withContext
            val rowsCursor = db.query(
                "SELECT host, username, token, isDefault FROM $TABLE ORDER BY isDefault DESC", emptyArray()
            )
            val rows = mutableListOf<GitCredential>()
            try {
                while (rowsCursor.moveToNext()) {
                    val host = rowsCursor.getString(0)?.lowercase() ?: continue
                    val username = rowsCursor.getString(1) ?: continue
                    val token = rowsCursor.getString(2) ?: continue
                    if (host.isBlank() || username.isBlank()) continue
                    // 每 host 只取第一条（默认优先，因 ORDER BY isDefault DESC）
                    if (rows.any { it.host == host }) continue
                    rows.add(GitCredential(id = host, host = host, username = username, token = token))
                }
            } finally {
                rowsCursor.close()
            }
            if (rows.isNotEmpty()) {
                rows.forEach { fileCredentialRepository.save(it) }
                FileLogger.i(TAG, "已从旧表迁移 ${rows.size} 条 git 凭据到文件")
            }
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            FileLogger.i(TAG, "已删除旧表 $TABLE")
        } catch (e: Exception) {
            FileLogger.w(TAG, "迁移旧 git 凭据失败（可忽略，文件为空时用户重录）: ${e.message}")
        }
    }
}
