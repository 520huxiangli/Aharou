package com.aharou.feature.agent.domain.container

import com.aharou.core.util.FileLogger
import com.aharou.core.util.shellQuote
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import com.aharou.feature.credentials.domain.model.GitCredential
import com.aharou.feature.settings.data.repository.ExecutionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.userauth.UserAuthException
import com.hierynomus.sshj.common.KeyDecryptionFailedException
import net.schmizz.sshj.userauth.password.PasswordUtils
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RemoteSshConnection"

/**
 * 共享的 SSH 连接管理器：持有两条独立的 sshj [SSHClient]——exec 通道供 [RemoteSshEngine] 执行命令，
 * SFTP 通道供 [RemoteSftpFileAccess] 读写文件。两者隔离，SFTP 的 Buffer 溢出不会拖垮命令通道。
 *
 * 连接配置（host/port/username/auth）由调用方在 [connect] 时传入。连接断开后下次 [connect]
 * 重新建立；SFTP 通道按需惰性建立（见 [sftp]）。exec 侧操作串行化（[mutex]），SFTP 侧由
 * [RemoteSftpFileAccess] 串行化（sshj 的 SFTPClient 非线程安全）。
 */
@Singleton
class RemoteSshConnection @Inject constructor(
    private val hostKeyStore: SshHostKeyStore,
    private val hostKeyVerifier: SshHostKeyVerifier,
    private val privateKeyStore: SshPrivateKeyStore,
    private val containerInstaller: ContainerInstaller
) {

    @Volatile
    private var sshClient: SSHClient? = null

    /**
     * 独立的 SFTP 通道：与 exec 各用一条 SSH transport。sshj 的 SFTP 有间歇性 Buffer 溢出
     * （hierynomus/sshj#461），共用 transport 时崩溃会一并拖垮 Bash/终端；分开后只影响文件通道，
     * 崩溃由 [invalidateSftp] + 下次 [sftp] 重建兜底。
     */
    @Volatile
    private var sftpSshClient: SSHClient? = null

    @Volatile
    private var sftpClient: SFTPClient? = null

    private val mutex = Mutex()
    private val sftpLock = Mutex()

    /**
     * 凭据同步互斥：切「自动注入」开关与增删凭据分别在独立协程里跑，注入与撤销并发时会一边写
     * gitconfig/includeIf、一边 rm 掉同目录文件，留下半截配置（注入静默失效，或 git 读到坏段）。
     */
    private val credentialSyncMutex = Mutex()

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    /** 连接状态流，供 UI 显示指示器、工作区初始化等待连接就绪。 */
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /** 重连成功后回调，供工作区重新加载。由 [startSupervisor] 注册。 */
    private var onReconnected: (suspend () -> Unit)? = null
    private var supervisorJob: Job? = null
    private var modeJob: Job? = null

    /** 认证失败标记：看到它就不做快速重连，否则反复重试会触发服务器防爆破（实测被封 IP）。 */
    @Volatile
    private var isAuthFailure = false

    private companion object {
        /** 认证失败后的重试间隔：5 分钟，足够避开大多数 fail2ban 窗口。 */
        const val AUTH_FAILURE_BACKOFF_MS = 5 * 60 * 1000L
    }

    /**
     * 当前是否处于远程执行模式。切回本地后「断线即重连」的所有入口都要据它空转，
     * 否则 supervisor 被停掉了，网络恢复 / 回到前台仍会把 SSH 连接建回来。
     */
    @Volatile
    private var remoteModeEnabled = false

    /** 远程服务器上的真实 home 路径（连接成功后查 $HOME 缓存），供文件工具展开 ~。 */
    @Volatile
    var remoteHome: String? = null
        private set

    /** 当前连接配置快照，供重连与路径映射使用。 */
    @Volatile
    var config: RemoteConnectionConfig? = null
        private set

    suspend fun connect(config: RemoteConnectionConfig) = mutex.withLock {
        // 凭据为空时直接报根因：否则 sshj 只会抛出
        // 「Exhausted available authentication methods」，与「密码错」「服务器拒绝」分不开，
        // 而且会白跑一轮认证（服务器侧可能计入防爆破失败次数）。
        when (val auth = config.auth) {
            is RemoteAuth.Password -> if (auth.password.isBlank()) {
                throw IllegalStateException("SSH 连接失败：未填写密码（凭据为空，不是密码错）")
            }
            is RemoteAuth.PrivateKey -> if (auth.privateKeyPath.isBlank()) {
                throw IllegalStateException("SSH 连接失败：未填写私钥路径（凭据为空）")
            }
        }
        if (isConnected() && this.config == config) return@withLock
        disconnectInternal()
        this.config = config
        _connectionState.value = ConnectionState.CONNECTING
        try {
            withContext(Dispatchers.IO) {
                val client = newSshClient(config)
                sshClient = client
                // 查远程真实 home（root 是 /root，普通用户是 /home/xxx），供文件工具展开 ~
                runCatching {
                    val s = client.startSession()
                    val cmd = s.exec("echo \$HOME")
                    remoteHome = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream))
                        .readText().trim().ifEmpty { null }
                    s.close()
                }
                // 注册 transport 断开监听：连接断开时即时推状态，不等 supervisor 轮询
                client.transport.setDisconnectListener { reason, info ->
                    FileLogger.w(TAG, "SSH 连接断开: $reason, $info")
                    _connectionState.value = ConnectionState.DISCONNECTED
                }
                FileLogger.i(TAG, "SSH 已连接 ${config.host}:${config.port} as ${config.username}")
            }
            _connectionState.value = ConnectionState.CONNECTED
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.FAILED
            throw e
        }
    }

    fun removeHostKey(host: String, port: Int) {
        hostKeyStore.remove(host, port)
    }

    fun savedHostKeys(): Map<String, String> = hostKeyStore.entries()
    /** 无参 connect：用上次保存的 config 重连。 */
    suspend fun connect() {
        val cfg = config ?: throw IllegalStateException("未配置 SSH 连接")
        connect(cfg)
    }

    suspend fun disconnect() = mutex.withLock {
        // 回拉必须在断开**之前**：连接断了就没法读远端文件了。
        // 远程模式下 AI 可能往 ~/shared 写过东西（信箱、待办），只在连上时拉一次不够——
        // 连接不断就不会再拉，写完的信永远回不到手机。断开时正是切回本地、需要数据落地的时刻。
        if (isConnected()) {
            runCatching { onBeforeDisconnect?.invoke(this) }
                .onFailure { FileLogger.w(TAG, "断开前回拉失败", it) }
        }
        disconnectInternal()
    }

    /** 断开前的回调（由上层注册，用于回拉共享区等收尾动作）。 */
    @Volatile
    private var onBeforeDisconnect: (suspend (RemoteSshConnection) -> Unit)? = null

    fun setOnBeforeDisconnect(block: suspend (RemoteSshConnection) -> Unit) {
        onBeforeDisconnect = block
    }

    private fun disconnectInternal() {
        closeSftpInternal()
        runCatching { sshClient?.disconnect() }
        sshClient = null
        _connectionState.value = ConnectionState.DISCONNECTED
    }

    /** 关闭并清空独立 SFTP 通道；下次 [sftp] 会按当前 config 重建。 */
    private fun closeSftpInternal() {
        runCatching { sftpClient?.close() }
        runCatching { sftpSshClient?.disconnect() }
        sftpClient = null
        sftpSshClient = null
    }

    /**
     * 建立一条 SSH transport 并完成认证与保活（exec 与 SFTP 各建一条）。
     *
     * 用 [DefaultConfig] 覆写 keepAlive provider 为 [KeepAliveProvider.KEEP_ALIVE]：sshj 默认的 HEARTBEAT
     * 只单向发心跳、不校验回应，对端已死也判不出失联；KEEP_ALIVE（KeepAliveRunner）要求服务器回应
     * keepalive 请求，连续 5 个间隔（5 × 30s）收不到回应即判 CONNECTION_LOST 并断开 transport，
     * 由 supervisor 走重连。
     *
     * 超时：sshj 的 connectTimeout/timeout 默认均为 0（无限等），TCP SYN 重试约 130s 是唯一兜底。
     * 两者均取 15s，与同项目 SftpSyncClient/FtpSyncClient 的取值一致。sshj 的 Reader 捕获
     * SocketTimeoutException 后仅重试、不清连接，故 15s 读超时不会误断空闲但健康的连接。
     */
    private fun newSshClient(config: RemoteConnectionConfig): SSHClient = SSHClient(
        DefaultConfig().apply { keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
    ).apply {
        addHostKeyVerifier(hostKeyVerifier)
        connectTimeout = 15_000
        timeout = 15_000
        connect(config.host, config.port)
        when (val auth = config.auth) {
            is RemoteAuth.Password -> authPassword(config.username, auth.password)
            is RemoteAuth.PrivateKey -> {
                val pem = privateKeyStore.readPem(auth.privateKeyPath)
                val passwordFinder = auth.passphrase?.let { PasswordUtils.createOneOff(it.toCharArray()) }
                authPublickey(config.username, loadKeys(pem, null, passwordFinder))
            }
        }
        // 启用 SSH 保活（KEEP_ALIVE：要求服务器回应 keepalive，同时兼作失联检测）
        runCatching {
            connection.keepAlive?.let {
                it.setKeepAliveInterval(30)
                it.start()
            }
        }
    }

    fun isConnected(): Boolean =
        sshClient?.isConnected == true && sshClient?.isAuthenticated == true

    /** 开一个 exec session 执行命令。调用方负责关闭返回的 Command。 */
    fun startExecSession(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        val session = client.startSession()
        val cmd = session.exec(command)
        // 立即给远端进程的 stdin 送 EOF：所有调用方只读输出。不关的话，远端按「stdin 是否可读」
        // 决定行为的程序（无路径参数的 rg 等）会把 channel 当待读输入，一直等到命令超时。
        runCatching { cmd.outputStream.close() }
        return cmd
    }

    /**
     * 开 exec 会话但**保留 stdin 可写**，供大内容经 stdin 传输（避免命令行参数超过远端 ARG_MAX）。
     * 调用方负责写入并关闭 [Session.Command.outputStream]、读取输出、关闭 session。
     */
    fun startExecSessionWithStdin(command: String): Session.Command {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return client.startSession().exec(command)
    }

    /**
     * 在远端起一个长驻进程并保留 stdin，供 stdio 型 MCP server 双向通信。
     *
     * 与 [startExecSessionWithStdin] 的区别：那条要求调用方自己管 session 与引号，这里直接返回
     * [Process]，让上层现成的 [StdioTransport] 不用为远程单写一套。
     */
    fun startProcess(commandLine: String): SshCommandProcess {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        val session = client.startSession()
        val command = session.exec(commandLine)
        return SshCommandProcess(session, command, commandLine.take(80))
    }

    /**
     * 本地工作区路径 → 服务器上的工作区目录（`<remoteWorkspacePath>/<目录名>`）。
     * 与 [RemoteSshEngine] 的命令 cd 目标同一口径，供 MCP stdio 远端起进程复用。
     */
    fun remoteWorkspaceFor(projectPath: String): String? {
        val base = config?.remoteWorkspacePath?.trimEnd('/') ?: return null
        val name = projectPath.trimEnd('/').substringAfterLast('/')
        return if (name.isBlank()) base else "$base/$name"
    }

    /** 开一个新的 Session，供调用方分配 PTY 并启动 shell。调用方负责关闭 Session。 */    fun startShellSession(): Session {
        val client = sshClient ?: throw IllegalStateException("SSH 未连接")
        return client.startSession()
    }

    /**
     * 获取独立的 SFTP client（惰性建立第二条 SSH 连接，与 exec 隔离）。调用方不应关闭它——
     * 由 [disconnect] 统一管理。连接失效（未连接/未认证）时丢弃重建，覆盖 SFTP 通道崩溃后的自愈。
     */
    /**
     * 回拉远端共享区的小文件到本地（远端 → 本地的增量合并）。
     *
     * 只推不拉会让远程模式写的信丢掉：AI 在远端改了 `~/shared/inbox/x.md`，不回拉就永远
     * 到不了手机，App 也不会投递；而且下次连上还会被本地旧版本盖回去。
     *
     * 冲突取舍：本地文件较新（mtime 更新）时不覆盖，只回写远端更新或本地没有的——
     * 没做三方合并，但不静默丢远端内容。
     */
    suspend fun pullSharedFiles(localRoot: java.io.File) {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val remoteRoot = "${home.trimEnd('/')}/shared"
        withContext(Dispatchers.IO) {
            runCatching {
                val s = client.startSession()
                // 只取 <=1MB 的普通文件，与推送上限一致；文件名与修改时间用 tab 分隔。
                // 注意 `-size -1M` 不能用：GNU find 的 -size 按块数且小数向上取整，
                // 非空文件也算 1 块，于是条件变成「小于 1 块」= 只匹配 0 字节文件，
                // 结果一个都列不出来（2026-10-28 实测：回拉一直空转的根因）。
                val cmd = s.exec(
                    "cd ${shellQuote(remoteRoot)} 2>/dev/null || exit 0; " +
                        "find . -type f -size -1024k -printf '%T@\\t%p\\n' 2>/dev/null"
                )
                val listing = cmd.inputStream.bufferedReader().readText()
                cmd.join(); s.close()
                val sftp = sftp()
                var pulled = 0
                listing.lineSequence().forEach { line ->
                    val parts = line.split('\t', limit = 2)
                    if (parts.size != 2) return@forEach
                    val remoteEpoch = parts[0].toDoubleOrNull()?.times(1000)?.toLong() ?: return@forEach
                    val rel = parts[1].removePrefix("./").trim()
                    if (rel.isEmpty() || rel.startsWith("..") || rel.contains("/../")) return@forEach
                    val dest = java.io.File(localRoot, rel)
                    // 本地更新就不覆盖：用户的本地编辑不该被远端旧版本冲掉。
                    if (dest.isFile && dest.lastModified() >= remoteEpoch) return@forEach
                    runCatching {
                        dest.parentFile?.mkdirs()
                        // RemoteFile 不是 InputStream，读法只能是 read(offset, buf, off, len)，
                        // 而这里只回拉 <=1MB 的小文件，直接整块读完再写盘。
                        val bytes = sftp.open(remoteRoot + "/" + rel).use { rf ->
                            val buf = ByteArray(64 * 1024)
                            val out = java.io.ByteArrayOutputStream()
                            var offset = 0L
                            while (true) {
                                val n = rf.read(offset, buf, 0, buf.size)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                            out.toByteArray()
                        }
                        dest.writeBytes(bytes)
                        dest.setLastModified(remoteEpoch)
                        pulled++
                    }.onFailure { FileLogger.w(TAG, "回拉共享区文件失败: $rel", it) }
                }
                if (pulled > 0) FileLogger.i(TAG, "已从远端回拉 $pulled 个共享区文件")
            }.onFailure { FileLogger.w(TAG, "回拉远端共享区失败", it) }
        }
    }

    suspend fun sftp(): SFTPClient = sftpLock.withLock {
        val cfg = config ?: throw IllegalStateException("SSH 未配置")
        sftpClient
            ?.takeIf { sftpSshClient?.isConnected == true && sftpSshClient?.isAuthenticated == true }
            ?.let { return@withLock it }
        closeSftpInternal()
        val client = withContext(Dispatchers.IO) { newSshClient(cfg) }
        sftpSshClient = client
        val sftp = withContext(Dispatchers.IO) { client.newSFTPClient() }
        sftpClient = sftp
        sftp
    }

    /** 丢弃当前 SFTP 通道（通道异常后调用），下次 [sftp] 会重建。 */
    suspend fun invalidateSftp() = sftpLock.withLock { closeSftpInternal() }

    /** 若已配置但未连接，立即尝试重连一次。返回是否最终连通。供 App 回到前台时主动触发。 */
    suspend fun tryReconnectIfDisconnected(): Boolean {
        // 本地模式下没有远程连接要恢复：网络恢复、回到前台都会调到这里，放行就会把刚断开的连接重连回来。
        if (!remoteModeEnabled) return false
        val cfg = config ?: return false
        if (isConnected()) return true
        _connectionState.value = ConnectionState.CONNECTING
        return try {
            connect(cfg)
            FileLogger.i(TAG, "SSH 重连成功（前台触发）")
            try {
                onReconnected?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 回调里的取消同样要透传：吞掉会让已取消的重连继续往下走，把取消记成一次回调失败。
                throw e
            } catch (e: Exception) {
                FileLogger.w(TAG, "SSH 已重连但重连后回调失败", e)
            }
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消必须原样透传：吐成「重连失败」会把 stopSupervisor 的取消当成一次真正的连接失败。
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "SSH 重连失败（前台触发）", e)
            // 认证类失败单独记：反复重试会触发服务器防爆破，比「连不上」严重得多。
            isAuthFailure = friendlySshError(e).contains("认证", ignoreCase = true) ||
                e.message?.contains("auth", ignoreCase = true) == true
            _connectionState.value = ConnectionState.FAILED
            false
        }
    }

    /**
     * 按执行模式驱动连接监督：远程模式下定期探活、断线自动重连；切回本地则停监督并断开连接。
     *
     * 由连接管理器自己盯执行模式，是因为「SSH 只在远程模式下有存在意义」是连接的固有约束：
     * 交给调用方每次记得停，漏一处就会在本地模式下继续探活重连、远程终端的 shell 会话也一直
     * 挂在服务器上。[config] 故意保留：它还被路径解析、项目级配置目录的哈希等读，
     * 清掉会让切回远程的瞬间算出一套不同的路径。
     *
     * 幂等：重复调用不会起多个模式监听；仅需在 App 启动时调用一次。
     */
    fun superviseByExecutionMode(
        scope: CoroutineScope,
        mode: Flow<ExecutionMode>,
        onReconnected: suspend () -> Unit
    ) {
        this.onReconnected = onReconnected
        if (modeJob != null) return
        modeJob = scope.launch {
            mode.collect { m ->
                remoteModeEnabled = m == ExecutionMode.REMOTE_SSH
                if (remoteModeEnabled) {
                    startSupervisor(scope, onReconnected)
                } else {
                    // 先停再断：否则探活协程会在断开后立刻重连。
                    stopSupervisor()
                    disconnect()
                }
            }
        }
    }

    /**
     * 启动连接监督协程：远程模式下定期探活，连接断开则自动重连（指数退避），
     * 重连成功后触发 [onReconnected] 回调（供工作区重新加载）并维护 [connectionState]。
     * 幂等：重复调用不会起多个 supervisor。仅在远程模式下生效。 */
    fun startSupervisor(scope: CoroutineScope, onReconnected: suspend () -> Unit) {
        this.onReconnected = onReconnected
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            var backoffMs = 5000L
            val maxBackoffMs = 30000L
            while (isActive) {
                delay(backoffMs)
                val cfg = config ?: continue
                if (isConnected()) {
                    backoffMs = 15000 // 连接正常，拉长探活间隔（仅兜底，断开有 DisconnectListener 即时通知）
                    continue
                }
                // 连接已断，尝试重连
                val ok = tryReconnectIfDisconnected()
                // 认证失败单独长退避：密码错/账号改了时反复重连会触发服务器防爆破（fail2ban），
                // 实测直接把手机 IP 封了。这种失败重试再快也没用，只会把事情弄得更糟。
                val authFailed = !ok && isAuthFailure
                if (!ok) {
                    backoffMs = if (authFailed) {
                        AUTH_FAILURE_BACKOFF_MS
                    } else {
                        (backoffMs * 2).coerceAtMost(maxBackoffMs)
                    }
                    delay(backoffMs)
                } else {
                    isAuthFailure = false
                    backoffMs = 15000
                }
            }
        }
    }

    /**
     * 停止连接监督。切回非远程执行模式时调用：supervisor 只服务远程模式，
     * 留着会在本地模式下继续探活并按退避重连，把已经无用的远程连接重新建起来。
     * 幂等：supervisor 本来就没跑时直接返回。
     */
    fun stopSupervisor() {
        supervisorJob?.cancel()
        supervisorJob = null
    }

    /**
     * 更新 ~/workspace 符号链接指向当前选中工作区的远程路径，让 AI 用 ~/workspace/... 路径时
     * Bash 命令（pwd 等）能直接访问到正确的工作区目录。应在工作区选中/初始化后调用。
     *
     * `~/workspace` 已是真实目录时跳过（工作区根被配成了 `~/workspace` 的情况）：此时 `ln` 会把
     * 链接建到该目录**内部**，形成自引用死循环，破坏整个工作区；跳过时命令链路会回退到真实路径。
     */
    suspend fun updateWorkspaceSymlink(workspacePath: String) {
        val client = sshClient ?: return
        val ws = workspacePath.trimEnd('/')
        if (ws.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val session = client.startSession()
                val cmd = session.exec(
                    "if [ -d ~/workspace ] && [ ! -L ~/workspace ]; then echo skip; " +
                        "else ln -sfn ${shellQuote(ws)} ~/workspace 2>/dev/null; echo done; fi"
                )
                val out = java.io.BufferedReader(java.io.InputStreamReader(cmd.inputStream)).readText().trim()
                session.close()
                if (out == "skip") {
                    FileLogger.w(TAG, "~/workspace 已是真实目录，跳过符号链接（工作区根可能配成了 ~/workspace）")
                }
            }.onFailure { FileLogger.w(TAG, "更新 workspace 符号链接失败: $ws", it) }
        }
    }

    /**
     * 把内置文档同步到远程 ~/.aharou/docs/，让远程模式下 AI 也能像本地模式一样
     * 查阅 ~/.aharou/docs/ 下的设置说明文档。每次连接/重连后全量覆盖，使 App 升级后
     * 远程文档随之更新。docs 是纯文本，用 exec + printf 写入即可，无需 SFTP。
     *
     * @param docs 相对 docs/ 的路径（可含子目录，如 guide/terminal.md）→ 文件内容。
     */
    suspend fun uploadDocs(docs: Map<String, String>) {
        if (docs.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val destDir = home.trimEnd('/') + "/.aharou/docs"
        withContext(Dispatchers.IO) {
            runCatching {
                // 先建全部子目录并清掉旧 .md：版本间调整文档分类会改变路径，只覆盖不清理会让旧文件残留
                val subDirs = docs.keys.mapNotNull { key ->
                    key.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }
                }.distinct()
                val mkdirArgs = (listOf(destDir) + subDirs.map { "$destDir/$it" }).joinToString(" ") { shellQuote(it) }
                val prepSession = client.startSession()
                prepSession.exec("mkdir -p $mkdirArgs; find ${shellQuote(destDir)} -type f -name '*.md' -delete").join()
                prepSession.close()
                for ((name, content) in docs) {
                    writeRemoteFile(client, "$destDir/$name", content)
                }
                FileLogger.i(TAG, "已同步 ${docs.size} 个文档到远程 $destDir")
            }.onFailure { FileLogger.w(TAG, "同步文档到远程失败", it) }
        }
    }

    /**
     * 释放 App 内置资源（子代理定义、面板示例脚本等）到远程 `~/.aharou/<subdir>/`。
     * 仅补齐服务器上缺失的文件、**不覆盖已存在**（保留用户修改），脚本赋可执行位。
     * 与 [uploadDocs] 一样在连接/重连后调用——远程模式下全局 agents/scripts 以远端为准，
     * 内置默认需在远端存在才能「开箱即用」。二进制安全（base64 传输）。
     */
    suspend fun uploadBuiltinFiles(
        destSubdir: String,
        files: Map<String, ByteArray>,
        executable: Boolean = false,
        /** 相对 home 的容器目录，默认 `.aharou`；共享区传空串（落在 `~/shared`）。 */
        rootDir: String = ".aharou"
    ) {
        if (files.isEmpty()) return
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val root = rootDir.trim('/')
        val destDir = home.trimEnd('/') + "/" + (if (root.isEmpty()) "" else "$root/") + destSubdir.trim('/')
        withContext(Dispatchers.IO) {
            runCatching {
                val mk = client.startSession()
                mk.exec("mkdir -p ${shellQuote(destDir)}").join()
                mk.close()
                for ((name, bytes) in files) {
                    val dest = "$destDir/$name"
                    // name 可含子目录（如 skills/config-channel/SKILL.md）：先建父目录，
                    // 否则重定向会因目录不存在而失败。
                    val parent = dest.substringBeforeLast('/', "")
                    if (parent.isNotEmpty() && parent != destDir) {
                        val md = client.startSession()
                        md.exec("mkdir -p ${shellQuote(parent)}").join()
                        md.close()
                    }
                    val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    val chmod = if (executable) " chmod +x ${shellQuote(dest)};" else ""
                    val session = client.startSession()
                    // $b64 是 base64 无需转义；路径统一走 shellQuote，防路径含单引号时被截断/注入
                    session.exec("if [ ! -e ${shellQuote(dest)} ]; then printf %s '$b64' | base64 -d > ${shellQuote(dest)};$chmod fi").join()
                    session.close()
                }
                FileLogger.i(TAG, "已释放 ${files.size} 个内置文件到远程 $destDir")
            }.onFailure { FileLogger.w(TAG, "释放内置文件到远程失败: $destSubdir", it) }
        }
    }

    /**
     * 把 App 的 git 凭据注入远程服务器（仅当用户开启「自动注入」时调用）：
     * 写 `~/.aharou/git-credentials`（store 格式）+ `~/.aharou/gitconfig` + `~/.aharou/gitconfig.credential`。
     *
     * 生效机制：[RemoteSshEngine] 执行命令时注入 `GIT_CONFIG_GLOBAL=$HOME/.aharou/gitconfig`，其中
     * `[include] path=~/.gitconfig` 保留服务器用户自己的全局配置（署名等），`[includeIf "gitdir:<远程工作区父目录>/"]`
     * 限定只有工作区父目录下的仓库才加载 store helper——不影响用户在服务器上手动 git，也不影响其它目录的仓库。
     * 用父目录而不是单个工作区：切工作区时不用重新上传，也不会因为漏上传而认证失败。
     *
     * @param credentials 要注入的凭据列表（每 host 一条）。
     * @param workspaceRoot 远程工作区根路径（AI 的 ~/workspace 映射目录），限定注入范围。
     */
    suspend fun uploadGitCredentialConfig(credentials: List<GitCredential>, workspaceRoot: String) {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aharouDir = home.trimEnd('/') + "/.aharou"
        // 注入范围用「远程工作区父目录」而不是单个工作区：includeIf 只认一个目录时，
        // 切到新工作区就不命中了，漏重新上传一次就 push 认证失败。父目录下都是用户自己的
        // 工作区仓库，放宽到这一层不会把凭据递给无关仓库。
        val wsRoot = config?.remoteWorkspacePath?.trimEnd('/')
            ?.takeIf { it.isNotBlank() }
            ?: workspaceRoot.trimEnd('/').ifEmpty { return }
        // 与 removeGitCredentialConfig 共用同一把锁：并发时一边写 includeIf 配置、一边删文件，
        // 会留下半截配置。两个方法互不调用，锁内也不再取别的锁，不存在重入/交叉等待。
        credentialSyncMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val mkdirSession = client.startSession()
                    mkdirSession.exec("mkdir -p ${shellQuote(aharouDir)}").join()
                    mkdirSession.close()

                    val creds = credentials.joinToString("\n") { c ->
                        "https://${enc(c.username)}:${enc(c.token)}@${c.host}"
                    }.let { if (it.isNotEmpty()) "$it\n" else "" }
                    writeRemoteFile(client, "$aharouDir/git-credentials", encodeCreds(creds))

                    // 上传 credential helper 脚本（本地由 ContainerInstaller 提取），配置为唯一 helper；
                    // 服务器上没有 App，用 AICODE_CRED_NO_PROMPT 让未命中时直接空退出而非等待弹窗。
                    containerInstaller.extractCredentialHelper()
                    val helperScript = java.io.File(containerInstaller.aharouDir, "git-credential-aicode").readText()
                    writeRemoteFile(client, "$aharouDir/git-credential-aicode", helperScript)
                    val chmodSession = client.startSession()
                    chmodSession.exec("chmod +x ${shellQuote("$aharouDir/git-credential-aicode")}").join()
                    chmodSession.close()

                    val gitconfig = buildString {
                        append("[include]\n")
                        append("    path = ~/.gitconfig\n")
                        append("[includeIf \"gitdir:${gitConfigValue(wsRoot)}/\"]\n")
                        append("    path = ~/.aharou/gitconfig.credential\n")
                    }
                    writeRemoteFile(client, "$aharouDir/gitconfig", gitconfig)

                    val credentialConfig = buildString {
                        append("[credential]\n")
                        append("    helper = !AICODE_CRED_NO_PROMPT=1 $aharouDir/git-credential-aicode\n")
                    }
                    writeRemoteFile(client, "$aharouDir/gitconfig.credential", credentialConfig)
                    FileLogger.i(TAG, "已注入 git 凭据到远程 $aharouDir（限定 $wsRoot/）")
                }.onFailure { FileLogger.w(TAG, "注入 git 凭据到远程失败", it) }
            }
        }
    }

    /**
     * 撤销远程注入：删除 App 管理的三个配置/凭据文件。
     * `GIT_CONFIG_GLOBAL` 指向不存在的文件时 git 静默跳过，注入即失效，服务器用户配置不受影响。
     */
    suspend fun removeGitCredentialConfig() {
        val client = sshClient ?: return
        val home = remoteHome ?: return
        val aharouDir = home.trimEnd('/') + "/.aharou"
        credentialSyncMutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val session = client.startSession()
                    val managedFiles = listOf(
                        "$aharouDir/gitconfig",
                        "$aharouDir/gitconfig.credential",
                        "$aharouDir/git-credentials",
                        "$aharouDir/git-credential-aicode"
                    )
                    session.exec("rm -f ${managedFiles.joinToString(" ") { shellQuote(it) }} 2>/dev/null; echo done").join()
                    session.close()
                    FileLogger.i(TAG, "已撤销远程 git 凭据注入")
                }.onFailure { FileLogger.w(TAG, "撤销远程 git 凭据注入失败", it) }
            }
        }
    }

    /** exec + printf 写一个文本文件到远程，内容与路径都由 [shellQuote] 转义。 */
    private suspend fun writeRemoteFile(client: SSHClient, dest: String, content: String) {
        val session = client.startSession()
        // 写临时文件再 mv 覆盖：直接 `>` 写会在中途断连时留下空或半截文件，凭据/ gitconfig
        // 一次写坏后续 git 操作就报看不懂的错。mv 同分区是原子替换。
        val tmp = "$dest.tmp"
        session.exec(
            "printf %s ${shellQuote(content)} > ${shellQuote(tmp)} && mv -f ${shellQuote(tmp)} ${shellQuote(dest)}"
        ).join()
        session.close()
    }

    private fun enc(part: String): String = java.net.URLEncoder.encode(part, "UTF-8")

    /**
     * gitconfig 双引号值的转义：`gitdir:` 后面跟的是用户自己填的远程路径，带着引号或反斜杠时
     * 会把整个 includeIf 段写坏（轻则凭据注入失效，重则后续段落被当成新键）。
     */
    private fun gitConfigValue(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** 与 app 侧一致的编码：base64 后整体反转（属混淆，非加密）。 */
    private fun encodeCreds(plain: String): String =
        android.util.Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP).reversed()
}

/** 远程 SSH 连接状态，供 UI 指示器与工作区初始化时序判断。 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    FAILED
}

/** 把 SSH 连接异常翻译成友好提示，供工具层回显给 AI/用户。 */
fun friendlySshError(e: Throwable): String {
    val msg = e.message.orEmpty()
    return when {
        e is KeyDecryptionFailedException ->
            "SSH 认证失败：私钥口令不正确或私钥格式不受支持（$msg）"
        e is UserAuthException ->
            "SSH 认证失败：用户名、密码或密钥未被服务器接受（$msg）"
        "ECONNREFUSED" in msg || "Connection refused" in msg ->
            "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确"
        "UnknownHost" in msg || "unknown host" in msg ->
            "无法连接到 SSH 服务器（未知主机），请检查主机地址"
        "Auth fail" in msg || "authentication" in msg ->
            "SSH 认证失败，请检查用户名和密码"
        "Network is unreachable" in msg ->
            "网络不可达，请检查网络连接"
        "SocketTimeout" in msg || "timed out" in msg ->
            "连接 SSH 服务器超时，请检查网络或服务器状态"
        "SSH 未连接" in msg ->
            "SSH 未连接，请等待连接恢复或在设置中检查配置"
        else -> "SSH 连接失败: ${e.message ?: "未知错误"}"
    }
}

/** 远程 SSH 连接配置。 */
data class RemoteConnectionConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val auth: RemoteAuth,
    /** 远程服务器上的工作区根路径（AI 的 ~/workspace 映射到此路径）。 */
    val remoteWorkspacePath: String
)
