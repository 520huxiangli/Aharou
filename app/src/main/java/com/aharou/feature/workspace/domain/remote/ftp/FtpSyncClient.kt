package com.aharou.feature.workspace.domain.remote.ftp

import com.aharou.core.util.FileLogger
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.workspace.domain.remote.RemoteFileInfo
import com.aharou.feature.workspace.domain.remote.RemoteFileRejectedException
import com.aharou.feature.workspace.domain.remote.RemoteSyncClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.time.Duration

class FtpSyncClient : RemoteSyncClient {

    companion object {
        private const val TAG = "FtpSyncClient"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val CONTROL_READ_TIMEOUT_MS = 15_000
        private const val DATA_TIMEOUT_MS = 30_000
        // 内置 FTP 服务器实测空闲 ~30s 即关控制连接，保活间隔必须显著小于它。
        private const val CONTROL_KEEPALIVE_SECONDS = 15L
        private const val CONTROL_KEEPALIVE_REPLY_SECONDS = 10L
    }

    private var ftpClient: FTPClient = FTPClient()

    /** FTPClient 非线程安全：同一实例上的并发命令会让控制连接回复串台，故所有操作串行化。 */
    private val mutex = Mutex()
    private var isConnected = false

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
        if (auth !is RemoteAuth.Password) {
            throw IllegalArgumentException("FTP only supports Password authentication")
        }

        // 先试明文；FileZilla Server 1.x 等默认强制 TLS 的服务器会拒掉明文登录，
        // 再改用 explicit TLS 重连一次，用户不必自己分辨服务器要不要加密。
        val plainError = runCatching {
            ftpClient = openClient(tls = false, host = host, port = port, username = username, password = auth.password)
        }.exceptionOrNull() ?: return
        runCatching {
            ftpClient = openClient(tls = true, host = host, port = port, username = username, password = auth.password)
        }.onFailure { tlsError ->
            throw IllegalStateException("${plainError.message}; TLS retry failed: ${tlsError.message}")
        }
    }

    private fun openClient(tls: Boolean, host: String, port: Int, username: String, password: String): FTPClient {
        val client: FTPClient = if (tls) FTPSClient("TLS", false) else FTPClient()
        // 编码须在 connect 前设置：影响控制命令与路径的编解码（非 ASCII 路径）。
        client.setControlEncoding(StandardCharsets.UTF_8.name())
        client.setAutodetectUTF8(true)
        client.setConnectTimeout(CONNECT_TIMEOUT_MS)
        client.setDefaultTimeout(CONTROL_READ_TIMEOUT_MS)
        client.setDataTimeout(DATA_TIMEOUT_MS)
        // 传输期间数据连接忙、控制连接空闲，靠 NOOP 保活，避免被服务器/路由器按 idle 断开。
        client.setControlKeepAliveTimeout(Duration.ofSeconds(CONTROL_KEEPALIVE_SECONDS))
        client.setControlKeepAliveReplyTimeout(Duration.ofSeconds(CONTROL_KEEPALIVE_REPLY_SECONDS))
        try {
            client.connect(host, port)
            if (!FTPReply.isPositiveCompletion(client.replyCode)) {
                throw IllegalStateException("FTP server refused connection.")
            }
            // explicit TLS 由 FTPSClient 在 connect 时就自动发出 AUTH TLS，这里不用（也无法）手动执行。
            if (!client.login(username, password)) {
                throw IllegalStateException(if (client is FTPSClient) "FTP login failed (TLS)" else "FTP login failed")
            }
            if (client is FTPSClient) {
                client.execPBSZ(0)
                client.execPROT("P")
            }
            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            FileLogger.i(TAG, "FTP 控制通道建立 tls=$tls 编码=${client.controlEncoding}, UTF8 特性=${client.hasFeature("UTF8")}")
            isConnected = true
            return client
        } catch (e: Exception) {
            runCatching { client.disconnect() }
            throw e
        }
    }

    private fun doDisconnect() {
        if (ftpClient.isConnected) {
            // 连接可能已半开，logout/disconnect 本身也可能失败，忽略后继续。
            runCatching { ftpClient.logout() }
            runCatching { ftpClient.disconnect() }
        }
        isConnected = false
    }

    override suspend fun listFiles(remotePath: String): List<RemoteFileInfo> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val files = ftpClient.listFiles(remotePath) ?: emptyArray()
                files.map {
                    RemoteFileInfo(
                        name = it.name,
                        isDirectory = it.isDirectory,
                        size = it.size,
                        lastModified = it.timestamp.timeInMillis
                    )
                }
            }
        }

    override suspend fun downloadFile(remotePath: String, localPath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                FileOutputStream(localFile).use { fos ->
                    val success = ftpClient.retrieveFile(remotePath, fos)
                    if (!success) {
                        val detail = "FTP 下载失败: $remotePath, reply=${ftpClient.replyCode} ${ftpClient.replyString.trim()}"
                        if (FTPReply.isNegativePermanent(ftpClient.replyCode)) throw RemoteFileRejectedException(detail)
                        throw IOException(detail)
                    }
                }
            }
        }

    override suspend fun uploadFile(localPath: String, remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val localFile = File(localPath)
                if (!localFile.exists()) return@withLock

                val remoteDir = remotePath.substringBeforeLast("/", "")
                if (remoteDir.isNotEmpty() && remoteDir != remotePath) {
                    makeDirectories(remoteDir)
                }

                FileInputStream(localFile).use { fis ->
                    val success = ftpClient.storeFile(remotePath, fis)
                    if (!success) {
                        val detail = "FTP 上传失败: $remotePath, reply=${ftpClient.replyCode} ${ftpClient.replyString.trim()}"
                        if (FTPReply.isNegativePermanent(ftpClient.replyCode)) throw RemoteFileRejectedException(detail)
                        throw IOException(detail)
                    }
                }
            }
        }

    override suspend fun createDirectory(remotePath: String) =
        withContext(Dispatchers.IO) { mutex.withLock { makeDirectories(remotePath) } }

    /** 逐级建目录（FTP 的 MKD 只建一级），已存在导致的失败忽略。 */
    private fun makeDirectories(path: String) {
        val absolute = path.startsWith("/")
        var current = ""
        for (part in path.split('/').filter { it.isNotEmpty() }) {
            current = when {
                current.isEmpty() -> if (absolute) "/$part" else part
                else -> "$current/$part"
            }
            runCatching { ftpClient.makeDirectory(current) }
        }
    }

    override suspend fun delete(remotePath: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                // 先尝试当做文件删除，如果失败则当做目录删除
                if (!ftpClient.deleteFile(remotePath)) {
                    ftpClient.removeDirectory(remotePath)
                }
            }
        }

    override suspend fun isConnected(): Boolean = isConnected && ftpClient.isConnected

    override suspend fun ping(): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (!ftpClient.isConnected) {
                    FileLogger.w(TAG, "FTP 探活: ftpClient.isConnected=false")
                    return@withLock false
                }
                try {
                    val ok = ftpClient.sendNoOp()
                    if (!ok) FileLogger.w(TAG, "FTP 探活 NOOP 未获成功回复: reply=${ftpClient.replyCode}")
                    ok
                } catch (e: IOException) {
                    FileLogger.w(TAG, "FTP 探活失败: ${e.javaClass.simpleName}: ${e.message}")
                    false
                }
            }
        }
}
