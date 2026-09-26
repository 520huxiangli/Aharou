package com.aharou.feature.credentials.domain.repository

import com.aharou.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.flow.Flow

/**
 * Git 凭据仓储。凭据按 [GitCredential.host] 匹配注入：push/pull 前解析当前 remote 的 host，
 * 调 [findForHost] 取对应账号的 token。
 */
interface CredentialRepository {
    fun getAll(): Flow<List<GitCredential>>

    /** 优先默认凭据，无默认回退该 host 任意一条。供注入链路调用。 */
    suspend fun findForHost(host: String): GitCredential?

    suspend fun save(credential: GitCredential)

    suspend fun delete(id: String)
}
