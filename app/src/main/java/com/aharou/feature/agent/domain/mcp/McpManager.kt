package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, FAILED, DISABLED }
}

// reloadMutex 串行化重连，避免设置页连点导致并发注册/反注册竞态。
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val oauthClient: McpOAuthClient,
    private val toolRegistry: ToolRegistry,
    @Named("Mcp") private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository
) {
    private companion object {
        const val TAG = "McpManager"

        /** 断线自动重连的退避序列（毫秒）：第 1/2/3 次分别为 5s / 15s / 60s。 */
        val RECONNECT_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)

        /** 连续重连多少次仍失败就停止自动重连（不无限热循环）。 */
        const val MAX_RECONNECT_ATTEMPTS = 3
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    private val activeClients = mutableMapOf<String, McpClient>()

    /** server → 已注册的命名空间工具名（`mcp__server__tool`）；反注册要用它，不能用 server 下发的原始名。 */
    private val registeredToolNames = mutableMapOf<String, List<String>>()

    /** 各 server 连续重连失败次数：用于退避与上限，连上即清零。 */
    private val reconnectAttempts = mutableMapOf<String, Int>()

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    fun start() {
        // 跟随当前工作区切换自动重载（首帧立即发射当前值，等价启动即重连；
        // 项目切换时合并配置与 stdio 工具的项目路径都会变化，需要重建连接）。
        scope.launch {
            workspaceRepository.current.collectLatest {
                reload()
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内自动重载，使新增/删除/启停即时生效。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到 MCP 配置文件外部变更，自动重载")
                reload()
            }
        }
        // 容器 profile 切换：stdio server 的进程跑在旧容器的 rootfs 上，必须重建才能用新容器；
        // HTTP server 不依赖容器，不受影响。drop(1) 跳过启动首帧（reload 已处理）。
        scope.launch {
            containerSettingsRepository.activeProfileIdFlow.drop(1).collect {
                reloadStdioServers()
            }
        }
        // 默认容器变化：远程模式下 stdio server 运行在默认容器上，同样需要重建。
        scope.launch {
            containerSettingsRepository.defaultContainerIdFlow.drop(1).collect {
                if (currentActiveProfile().mode == ExecutionMode.REMOTE_SSH) {
                    reloadStdioServers()
                }
            }
        }
    }

    suspend fun reload() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers()
        FileLogger.i(TAG, "重新加载 MCP 配置，共 ${servers.size} 个 server")

        // 关闭 stdio 会杀进程树并等待，调用方（设置页）走 Main，必须切到 IO 做。
        withContext(Dispatchers.IO) { teardown() }

        if (servers.isEmpty()) {
            _statuses.value = emptyList()
            return@withLock
        }

        // 先把所有 server 置为「连接中/禁用」，UI 立即有反馈。
        _statuses.value = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }

        // 并行连接所有启用的 server；各自独立失败。
        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg ->
                async { connectOne(cfg) }
            }.awaitAll()
        }

        // 合并禁用项与连接结果，保持原始顺序。
        val byName = results.associateBy { it.name }
        _statuses.value = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
    }

    private suspend fun connectOne(cfg: McpServerConfig): McpServerStatus {
        val t0 = System.currentTimeMillis()
        var created: McpClient? = null
        // transport 在 try 之外声明：client 构造失败时 created 还是 null，得靠它兜底 close。
        var transport: McpTransport? = null
        return try {
            FileLogger.i(TAG, "[${cfg.name}] 开始连接（${if (cfg.isStdio) "stdio" else "HTTP"}）")
            val t = if (cfg.isStdio) {
                // stdio server 跑在「运行时容器」上：本地模式用当前容器，远程 SSH 模式用默认容器。
                // 容器未就绪不自动初始化，直接失败并引导去终端页完成初始化。
                val runtimeProfile = resolveMcpRuntimeProfile()
                containerEngine.notReadyHintFor(runtimeProfile)?.let {
                    throw IllegalStateException(it)
                }
                StdioTransport(
                    serverName = cfg.name,
                    engine = containerEngine,
                    program = cfg.command!!,
                    programArgs = cfg.args,
                    projectPath = workspaceRepository.currentPath(),
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile,
                    // stdio 进程死亡 / 管道断开：自报死亡，由 onTransportClosed 摘除死连接并按退避重连。
                    onClosed = { onTransportClosed(cfg.name, created) }
                )
            } else {
                StreamableHttpTransport(
                    endpoint = cfg.url.orEmpty(),
                    client = okHttpClient,
                    extraHeaders = cfg.headers,
                    // 配了 oauth 块的 server 由提供者在每次请求前注入/刷新 Bearer 令牌。
                    authProvider = if (cfg.oauth != null) oauthClient.bearerProvider(cfg.name) else null
                )
            }
            transport = t
            val client = McpClient(serverName = cfg.name, transport = t).also { created = it }
            client.connect()

            val tools = client.tools.map { McpTool(client, it) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            val registeredNames = enabledTools.map { it.name }
            synchronized(activeClients) {
                activeClients[cfg.name] = client
                enabledTools.forEach { toolRegistry.register(it.name, it) }
                registeredToolNames[cfg.name] = registeredNames
            }
            FileLogger.i(TAG, "[${cfg.name}] 连接成功，注册 ${enabledTools.size}/${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: CancellationException) {
            // 取消不是失败：先收掉半开连接再原样抛出，别让 stdio 子进程与并发名额跟着泄漏。
            runCatching { created?.close() ?: transport?.close() }
            throw e
        } catch (e: Exception) {
            runCatching { created?.close() ?: transport?.close() }
            // 连接失败回到「无退避记录」状态：手动重载应重新从第 1 次退避开始。
            reconnectAttempts.remove(cfg.name)
            FileLogger.e(TAG, "[${cfg.name}] 连接失败（${System.currentTimeMillis() - t0}ms）", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        return synchronized(activeClients) {
            activeClients[serverName]?.tools ?: emptyList()
        }
    }

    /**
     * 仅重连单个 server（保存/启用开关/编辑刷新用），其他 server 的连接与已注册工具不受影响。
     * 配置中不存在该 name 时静默返回。
     */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val cfg = configRepository.getEffectiveServers().firstOrNull { it.name == name } ?: return@withLock
        withContext(Dispatchers.IO) { teardownServer(name) }
        if (_statuses.value.none { it.name == name }) {
            _statuses.value = _statuses.value + McpServerStatus(name, McpServerStatus.State.CONNECTING)
        }
        if (!cfg.enabled) {
            _statuses.value = _statuses.value.map {
                if (it.name == name) McpServerStatus(name, McpServerStatus.State.DISABLED) else it
            }
            return@withLock
        }
        reconnectOne(cfg)
    }

    /**
     * 仅重连当前未连接的 server（已连接的跳过），新会话时兜底：manageMcp 新增/删除只改配置不重连，
     * 提示「下次会话生效」；本方法让新会话真正连上未连接的 server，不打断已连接的工具。
     */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled }
        for (cfg in servers) {
            // 按「连接是否还活着」判断，而不是只看表里有没有条目：
            // stdio 进程死后旧实现拿不到死亡信号，条目一直在，于是死连接永远不被重连。
            val alive = synchronized(activeClients) { activeClients[cfg.name]?.isAlive == true }
            if (alive) continue
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /**
     * 异步兜底重连未连接的 server（不阻塞调用方）。新会话创建不应等待 MCP 就绪：
     * stdio server 首跑可能长时间卡在下载依赖/握手超时，同步等待会让「新建会话」看起来卡死。
     */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig) {
        if (_statuses.value.none { it.name == cfg.name }) {
            _statuses.value = _statuses.value + McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING)
        }
        _statuses.value = _statuses.value.map {
            if (it.name == cfg.name) McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING) else it
        }
        val result = withContext(Dispatchers.IO) { connectOne(cfg) }
        _statuses.value = _statuses.value.map { if (it.name == cfg.name) result else it }
    }

    /** 解析 stdio server 的运行时容器：本地模式用当前 profile，远程 SSH 模式用默认容器。 */
    private suspend fun resolveMcpRuntimeProfile(): ContainerProfile {
        val active = currentActiveProfile()
        return if (active.mode == ExecutionMode.REMOTE_SSH) resolveDefaultContainerProfile() else active
    }

    /** 当前激活 profile：按 id 从配置列表解析，找不到回退内置 Alpine。 */
    private suspend fun currentActiveProfile(): ContainerProfile {
        val id = containerSettingsRepository.activeProfileIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id } ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 默认容器：设置的 id 且为本地 PRoot 模式；找不到/被删/是远程则回退内置 Alpine。 */
    private suspend fun resolveDefaultContainerProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /**
     * 容器相关配置变化后重建所有 stdio server（HTTP 不依赖容器，不动）。
     * 旧进程钉在旧容器的 rootfs 上，必须 teardown 后才能用新容器拉起；单个 server 失败不影响其它。
     */
    private suspend fun reloadStdioServers() = reloadMutex.withLock {
        val servers = configRepository.getEffectiveServers().filter { it.enabled && it.isStdio }
        if (servers.isEmpty()) return@withLock
        FileLogger.i(TAG, "容器配置变化，重建 ${servers.size} 个 stdio MCP server")
        for (cfg in servers) {
            teardownServer(cfg.name)
            reconnectOne(cfg)
        }
    }

    /** 删除 server 时仅断开其连接并反注册其工具，不影响其他 server。 */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        withContext(Dispatchers.IO) { teardownServer(name) }
        _statuses.value = _statuses.value.filterNot { it.name == name }
    }

    private fun teardownServer(name: String) {
        // close() 会杀进程树并等待，放到锁外做，别让其它 server 的注册/重连被这条连接拖住。
        val client = synchronized(activeClients) {
            registeredToolNames.remove(name)?.forEach { toolRegistry.unregister(it) }
            activeClients.remove(name)
        }
        runCatching { client?.close() }
    }

    /**
     * stdio 进程死亡 / 管道断开：把这条连接从 [activeClients] 摘掉、反注册它的工具、状态置 FAILED，
     * 再按退避重连。
     *
     * 只在「死的正是当前登记的那条连接」时动手：显式 reload / removeServer 已经把条目清掉的情形
     * 直接跳过，不与它们抢；连接尚在建立中就死亡（client 还是 null）也跳过，那条路径由
     * [connectOne] 自己的失败处理员承担。
     */
    private fun onTransportClosed(serverName: String, client: McpClient?) {
        val takenOver = synchronized(activeClients) {
            if (client != null && activeClients[serverName] === client) {
                activeClients.remove(serverName)
                registeredToolNames.remove(serverName)?.forEach { toolRegistry.unregister(it) }
                true
            } else {
                false
            }
        }
        if (!takenOver) return
        FileLogger.w(TAG, "[$serverName] 连接已断开（stdio 进程退出或管道断开），准备重连")
        _statuses.value = _statuses.value.map {
            if (it.name == serverName) {
                McpServerStatus(serverName, McpServerStatus.State.FAILED, error = "连接已断开")
            } else it
        }
        scheduleReconnect(serverName)
    }

    /** 断线重连：按 [RECONNECT_DELAYS_MS] 退避，最多 [MAX_RECONNECT_ATTEMPTS] 次；连上即清零计数并停止。 */
    private fun scheduleReconnect(serverName: String, attempt: Int = (reconnectAttempts[serverName] ?: 0) + 1) {
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            FileLogger.w(
                TAG,
                "[$serverName] 已连续重连 $MAX_RECONNECT_ATTEMPTS 次仍未成功，停止自动重连" +
                    "（下次新会话或设置里手动重载会再试）"
            )
            reconnectAttempts.remove(serverName)
            return
        }
        reconnectAttempts[serverName] = attempt
        val delayMs = RECONNECT_DELAYS_MS[(attempt - 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        scope.launch {
            delay(delayMs)
            val cfg = configRepository.getEffectiveServers().firstOrNull { it.name == serverName } ?: return@launch
            if (!cfg.enabled) return@launch
            reloadMutex.withLock {
                // 等待期间可能已被显式 reload 连上（或已删除）：别重复连。
                val alive = synchronized(activeClients) { activeClients[serverName]?.isAlive == true }
                if (alive) return@withLock
                FileLogger.i(TAG, "[$serverName] 断线重连第 $attempt 次")
                teardownServer(serverName)
                reconnectOne(cfg)
            }
            val connected = _statuses.value.firstOrNull { it.name == serverName }?.state == McpServerStatus.State.CONNECTED
            if (connected) {
                reconnectAttempts.remove(serverName)
            } else {
                // 显式带上下一次次数：connectOne 失败时会清掉 map 里的记录，靠 map 读会永远算回第 1 次。
                scheduleReconnect(serverName, attempt + 1)
            }
        }
    }

    private fun teardown() {
        // 先在锁内摘干净注册表与连接表，出锁后再逐个 close（close 会阻塞）。
        val clients = synchronized(activeClients) {
            registeredToolNames.values.forEach { names -> names.forEach { toolRegistry.unregister(it) } }
            registeredToolNames.clear()
            val snapshot = activeClients.values.toList()
            activeClients.clear()
            snapshot
        }
        clients.forEach { runCatching { it.close() } }
    }
}
