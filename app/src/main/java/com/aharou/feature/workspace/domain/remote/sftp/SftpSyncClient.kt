package com.aharou.feature.workspace.domain.remote.sftp

import com.aharou.feature.agent.domain.container.SshHostKeyVerifier
import com.aharou.feature.agent.domain.container.SshPrivateKeyStore
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.workspace.domain.remote.RemoteFileInfo
import com.aharou.feature.workspace.domain.remote.RemoteSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File

class SftpSyncClient(
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore
) : RemoteSyncClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }

    /** 同一 SFTP 会话上的并发请求会让请求/响应错配，故所有操作串行化。 */
    private val mutex = Mutex()
    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) =
        withContext(Dispatchers.IO) { mutex.withLock { doConnect(host, port, username, auth) } }

    override suspend fun disconnect() =
        withContext(Dispatchers.IO) { mutex.withLock { doDisconnect() } }

    override suspend fun reconnect(host: String, port: Int, username: String, auth: RemoteAuth) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                doDisconnect()
                doConnect(host, port, username, auth)
            }
        }

    private fun doConnect(host: String, port: Int, username: String, auth: RemoteAuth) {
        val ssh = SSHClient()
        try {
            ssh.apply {
                setConnectTimeout(CONNECT_TIMEOUT_MS)
                setTimeout(READ_TIMEOUT_MS)
                addHostKeyVerifier(hostKeyVerifier)
                connect(host, port)

                when (auth) {
                    is RemoteAuth.Password -> authPassword(username, auth.password)
                    is RemoteAuth.PrivateKey -> {
                        val pem = privateKeyStore.readPem(auth.privateKeyPath)
                        val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                        authPublickey(username, loadKeys(pem, null, passwordFinder))
                    }
                }
            }
            val sftp = ssh.newSFTPClient()
            // 成功后才落字段；失败路径字段保持 null，由本方法的 catch 负责关连接
            sshClient = ssh
            sftpClient = sftp
        } catch (e: Exception) {
            // 认证或建 SFTP 会话失败时字段尚未赋值，无人会关这个已建立 TCP 的 ssh 实例，就地关闭
            runCatching { ssh.disconnect() }
            throw e
        }
    }

    private fun doDisconnect() {
        runCatching { sftpClient?.close() }
        runCatching { sshClient?.disconnect() }
        sftpClient = null
        sshClient = null
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
                sftp.ls(remotePath).map {
                    RemoteFileInfo(
                        name = it.name,
                        isDirectory = it.attributes.type == FileMode.Type.DIRECTORY,
                        size = it.attributes.size,
                        lastModified = it.attributes.mtime * 1000L // mtime is in seconds
                    )
                }
            }
        }

    override suspend fun downloadFile(remotePath: String, localPath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
                File(localPath).parentFile?.mkdirs()
                sftp.get(remotePath, localPath)
            }
        }

    override suspend fun uploadFile(localPath: String, remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
                if (File(localPath).exists()) {
                    sftp.put(localPath, remotePath)
                }
            }
        }

    override suspend fun createDirectory(remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
                sftp.mkdirs(remotePath)
            }
        }

    override suspend fun delete(remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
                val attrs = sftp.statExistence(remotePath)
                if (attrs != null) {
                    deleteRecursive(sftp, remotePath, attrs.type)
                }
            }
        }

    /** 递归删除（SFTP 无递归删除原语）：后序遍历，目录在子项删完后 rmdir。 */
    private fun deleteRecursive(
        sftp: SFTPClient,
        path: String,
        type: FileMode.Type
    ) {
        if (type == FileMode.Type.DIRECTORY) {
            for (entry in sftp.ls(path)) {
                val name = entry.name
                if (name == "." || name == "..") continue
                deleteRecursive(sftp, entry.path, entry.attributes.type)
            }
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    override suspend fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    override suspend fun ping(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val ssh = sshClient ?: return@withLock false
                val sftp = sftpClient ?: return@withLock false
                if (!ssh.isConnected || !ssh.isAuthenticated) return@withLock false
                try {
                    // 任何 SFTP 协议响应（含"路径不存在"等业务错误）都证明连接活着；仅传输/socket 异常才判断开
                    sftp.stat(".")
                    true
                } catch (e: SFTPException) {
                    true
                } catch (e: Exception) {
                    false
                }
            }
        }
}
