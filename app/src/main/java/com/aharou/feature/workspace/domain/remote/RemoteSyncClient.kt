package com.aharou.feature.workspace.domain.remote

class RemoteFileRejectedException(message: String) : Exception(message)

interface RemoteSyncClient {
    suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth)
    suspend fun disconnect()
    suspend fun listFiles(remotePath: String): List<RemoteFileInfo>
    suspend fun downloadFile(remotePath: String, localPath: String)
    suspend fun uploadFile(localPath: String, remotePath: String)
    suspend fun createDirectory(remotePath: String)
    suspend fun delete(remotePath: String)
    suspend fun isConnected(): Boolean

    /** 原子重连：断开旧连接并重新建立。供同步引擎的探活/失败重连使用，
     *  避免 disconnect 与 connect 之间被其它协程插入到半连接状态上操作。 */
    suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth)

    /** 轻量探活：连接是否仍然可用。默认复用 isConnected 标志，网络协议可覆盖为真实往返（如 SFTP stat / FTP NOOP），
     *  以便发现 TCP 半开等 isConnected 检测不到的断连。 */
    suspend fun ping(): Boolean = isConnected()
}

data class RemoteFileInfo(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)

sealed class RemoteAuth {
    data class Password(val password: String): RemoteAuth()
    data class PrivateKey(val privateKeyPath: String, val passphrase: String? = null): RemoteAuth()
}
