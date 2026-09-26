package com.aharou.feature.workspace.domain.remote.sftp

import com.aharou.feature.agent.domain.container.SshHostKeyVerifier
import com.aharou.feature.agent.domain.container.SshPrivateKeyStore
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.workspace.domain.remote.RemoteFileInfo
import com.aharou.feature.workspace.domain.remote.RemoteSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.File

class SftpSyncClient(
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore
) : RemoteSyncClient {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000L
    }

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    override suspend fun connect(host: String, port: Int, username: String, auth: RemoteAuth) = withContext(Dispatchers.IO) {
        sshClient = SSHClient().apply {
            setConnectTimeout(CONNECT_TIMEOUT_MS.toInt())
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
        sftpClient = sshClient?.newSFTPClient()
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        sftpClient?.close()
        sshClient?.disconnect()
        sftpClient = null
        sshClient = null
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
        sftp.ls(remotePath).map {
            RemoteFileInfo(
                name = it.name,
                isDirectory = it.attributes.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                size = it.attributes.size,
                lastModified = it.attributes.mtime * 1000L // mtime is in seconds
            )
        }
    }

    override suspend fun downloadFile(remotePath: String, localPath: String) = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
        val localFile = File(localPath)
        localFile.parentFile?.mkdirs()
        sftp.get(remotePath, localPath)
    }

    override suspend fun uploadFile(localPath: String, remotePath: String) = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
        val localFile = File(localPath)
        if (localFile.exists()) {
            sftp.put(localPath, remotePath)
        }
    }

    override suspend fun createDirectory(remotePath: String) = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
        sftp.mkdirs(remotePath)
    }

    override suspend fun delete(remotePath: String) = withContext(Dispatchers.IO) {
        val sftp = sftpClient ?: throw IllegalStateException("SFTP Client is not connected")
        val attrs = sftp.statExistence(remotePath)
        if (attrs != null) {
            if (attrs.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY) {
                // 递归删除暂未实现
                sftp.rmdir(remotePath)
            } else {
                sftp.rm(remotePath)
            }
        }
    }

    override suspend fun isConnected(): Boolean = sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    override suspend fun ping(): Boolean = withContext(Dispatchers.IO) {
        val ssh = sshClient ?: return@withContext false
        val sftp = sftpClient ?: return@withContext false
        if (!ssh.isConnected || !ssh.isAuthenticated) return@withContext false
        try {
            // 任何 SFTP 协议响应（含"路径不存在"等业务错误）都证明连接活着；仅传输/socket 异常才判断开
            sftp.stat(".")
            true
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            true
        } catch (e: Exception) {
            false
        }
    }
}
