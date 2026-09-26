package com.aharou.feature.settings.data

import android.content.Context
import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.local.dao.AIProviderDao
import com.aharou.feature.workspace.data.local.dao.RemoteConnectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 一次性把历史明文的敏感字段加密回写：提供商 API Key（及代理密码、自定义请求头、脚本参数）、
 * 远程连接的密码/passphrase、SSH 私钥文件。这些字段此前为明文存储，改 Keystore 加密后需补齐存量数据。
 *
 * 幂等：已加密的字段跳过，可重复调用。
 */
@Singleton
class SecretEncryptionMigrator @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val aiProviderDao: AIProviderDao,
    private val remoteConnectionDao: RemoteConnectionDao
) {
    private companion object {
        const val TAG = "SecretEncryptionMigrator"
    }

    suspend fun migrateIfNeeded() = withContext(Dispatchers.IO) {
        runCatching { migrateProviders() }.onFailure { FileLogger.w(TAG, "迁移 provider 敏感字段失败", it) }
        runCatching { migrateConnections() }.onFailure { FileLogger.w(TAG, "迁移连接敏感字段失败", it) }
        runCatching { migrateSshKeys() }.onFailure { FileLogger.w(TAG, "迁移 SSH 私钥文件失败", it) }
    }

    /** 已加密则原样返回，未加密则加密（幂等）。 */
    private fun enc(value: String): String =
        if (KeystoreCipher.isEncryptedString(value)) value else KeystoreCipher.encryptString(value)

    private suspend fun migrateProviders() {
        val providers = aiProviderDao.getAllProvidersOnce()
        val migrated = providers.map { p ->
            p.copy(
                apiKey = enc(p.apiKey),
                apiKeys = enc(p.apiKeys),
                proxyPassword = enc(p.proxyPassword),
                customHeaders = enc(p.customHeaders),
                scriptParams = enc(p.scriptParams)
            )
        }
        if (migrated != providers) {
            aiProviderDao.insertAllProviders(migrated)
            FileLogger.i(TAG, "已加密 ${providers.size} 个 provider 的敏感字段")
        }
    }

    private suspend fun migrateConnections() {
        val connections = remoteConnectionDao.getAllConnectionsOnce()
        val migrated = connections.map { c ->
            c.copy(
                authData = if (c.authType.equals("PASSWORD", ignoreCase = true)) enc(c.authData) else c.authData,
                passphrase = c.passphrase?.let { enc(it) }
            )
        }
        if (migrated != connections) {
            remoteConnectionDao.insertAllConnections(migrated)
            FileLogger.i(TAG, "已加密 ${connections.size} 个连接的敏感字段")
        }
    }

    private fun migrateSshKeys() {
        val files = File(context.filesDir, "ssh_keys").listFiles() ?: return
        var count = 0
        files.forEach { file ->
            if (!file.isFile) return@forEach
            val bytes = runCatching { file.readBytes() }.getOrNull() ?: return@forEach
            if (!KeystoreCipher.isEncryptedBytes(bytes)) {
                runCatching { file.writeBytes(KeystoreCipher.encryptBytes(bytes)) }
                    .onSuccess { count++ }
                    .onFailure { FileLogger.w(TAG, "加密私钥文件失败: ${file.name}", it) }
            }
        }
        if (count > 0) FileLogger.i(TAG, "已加密 $count 个 SSH 私钥文件")
    }
}
