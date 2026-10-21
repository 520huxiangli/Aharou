package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.tool.ToolRegistry
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.vault.VaultRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

data class McpServerStatus(
    val name: String,
    val state: State,
    val toolCount: Int = 0,
    val error: String? = null
) {
    enum class State { CONNECTING, CONNECTED, FAILED, DISABLED }
}

/**
 * MCP 连接管理：**按工作区**各自维护一套连接。
 *
 * 为什么不是全局一套：MCP 配置分全局与工作区级（项目级）；工作区级里可能有只属于该工作区的
 * 脚本与凭据（例如机器人桥接只该在小染工作区可用）。全局单套时只能跟着「当前活动工作区」加载，
 * 一换工作区那套连接就没了——隧道断、投递失败。所以这里每个工作区一个 [WorkspaceRuntime]，
 * 连接常驻；但**工具表只挂当前活动工作区的那一套**：工具名是 `mcp__server__tool`，
 * 两个工作区有同名 server 时必须二选一，不能同时注册。
 *
 * 连接生命周期：连上就一直保持，不因切工作区或空闲时间被主动拆掉；只有配置变更、容器切换、
 * 进程死亡（自报重连）或 App 退出才会断开。
 */
// reloadMutex 串行化重连，避免设置页连点导致并发注册/反注册竞态。
@Singleton
class McpManager @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val oauthClient: McpOAuthClient,
    private val toolRegistry: ToolRegistry,
    @Named("Mcp") private val okHttpClient: OkHttpClient,
    private val containerEngine: LinuxContainerEngine,
    private val workspaceRepository: WorkspaceRepository,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val vault: VaultRepository
) {
    private companion object {
        const val TAG = "McpManager"

        /** 断线自动重连的退避序列（毫秒）：第 1/2/3 次分别为 5s / 15s / 60s。 */
        val RECONNECT_DELAYS_MS = longArrayOf(5_000L, 15_000L, 60_000L)

        /** 连续重连多少次仍失败就转入低频重试（不无限热循环）。 */
        const val MAX_RECONNECT_ATTEMPTS = 3

        /** 快速重试用尽后的低频重试间隔：装包重启时 stdio server（如容器里的 bridge）起得慢，
         *  只试三次会一直停在 FAILED，直到用户新建会话或手动重载。 */
        /** 非活动工作区的连接空闲超过这个时长就拆掉（开了保活的 server 不受影响）。 */
        const val IDLE_EVICT_MS = 15 * 60 * 1000L

        /** 空闲检查的轮询间隔。 */
        const val EVICT_CHECK_MS = 5 * 60 * 1000L

        const val SLOW_RETRY_MS = 10 * 60 * 1000L

        /** 非活动工作区的连接空闲多久回收：常驻是为了切回来即用，不是为了永久占住进程。 */

        /** 同时保留连接的工作区上限：每个工作区都可能是一批 stdio 进程，必须有上限。 */

        /** 空闲回收的检查间隔。 */
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reloadMutex = Mutex()

    /** 单个工作区的连接与状态。 */
    private class WorkspaceRuntime(val path: String) {
        val clients = mutableMapOf<String, McpClient>()
        var statuses: List<McpServerStatus> = emptyList()

        /** 每个 server 最后一次被调用的时间：空闲回收按它判定，工作区切换不重置。 */
        val lastUsed = mutableMapOf<String, Long>()

        /** 配置里开了保活开关的 server 名：空闲回收跳过它们。 */
        val keepAliveNames = mutableSetOf<String>()
    }

    private val runtimes = mutableMapOf<String, WorkspaceRuntime>()

    /** 当前活动工作区：只有它的工具会挂到全局工具表上。 */
    private var activeWorkspace: String = ""

    /** server → 已注册的命名空间工具名（`mcp__server__tool`）；只反映活动工作区。 */
    private val registeredToolNames = mutableMapOf<String, List<String>>()
    /** 已注册的 server 属于哪个工作区：拆某个工作区的连接时只摘它自己的工具。 */
    private val registeredToolOwner = mutableMapOf<String, String>()

    /** 各 server 连续重连失败次数，键为「工作区|server」：不同工作区的同名 server 各自计数。 */
    private val reconnectAttempts = mutableMapOf<String, Int>()

    /** 低频兜底重试的协程（每「工作区|server」至多一个）。 */
    private val slowRetries = mutableMapOf<String, Job>()

    private val _statuses = MutableStateFlow<List<McpServerStatus>>(emptyList())

    /** 当前活动工作区的连接状态（设置页与工具提示用）。 */
    val statuses: StateFlow<List<McpServerStatus>> = _statuses.asStateFlow()

    /** 实际正在生效的工作区（工具表挂着的那一套）：与「本会话所属工作区」可能不同，工具提示要用。 */
    val activeWorkspacePath: String get() = activeWorkspace

    /** 指定工作区的连接状态：工具按「会话所属工作区」读取，不拿全局活动工作区的那份。 */
    fun statusesFor(workspacePath: String): List<McpServerStatus> =
        synchronized(runtimes) { runtimes[workspacePath]?.statuses.orEmpty() }

    /**
     * 由会话切换驱动：MCP 工具应该跟着「当前会话所属工作区」走，而不是跟着全局活动工作区。
     *
     * 否则在 Aharou 工作区开着的时候，小染工作区里正在跑的会话就拿不到它自己的项目级 server
     * （典型是桥接 bot）——工具表里全员都是 Aharou 那一套。
     */
    fun setActiveWorkspace(path: String) {
        if (path.isBlank()) return
        scope.launch { onActiveWorkspaceChanged(path) }
    }

    fun start() {
        // 工作区切换：不拆连接，只切换「工具表挂哪一套」，必要时首次加载该工作区的配置。
        scope.launch {
            workspaceRepository.current.collectLatest { workspace ->
                onActiveWorkspaceChanged(workspace?.path.orEmpty())
            }
        }
        // 配置文件被外部（容器内/手工）直接编辑：数秒内重载**活动工作区**，其它工作区等切换时按需加载。
        scope.launch {
            configRepository.externalChanges.collect {
                FileLogger.i(TAG, "检测到 MCP 配置变更，重载活动工作区")
                reload()
            }
        }
        // 空闲回收：非活动工作区放久了就拆连接（保活的 server 除外），避免每个工作区都常驻一批进程。
        scope.launch {
            while (true) {
                delay(EVICT_CHECK_MS)
                evictIdleServers()
            }
        }
        // 容器 profile 切换：stdio server 的进程跑在旧容器的 rootfs 上，所有工作区都要重建；HTTP 不受影响。
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

    private suspend fun onActiveWorkspaceChanged(path: String) = reloadMutex.withLock {
        if (path.isBlank() || path == activeWorkspace) return@withLock
        FileLogger.i(TAG, "活动工作区切换为 $path，切换 MCP 工具表")
        activeWorkspace = path
        val runtime = runtimes.getOrPut(path) { WorkspaceRuntime(path) }
        // 切过来算在用：该工作区现有的连接计时一并刷新，别刚切过来就被回收。
        val now = System.currentTimeMillis()
        synchronized(runtimes) { runtime.clients.keys.forEach { runtime.lastUsed[it] = now } }
        // 工具表的差异替换交给 registerToolsLocked：这里不再先全摘，否则共用 server 会空窗。
        if (runtime.statuses.isEmpty()) {
            loadWorkspaceLocked(path)
        } else {
            registerToolsLocked(path)
            _statuses.value = runtime.statuses
        }
    }

    /** 重载**活动工作区**（设置页手动重连、配置变更）。 */
    suspend fun reload() = reloadMutex.withLock {
        if (activeWorkspace.isBlank()) return@withLock
        loadWorkspaceLocked(activeWorkspace)
    }

    /** 加载指定工作区的全部 server（先拆该工作区旧连接）。调用方必须已持锁。 */
    private suspend fun loadWorkspaceLocked(path: String) {
        val servers = configRepository.getEffectiveServersFor(path)
        FileLogger.i(TAG, "[$path] 重新加载 MCP 配置，共 ${servers.size} 个 server")
        teardownWorkspaceLocked(path)
        val runtime = runtimes.getOrPut(path) { WorkspaceRuntime(path) }
        if (servers.isEmpty()) {
            runtime.statuses = emptyList()
            if (path == activeWorkspace) _statuses.value = emptyList()
            return
        }
        runtime.statuses = servers.map { cfg ->
            McpServerStatus(
                name = cfg.name,
                state = if (cfg.enabled) McpServerStatus.State.CONNECTING else McpServerStatus.State.DISABLED
            )
        }
        if (path == activeWorkspace) _statuses.value = runtime.statuses

        val results = withContext(Dispatchers.IO) {
            servers.filter { it.enabled }.map { cfg -> async { connectOne(cfg, path) } }.awaitAll()
        }
        val byName = results.associateBy { it.name }
        runtime.statuses = servers.map { cfg ->
            byName[cfg.name] ?: McpServerStatus(cfg.name, McpServerStatus.State.DISABLED)
        }
        if (path == activeWorkspace) {
            _statuses.value = runtime.statuses
            registerToolsLocked(path)
        }
    }

    /**
     * 把指定工作区已连上的工具挂到全局工具表（先摘旧）。
     *
     * 只在活动工作区调用：工具名全局唯一，两个工作区同名 server 只能挂一套。
     */
    private fun registerToolsLocked(path: String) {
        val runtime = runtimes[path] ?: return
        synchronized(registeredToolNames) {
            val wanted = mutableSetOf<String>()
            // 先挂新的（同名直接覆盖），最后再摘多余的：共用的 server（全局）在切换过程中不会消失，
            // 否则每切一次会话，工具表里那些两份工作区都有的工具都会短暂空窗。
            runtime.clients.forEach { (serverName, client) ->
                val status = runtime.statuses.firstOrNull { it.name == serverName } ?: return@forEach
                if (status.state != McpServerStatus.State.CONNECTED) return@forEach
                val tools = client.tools.map { tool -> newTool(client, tool, path, serverName) }
                if (tools.isEmpty()) return@forEach
                tools.forEach { toolRegistry.register(it.name, it) }
                registeredToolNames[serverName] = tools.map { it.name }
                registeredToolOwner[serverName] = path
                wanted += serverName
            }
            registeredToolNames.keys.filter { it !in wanted }.forEach { server ->
                registeredToolNames.remove(server)?.forEach { toolRegistry.unregister(it) }
                registeredToolOwner.remove(server)
            }
        }
    }

    private suspend fun connectOne(cfg: McpServerConfig, workspacePath: String): McpServerStatus {
        val t0 = System.currentTimeMillis()
        var created: McpClient? = null
        // transport 在 try 之外声明：client 构造失败时 created 还是 null，得靠它兜底 close。
        var transport: McpTransport? = null
        return try {
            FileLogger.i(TAG, "[${cfg.name}] 开始连接（${if (cfg.isStdio) "stdio" else "HTTP"}，工作区 $workspacePath）")
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
                    // 绑定**本工作区**：不同工作区的同名 server 各自跑在自己的项目目录里。
                    projectPath = workspacePath,
                    extraEnv = cfg.env,
                    runtimeProfile = runtimeProfile,
                    // stdio 进程死亡 / 管道断开：自报死亡，由 onTransportClosed 摘除死连接并按退避重连。
                    onClosed = { onTransportClosed(workspacePath, cfg.name, created) }
                )
            } else {
                StreamableHttpTransport(
                    // 配置里的密钥是占位符，这里必须换成真值——且要在传输层内部解析 URL 之前，
                    // query 形态的 URL 才能拼对。strict=false：密钥被删时保留占位符、由连接以 401
                    // 暴露问题，比 connectOne 直接抛异常更好排查。
                    endpoint = vault.resolve(cfg.url.orEmpty(), strict = false),
                    client = okHttpClient,
                    extraHeaders = cfg.headers.mapValues { (_, v) -> vault.resolve(v, strict = false) },
                    // 配了 oauth 块的 server 由提供者在每次请求前注入/刷新 Bearer 令牌。
                    authProvider = if (cfg.oauth != null) oauthClient.bearerProvider(cfg.name) else null
                )
            }
            transport = t
            val client = McpClient(serverName = cfg.name, transport = t).also { created = it }
            client.connect()

            val tools = client.tools.map { tool -> newTool(client, tool, workspacePath, cfg.name) }
            val enabledTools = tools.filter { it.remoteName !in cfg.disabledTools }
            synchronized(runtimes) {
                runtimes[workspacePath]?.let { rt ->
                rt.clients[cfg.name] = client
                rt.lastUsed[cfg.name] = System.currentTimeMillis()
                if (cfg.keepAlive) rt.keepAliveNames += cfg.name else rt.keepAliveNames -= cfg.name
            }
            }
            if (workspacePath == activeWorkspace) {
                // 重连成功时先清同名旧工具再注册新的：旧连接被 close 后，注册表里那些
                // McpTool 还指着已关闭的 transport，调用时就会报「transport 已关闭」。
                synchronized(registeredToolNames) {
                    if (registeredToolOwner[cfg.name] == workspacePath) {
                        registeredToolNames.remove(cfg.name)?.forEach { toolRegistry.unregister(it) }
                    }
                    enabledTools.forEach { toolRegistry.register(it.name, it) }
                    registeredToolNames[cfg.name] = enabledTools.map { it.name }
                    registeredToolOwner[cfg.name] = workspacePath
                }
            }
            FileLogger.i(
                TAG,
                "[${cfg.name}] 连接成功，${enabledTools.size} 个工具（${System.currentTimeMillis() - t0}ms，工作区 $workspacePath）"
            )
            McpServerStatus(cfg.name, McpServerStatus.State.CONNECTED, toolCount = enabledTools.size)
        } catch (e: CancellationException) {
            // 取消不是失败：先收掉半开连接再原样抛出，别让 stdio 子进程与并发名额跟着泄漏。
            runCatching { created?.close() ?: transport?.close() }
            throw e
        } catch (e: Exception) {
            runCatching { created?.close() ?: transport?.close() }
            reconnectAttempts.remove(retryKey(workspacePath, cfg.name))
            FileLogger.e(TAG, "[${cfg.name}] 连接失败（${System.currentTimeMillis() - t0}ms）", e)
            McpServerStatus(cfg.name, McpServerStatus.State.FAILED, error = e.message)
        }
    }

    fun getServerTools(serverName: String): List<McpToolDescriptor> {
        synchronized(runtimes) {
            return runtimes[activeWorkspace]?.clients?.get(serverName)?.tools ?: emptyList()
        }
    }

    /** 仅重连活动工作区的单个 server（保存/启用开关/编辑刷新用）。 */
    suspend fun reloadServer(name: String) = reloadMutex.withLock {
        val path = activeWorkspace.ifBlank { return@withLock }
        val cfg = configRepository.getEffectiveServersFor(path).firstOrNull { it.name == name } ?: return@withLock
        withContext(Dispatchers.IO) { teardownServerLocked(path, name) }
        if (!cfg.enabled) {
            updateStatus(path, McpServerStatus(name, McpServerStatus.State.DISABLED))
            return@withLock
        }
        reconnectOne(cfg, path)
    }

    /** 仅重连活动工作区里未连接的 server（新会话兜底）。 */
    suspend fun reconnectUnconnected() = reloadMutex.withLock {
        val path = activeWorkspace.ifBlank { return@withLock }
        val servers = configRepository.getEffectiveServersFor(path).filter { it.enabled }
        for (cfg in servers) {
            val alive = synchronized(runtimes) { runtimes[path]?.clients?.get(cfg.name)?.isAlive == true }
            if (alive) continue
            teardownServerLocked(path, cfg.name)
            reconnectOne(cfg, path)
        }
    }

    /** 异步兜底重连未连接的 server（不阻塞调用方）；新会话创建不该等 MCP 就绪。 */
    fun reconnectUnconnectedAsync() {
        scope.launch { reconnectUnconnected() }
    }

    private suspend fun reconnectOne(cfg: McpServerConfig, workspacePath: String) {
        updateStatus(workspacePath, McpServerStatus(cfg.name, McpServerStatus.State.CONNECTING))
        val result = withContext(Dispatchers.IO) { connectOne(cfg, workspacePath) }
        updateStatus(workspacePath, result)
    }

    private fun updateStatus(path: String, status: McpServerStatus) {
        val runtime = runtimes.getOrPut(path) { WorkspaceRuntime(path) }
        runtime.statuses = if (runtime.statuses.any { it.name == status.name }) {
            runtime.statuses.map { if (it.name == status.name) status else it }
        } else {
            runtime.statuses + status
        }
        if (path == activeWorkspace) _statuses.value = runtime.statuses
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
     * 容器相关配置变化后重建所有工作区的 stdio server（HTTP 不依赖容器，不动）。
     * 旧进程钉在旧容器的 rootfs 上，必须 teardown 后才能用新容器拉起；单个 server 失败不影响其它。
     */
    suspend fun reloadStdioServers() = reloadMutex.withLock {
        val paths = synchronized(runtimes) { runtimes.keys.toList() }.ifEmpty { listOf(activeWorkspace) }
        for (path in paths.filter { it.isNotBlank() }) {
            val servers = configRepository.getEffectiveServersFor(path).filter { it.enabled && it.isStdio }
            if (servers.isEmpty()) continue
            FileLogger.i(TAG, "[$path] 容器配置变化，重建 ${servers.size} 个 stdio MCP server")
            for (cfg in servers) {
                teardownServerLocked(path, cfg.name)
                reconnectOne(cfg, path)
            }
        }
    }

    /** 删除 server 时仅断开活动工作区里该 server 的连接并反注册其工具。 */
    suspend fun removeServer(name: String) = reloadMutex.withLock {
        val path = activeWorkspace.ifBlank { return@withLock }
        withContext(Dispatchers.IO) { teardownServerLocked(path, name) }
        val runtime = runtimes[path]
        runtime?.statuses = runtime.statuses.filterNot { it.name == name }
        if (path == activeWorkspace) _statuses.value = runtime?.statuses.orEmpty()
    }

    private fun teardownServerLocked(path: String, name: String) {
        val client = synchronized(runtimes) { runtimes[path]?.clients?.remove(name) }
        synchronized(registeredToolNames) {
            // 同名 server 可能已被别的工作区接管（工具表只挂一套），不是自己挂的就不要摘。
            if (registeredToolOwner[name] == path) {
                registeredToolNames.remove(name)?.forEach { toolRegistry.unregister(it) }
                registeredToolOwner.remove(name)
            }
        }
        runCatching { client?.close() }
    }

    /** 构造带「重连 + 使用登记」回调的 MCP 工具。 */
    private fun newTool(client: McpClient, tool: McpToolDescriptor, path: String, server: String): McpTool =
        McpTool(
            client = client,
            descriptor = tool,
            onReconnect = { ensureClient(path, server) },
            onUsed = { touch(path, server) }
        )

    /** 拆掉某工作区的全部连接（释放进程、清状态）。 */
    private fun teardownWorkspaceLocked(path: String) {
        val clients = synchronized(runtimes) {
            val runtime = runtimes[path] ?: return@synchronized emptyList()
            val snapshot = runtime.clients.values.toList()
            runtime.clients.clear()
            runtime.statuses = emptyList()
            snapshot
        }
        synchronized(registeredToolNames) {
            // 只摘属于该工作区的工具：以前是全摘，回收一个空闲工作区会把活动工作区的工具一起弄没。
            registeredToolOwner.filterValues { it == path }.keys.toList().forEach { server ->
                registeredToolNames.remove(server)?.forEach { toolRegistry.unregister(it) }
                registeredToolOwner.remove(server)
            }
        }
        clients.forEach { runCatching { it.close() } }
        if (path == activeWorkspace) _statuses.value = emptyList()
    }

    /** 按 server 判空闲：超过 [IDLE_EVICT_MS] 没被调用就拆（保活的不拆），下次调用自动重连。 */
    private fun evictIdleServers() {
        scope.launch {
            reloadMutex.withLock {
                val now = System.currentTimeMillis()
                val victims = synchronized(runtimes) {
                    runtimes.values.flatMap { rt ->
                        rt.clients.keys.filter { name ->
                            name !in rt.keepAliveNames && now - (rt.lastUsed[name] ?: now) > IDLE_EVICT_MS
                        }.map { name -> rt.path to name }
                    }
                }
                victims.forEach { (path, name) ->
                    FileLogger.i(TAG, "[$path] $name 空闲超过 15 分钟，回收连接（再次调用时自动重连）")
                    teardownServerLocked(path, name)
                }
            }
        }
    }

    /** 工具调用前登记使用时间。 */
    private fun touch(workspacePath: String, serverName: String) {
        synchronized(runtimes) {
            runtimes[workspacePath]?.lastUsed?.put(serverName, System.currentTimeMillis())
        }
    }

    /** 连接被空闲回收后按需重连，供工具调用失败时重试。 */
    private suspend fun ensureClient(workspacePath: String, serverName: String): McpClient? =
        reloadMutex.withLock {
            synchronized(runtimes) { runtimes[workspacePath]?.clients?.get(serverName) }
                ?.let { return@withLock it }
            val cfg = runCatching { configRepository.getEffectiveServersFor(workspacePath) }
                .getOrNull()?.firstOrNull { it.name == serverName && it.enabled }
                ?: return@withLock null
            runCatching { connectOne(cfg, workspacePath) }
            synchronized(runtimes) { runtimes[workspacePath]?.clients?.get(serverName) }
        }

    /**
     * stdio 进程死亡 / 管道断开：摘掉该工作区这条连接、反注册工具、状态置 FAILED，再按退避重连。
     *
     * 只在「死的正是当前登记的那条连接」时动手：显式 reload / removeServer 已清掉条目的情形
     * 直接跳过；连接尚在建立中就死亡（client 还是 null）也跳过，那条路径由 [connectOne] 兜底。
     */
    private fun onTransportClosed(workspacePath: String, serverName: String, client: McpClient?) {
        val takenOver = synchronized(runtimes) {
            val runtime = runtimes[workspacePath]
            if (client != null && runtime?.clients?.get(serverName) === client) {
                runtime.clients.remove(serverName)
                true
            } else {
                false
            }
        }
        if (!takenOver) return
        if (workspacePath == activeWorkspace) {
            synchronized(registeredToolNames) {
                registeredToolNames.remove(serverName)?.forEach { toolRegistry.unregister(it) }
            }
        }
        FileLogger.w(TAG, "[$serverName] 连接已断开（stdio 进程退出或管道断开，工作区 $workspacePath），准备重连")
        updateStatus(workspacePath, McpServerStatus(serverName, McpServerStatus.State.FAILED, error = "连接已断开"))
        scheduleReconnect(workspacePath, serverName)
    }

    private fun retryKey(workspacePath: String, serverName: String): String = "$workspacePath|$serverName"

    /** 断线重连：按 [RECONNECT_DELAYS_MS] 退避，最多 [MAX_RECONNECT_ATTEMPTS] 次；连上即清零计数并停止。 */
    private fun scheduleReconnect(
        workspacePath: String,
        serverName: String,
        attempt: Int = (reconnectAttempts[retryKey(workspacePath, serverName)] ?: 0) + 1
    ) {
        val key = retryKey(workspacePath, serverName)
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            FileLogger.w(
                TAG,
                "[$serverName] 快速重连已用完，转入低频重试（每 ${SLOW_RETRY_MS / 1000} 秒一次，直到连上或被禁用）"
            )
            reconnectAttempts.remove(key)
            scheduleSlowRetry(workspacePath, serverName)
            return
        }
        reconnectAttempts[key] = attempt
        val delayMs = RECONNECT_DELAYS_MS[(attempt - 1).coerceAtMost(RECONNECT_DELAYS_MS.lastIndex)]
        scope.launch {
            delay(delayMs)
            val cfg = configRepository.getEffectiveServersFor(workspacePath)
                .firstOrNull { it.name == serverName } ?: return@launch
            if (!cfg.enabled) return@launch
            reloadMutex.withLock {
                val alive = synchronized(runtimes) {
                    runtimes[workspacePath]?.clients?.get(serverName)?.isAlive == true
                }
                if (alive) return@withLock
                FileLogger.i(TAG, "[$serverName] 断线重连第 $attempt 次（工作区 $workspacePath）")
                teardownServerLocked(workspacePath, serverName)
                reconnectOne(cfg, workspacePath)
            }
            val connected = runtimes[workspacePath]?.statuses
                ?.firstOrNull { it.name == serverName }?.state == McpServerStatus.State.CONNECTED
            if (connected) {
                reconnectAttempts.remove(key)
            } else {
                // 显式带上下一次次数：connectOne 失败时会清掉 map 里的记录，靠 map 读会永远算回第 1 次。
                scheduleReconnect(workspacePath, serverName, attempt + 1)
            }
        }
    }

    /** 快速重试用尽后的低频兜底重试：直到连上、被禁用或被删除。同「工作区|server」至多一个在跑。 */
    private fun scheduleSlowRetry(workspacePath: String, serverName: String) {
        val key = retryKey(workspacePath, serverName)
        if (synchronized(slowRetries) { slowRetries[key]?.isActive == true }) return
        val job = scope.launch {
            delay(SLOW_RETRY_MS)
            synchronized(slowRetries) { slowRetries.remove(key) }
            val cfg = configRepository.getEffectiveServersFor(workspacePath)
                .firstOrNull { it.name == serverName } ?: return@launch
            if (!cfg.enabled) return@launch
            reloadMutex.withLock {
                val alive = synchronized(runtimes) {
                    runtimes[workspacePath]?.clients?.get(serverName)?.isAlive == true
                }
                if (alive) return@withLock
                FileLogger.i(TAG, "[$serverName] 低频重试（工作区 $workspacePath）")
                teardownServerLocked(workspacePath, serverName)
                reconnectOne(cfg, workspacePath)
            }
            val connected = runtimes[workspacePath]?.statuses
                ?.firstOrNull { it.name == serverName }?.state == McpServerStatus.State.CONNECTED
            if (!connected) scheduleSlowRetry(workspacePath, serverName)
        }
        synchronized(slowRetries) { slowRetries[key] = job }
    }

}
