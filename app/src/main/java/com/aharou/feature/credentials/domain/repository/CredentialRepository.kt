package com.aharou.feature.credentials.domain.repository

import com.aharou.feature.credentials.domain.model.GitCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Git 凭据仓储。凭据按 [GitCredential.host] 匹配注入：push/pull 前解析当前 remote 的 host，
 * 调 [findForHost] 取对应账号的 token。
 */
interface CredentialRepository {
    fun getAll(): Flow<List<GitCredential>>

    /**
     * 凭据文件整份读不到时为 true。此状态下 [getAll] 只会给出空表，[save] / [delete] 直接抛只读保护异常：
     * 读写同一份文件，读不到就写，等于把其余主机的凭据整体抹掉。
     *
     * 依赖它的调用方（如把凭据上传到远程服务器的注入链路）必须先看这里，否则会把空表同步出去。
     */
    val storageReadOnly: StateFlow<Boolean>

    /** 优先默认凭据，无默认回退该 host 任意一条。供注入链路调用。 */
    suspend fun findForHost(host: String): GitCredential?

    suspend fun save(credential: GitCredential)

    suspend fun delete(id: String)
}
