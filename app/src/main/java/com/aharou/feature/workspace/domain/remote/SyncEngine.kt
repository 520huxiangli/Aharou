package com.aharou.feature.workspace.domain.remote

import com.aharou.core.util.FileLogger
import com.aharou.core.util.GitIgnoreMatcher
import com.aharou.feature.workspace.domain.model.SyncConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SyncEngine(
    private val mount: com.aharou.feature.workspace.domain.model.RemoteMount,
    private val connection: com.aharou.feature.workspace.domain.model.RemoteConnection,
    private val syncClient: RemoteSyncClient,
    private val auth: RemoteAuth,
    private val ignoredPatternsStr: String,
    private val useGitIgnore: Boolean,
    private val maxSyncBatchSize: Int,
    /** 同步指纹索引：未变文件据此跳过重传（见 [SyncDecision]）。为 null 时不持久化、退化为全量上传。 */
    private val indexStore: SyncIndexStore? = null
) {
    companion object {
        private const val TAG = "SyncEngine"
        private const val PING_INTERVAL_MS = 15_000L
        private const val RECONNECT_BASE_MS = 5_000L
        private const val RECONNECT_MAX_MS = 60_000L
        private const val FILE_SYNC_DELAY_MS = 50L

        /** 待同步路径队列上限；超出时丢弃最旧项，防止大量改动时内存无界增长。 */
        private const val MAX_PENDING_SYNC = 4096

        private val HEX = "0123456789abcdef".toCharArray()
    }

    private val customIgnores = ignoredPatternsStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private var gitIgnorePatterns = emptyList<String>()

    private fun isIgnored(path: String): Boolean {
        // 统一转为「相对挂载根」的路径段再匹配：GitIgnoreMatcher 契约是相对路径段，
        // 直接传宿主/远程绝对路径会让前缀段参与匹配，可能误命中（如 gitignore 写了 `data` 而
        // 宿主路径恰好有 /data 前缀）。本地路径剥 localMountPath，远程路径剥 remotePath。
        val relative = path
            .removePrefix(mount.localMountPath)
            .removePrefix(mount.remotePath)
            .trimStart('/', File.separatorChar)
        val parts = relative.split(File.separatorChar, '/').filter { it.isNotEmpty() }

        // 1. 检查自定义忽略规则 (主要针对目录名和文件名)
        if (parts.any { it in customIgnores }) return true

        // 2. 检查 .gitignore 规则
        if (useGitIgnore && gitIgnorePatterns.isNotEmpty()) {
            if (GitIgnoreMatcher.isIgnored(gitIgnorePatterns, parts)) {
                return true
            }
        }
        return false
    }

    /** 相对挂载根的路径，作为索引 key（挂载本地路径改动后仍可复用）。 */
    private fun relativeKey(absolutePath: String): String =
        absolutePath.removePrefix(mount.localMountPath).removePrefix("/")

    /** 计算文件内容 SHA-256（十六进制小写）；读盘失败返回 null（调用方据此保守上传）。 */
    private fun sha256Of(file: File): String? = runCatching {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
        }
        val hex = StringBuilder(64)
        for (b in digest.digest()) hex.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        hex.toString()
    }.getOrNull()

    /** 判定某文件是否需要上传；命中跳过时顺带刷新 mtime，避免下次重复算哈希。 */
    private fun shouldUpload(rel: String, file: File): Boolean {
        val action = SyncDecision.decide(fingerprints[rel], file.length(), file.lastModified()) { sha256Of(file) }
        if (action == SyncDecision.Action.SKIP_UNCHANGED) {
            fingerprints[rel]?.let { stored ->
                if (stored.mtimeMs != file.lastModified()) {
                    fingerprints[rel] = stored.copy(mtimeMs = file.lastModified())
                }
            }
            return false
        }
        return true
    }

    /** 上传成功后记录指纹；仅当与旧记录同尺寸（原地改写）才补算哈希，避免典型上传都全量读盘。 */
    private fun recordUploaded(rel: String, file: File) {
        val size = file.length()
        val stored = fingerprints[rel]
        val sha = if (stored != null && stored.size == size) sha256Of(file) else null
        fingerprints[rel] = FileFingerprint(size, file.lastModified(), sha)
    }

    private fun saveIndex() {
        val store = indexStore ?: return
        if (fingerprints.isNotEmpty()) store.save(mount.id, remoteIdentity, HashMap(fingerprints))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val retryCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 相对挂载根路径 → 上次成功同步的指纹；用于跳过未变文件。 */
    private val fingerprints = java.util.concurrent.ConcurrentHashMap<String, FileFingerprint>()

    /** 本次连接对应的远端身份；与索引内记录不一致时索引整体作废（全量上传）。 */
    private val remoteIdentity = SyncIndexIdentity(
        connectionId = connection.id,
        host = connection.host,
        remotePath = mount.remotePath
    )

    private val _connectionState = MutableStateFlow(SyncConnectionState.CONNECTED)
    /** 连接健康度：探活失败进 RECONNECTING，重连成功回 CONNECTED，供 UI 展示。 */
    val connectionState: StateFlow<SyncConnectionState> = _connectionState.asStateFlow()
    
    // 使用 Channel 做缓冲和防抖（上限内堆积，满则丢弃最旧项）
    private val syncChannel = Channel<String>(
        capacity = MAX_PENDING_SYNC,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    init {
        indexStore?.load(mount.id, remoteIdentity)?.let { fingerprints.putAll(it) }
        if (useGitIgnore) {
            val gitignore = File(mount.localMountPath, ".gitignore")
            if (gitignore.exists()) {
                gitIgnorePatterns = gitignore.readLines()
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .map { it.trim().removeSuffix("/") }
            }
        }

        scope.launch {
            while (isActive) {
                // 等待队列中的第一个事件
                val firstItem = syncChannel.receive()
                val batch = mutableSetOf(firstItem) // 使用 Set 去重，避免极短时间内同一文件多次触发上传

                // 尝试收集更多就绪的事件，但不超过最大批处理数量
                while (batch.size < maxSyncBatchSize) {
                    val next = syncChannel.tryReceive().getOrNull() ?: break
                    batch.add(next)
                }

                // 按顺序处理批次
                for (localPath in batch) {
                    handleLocalChange(localPath)
                }
                saveIndex()

                // 如果达到批次上限，说明可能正处于大量修改阶段，短暂延迟让服务器缓冲一下
                if (batch.size >= maxSyncBatchSize) {
                    delay(300)
                }
            }
        }

        // 断线自动重连 supervisor：定期探活，断开则按退避重连。
        scope.launch {
            var backoffMs = RECONNECT_BASE_MS
            while (isActive) {
                delay(PING_INTERVAL_MS)
                if (!syncClient.ping()) {
                    FileLogger.w(TAG, "Sync: 连接已断开（${connection.name}），开始自动重连")
                    _connectionState.value = SyncConnectionState.RECONNECTING
                    while (isActive && !syncClient.ping()) {
                        delay(backoffMs)
                        try {
                            syncClient.reconnect(connection.host, connection.port, connection.username, auth)
                            backoffMs = RECONNECT_BASE_MS
                            _connectionState.value = SyncConnectionState.CONNECTED
                            FileLogger.i(TAG, "Sync: 自动重连成功（${connection.name}）")
                        } catch (e: Exception) {
                            FileLogger.e(TAG, "Sync: 自动重连失败（${connection.name}），${backoffMs / 1000}s 后重试: ${e.message}")
                            backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_MAX_MS)
                        }
                    }
                }
            }
        }
    }

    /**
     * 全量拉取远程工作区到本地
     */
    suspend fun downloadWorkspace() = withContext(Dispatchers.IO) {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) {
            localRoot.mkdirs()
        }
        
        // 简单递归下载实现
        suspend fun pull(remoteDir: String, localDir: File) {
            val files = syncClient.listFiles(remoteDir)
            for (file in files) {
                if (isIgnored("$remoteDir/${file.name}")) continue
                val rPath = "$remoteDir/${file.name}"
                val lFile = File(localDir, file.name)
                if (file.isDirectory) {
                    lFile.mkdirs()
                    pull(rPath, lFile)
                } else {
                    try {
                        syncClient.downloadFile(rPath, lFile.absolutePath)
                        fingerprints[relativeKey(lFile.absolutePath)] =
                            FileFingerprint(lFile.length(), lFile.lastModified(), null)
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Download Error for $rPath: ${e.message}")
                        if (e !is RemoteFileRejectedException) forceReconnect()
                    }
                }
            }
        }
        pull(mount.remotePath, localRoot)
        saveIndex()
    }

    /**
     * 推送本地工作区到远程。[force] 为 true 时忽略索引、全部重传；默认走增量、跳过未变文件。
     */
    suspend fun uploadWorkspace(force: Boolean = false) = withContext(Dispatchers.IO) {
        if (!syncClient.isConnected()) {
            throw IllegalStateException("Client not connected")
        }
        val localRoot = File(mount.localMountPath)
        if (!localRoot.exists()) return@withContext

        suspend fun push(localDir: File, remoteDir: String) {
            val files = localDir.listFiles() ?: return
            for (file in files) {
                if (isIgnored(file.absolutePath)) continue
                val rPath = "$remoteDir/${file.name}"
                if (file.isDirectory) {
                    // 远程目录可能已由先前同步创建，已存在时报错可忽略。
                    try { syncClient.createDirectory(rPath) } catch (e: Exception) {
                        FileLogger.w(TAG, "Sync: 远程目录创建失败 $rPath: ${e.message}")
                    }
                    push(file, rPath)
                } else {
                    val rel = relativeKey(file.absolutePath)
                    if (!force && !shouldUpload(rel, file)) {
                        FileLogger.d(TAG, "Sync: 未变化，跳过上传 $rPath")
                        continue
                    }
                    try {
                        syncClient.uploadFile(file.absolutePath, rPath)
                        recordUploaded(rel, file)
                        delay(FILE_SYNC_DELAY_MS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Upload Error for $rPath: ${e.message}")
                        if (e is RemoteFileRejectedException) continue
                        forceReconnect()
                        // 全量同步失败的也放入增量队列兜底重传
                        scope.launch {
                            delay(1000)
                            syncChannel.send(file.absolutePath)
                        }
                    }
                }
            }
        }
        push(localRoot, mount.remotePath)
        saveIndex()
    }

    /** 清除本挂载的同步指纹：内存与持久化都清空，后续上传视为全部未同步。 */
    fun clearSyncIndex() {
        fingerprints.clear()
        indexStore?.clear(mount.id)
    }

    /**
     * 外部（[com.aharou.feature.workspace.domain.repository.RemoteRepository] 的中心服务订阅）发现
     * 本地变更后投递进来；与失败重试共用同一队列，保持原有批处理与节流语义。
     */
    fun enqueueLocalChange(localPath: String) {
        FileLogger.d(TAG, "[diag] enqueueLocalChange localPath=$localPath ignored=${isIgnored(localPath)}")
        syncChannel.trySend(localPath)
    }

    private suspend fun handleLocalChange(localPath: String) {
        if (isIgnored(localPath)) return
        val file = File(localPath)
        // 计算对应的远程路径
        val relativePath = localPath.removePrefix(mount.localMountPath).removePrefix("/")
        val remotePath = "${mount.remotePath}/$relativePath"

        try {
            if (file.exists()) {
                if (file.isDirectory) {
                    syncClient.createDirectory(remotePath)
                    FileLogger.i(TAG, "Sync: Created remote directory $remotePath")
                } else if (!shouldUpload(relativePath, file)) {
                    FileLogger.d(TAG, "Sync: 未变化，跳过上传 $remotePath")
                } else {
                    syncClient.uploadFile(localPath, remotePath)
                    recordUploaded(relativePath, file)
                    FileLogger.i(TAG, "Sync: Uploaded to $remotePath")
                    delay(FILE_SYNC_DELAY_MS)
                }
            } else {
                syncClient.delete(remotePath)
                fingerprints.remove(relativePath)
                FileLogger.i(TAG, "Sync: Deleted $remotePath")
                delay(FILE_SYNC_DELAY_MS)
            }
            retryCounts.remove(localPath) // 成功后清除重试计数
        } catch (e: Exception) {
            FileLogger.e(TAG, "Sync Error for $localPath: ${e.message}", e)
            if (e is RemoteFileRejectedException) {
                retryCounts.remove(localPath)
                FileLogger.w(TAG, "Sync: 服务器拒绝该路径，跳过重连和重试: $remotePath")
                return
            }
            forceReconnect()
            val count = retryCounts.getOrDefault(localPath, 0)
            if (count < 3) {
                retryCounts[localPath] = count + 1
                FileLogger.i(TAG, "Sync: Retry ${count + 1}/3 for $localPath")
                // 将失败的文件重新放入队列
                scope.launch {
                    delay(1000)
                    syncChannel.send(localPath)
                }
            } else {
                FileLogger.e(TAG, "Sync: Max retries reached for $localPath. Giving up.")
                retryCounts.remove(localPath)
            }
        }
    }

    fun shutdown() {
        scope.cancel()
    }

    private suspend fun forceReconnect() {
        try {
            // 断开旧连接可能因网络中断本身失败，忽略后继续尝试重连。
            syncClient.disconnect()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Sync: 断开旧连接失败: ${e.message}")
        }
        try {
            syncClient.connect(
                connection.host,
                connection.port,
                connection.username,
                auth
            )
            FileLogger.i(TAG, "Sync: Force reconnected to server successfully.")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Sync: Failed to force reconnect: ${e.message}")
        }
    }
}
