package com.aharou.feature.agent.domain.mcp

import com.aharou.core.security.KeystoreCipher
import com.aharou.core.util.FileLogger
import com.aharou.core.util.writeTextSafely
import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** MCP server 的配置作用域：全局（跨项目共享）或项目级（仅当前工作区生效）。 */
enum class McpScope { GLOBAL, PROJECT }

/** 一个生效的 MCP 条目：配置 + 其来源作用域，供 UI 标注「全局/项目」。 */
data class McpServerEntry(
    val server: McpServerConfig,
    val scope: McpScope
)

/**
 * MCP 配置持久化，支持全局 + 项目级两级：
 * - 全局：`filesDir/aicode/mcp.json`（跨项目、跨升级保留）；
 * - 项目级：`workspacePath/.aicode/mcp.json`（随工作区走，可 git 追踪）。
 *
 * 生效配置 = 全局 + 项目合并，**项目级优先**，同名时项目项覆盖全局项。
 * 并发模式：Mutex 保护文件 IO + MutableStateFlow 缓存，项目级按工作区路径各自缓存。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class McpConfigRepository @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val workspaceRepository: WorkspaceRepository,
    private val projectAicodeRoot: ProjectAharouRoot,
    private val fileChangeHub: FileChangeHub
) {
    private companion object {
        const val TAG = "McpConfigRepository"
        const val CONFIG_FILE = "mcp.json"
        const val DEFAULT_JSON = """{"mcpServers":{}}"""
        /** 项目级配置目录名，容器内即 `~/workspace/.aicode`。 */
        const val AHAROU_DIR_NAME = ".aharou"
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
        val PRETTY_JSON = Json { prettyPrint = true }
    }

    /** 全局配置文件：`filesDir/aicode/mcp.json`。 */
    private val globalFile: File
        get() = File(containerInstaller.aharouDir, CONFIG_FILE)

    /** 当前工作区的项目级配置文件：`workspacePath/.aicode/mcp.json`。 */
    private fun projectFileForPath(workspacePath: String): File =
        File(projectAicodeRoot.forPath(workspacePath), CONFIG_FILE)

    private val globalState = MutableStateFlow<String?>(null)
    private val projectStates = ConcurrentHashMap<String, MutableStateFlow<String?>>()
    private val mutex = Mutex()

    // ── 外部修改监听：容器内/手工直接编辑配置文件后，刷新缓存并广播给 McpManager 重连 ──

    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 本进程写入配置后的广播。
     *
     * 文件监听只认「外部改动」：本进程写完会把缓存同步成同一份内容，[reloadFromDisk] 比对下来是
     * 「没变」，于是不广播——新增的 server 便一直连不上（用户看到的是「配置在了但工具没出来」）。
     * 所以写入路径自己喊一声。
     */
    private val selfChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 16)

    /**
     * 配置发生实际变化（外部改动或本进程写入）时广播一次。订阅驱动：只在有订阅者（McpManager）期间
     * 才由 [FileChangeHub] 监听两个配置文件所在目录，无人订阅时零开销。
     */
    val externalChanges: SharedFlow<Unit> = merge(
        merge(
            fileChangeHub.watchAicode(),
            fileChangeHub.watchWorkspace("${FileChangeHub.CONTAINER_ROOT}/$AHAROU_DIR_NAME")
        ).mapNotNull { batch ->
            val globalPath = globalFile.absolutePath
            val projectPath = projectFileForPath(workspaceRepository.currentPath()).absolutePath
            val touched = batch.changes.any { it.hostPath == globalPath || it.hostPath == projectPath }
            // 同目录下其它文件的变更（如 skills.json）不触发重连；内容没真变（如 touch）也不触发。
            if (!touched) null else if (reloadFromDisk()) Unit else null
        },
        selfChanges.asSharedFlow()
    ).shareIn(watchScope, SharingStarted.WhileSubscribed(), replay = 0)

    /**
     * 外部变更后按磁盘现状刷新缓存，内容确实变化时返回 true。项目级只处理当前工作区；
     * 其缓存尚未加载时不广播——首次加载由 [ensureProjectLoaded] 完成，工作区切换不算外部变更。
     */
    private suspend fun reloadFromDisk(): Boolean {
        var changed = false
        val globalContent = load(globalFile)
        if (globalContent != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = globalContent
            changed = true
            FileLogger.i(TAG, "检测到全局 MCP 配置变化，已刷新")
        }
        val path = workspaceRepository.currentPath()
        val state = getProjectState(path)
        if (state.value != null) {
            val content = load(projectFileForPath(path))
            if (content != state.value) {
                state.value = content
                changed = true
                FileLogger.i(TAG, "检测到项目 MCP 配置变化，已刷新")
            }
        }
        return changed
    }

    private fun getProjectState(workspacePath: String): MutableStateFlow<String?> =
        projectStates.getOrPut(workspacePath) { MutableStateFlow(null) }

    private suspend fun ensureGlobalLoaded() {
        if (globalState.value != null) return
        mutex.withLock {
            if (globalState.value != null) return
            globalState.value = load(globalFile)
        }
    }

    private suspend fun ensureProjectLoaded(workspacePath: String) {
        val state = getProjectState(workspacePath)
        if (state.value != null) return
        mutex.withLock {
            if (state.value != null) return
            state.value = load(projectFileForPath(workspacePath))
        }
    }

    private suspend fun load(file: File): String = withContext(Dispatchers.IO) {
        // 只读加载：文件不存在时返回默认配置，不主动创建目录/文件，
        // 避免在 projectsRoot 下误建 .aicode 目录被工作区扫描器当成工作区。
        if (file.isFile) {
            return@withContext runCatching { file.readText() }.getOrElse {
                FileLogger.w(TAG, "读取 ${file.name} 失败，回退默认配置: ${it.message}")
                DEFAULT_JSON
            }
        }
        DEFAULT_JSON
    }

    /** 写入配置文件；失败不抛（该目录被绑进容器，权限可能被容器内 root 环境改坏）。 */
    private fun writeFile(file: File, json: String): Boolean = file.writeTextSafely(json, TAG)

    /** 全局 MCP 配置流。 */
    val globalServersFlow: Flow<List<McpServerConfig>> = flow {
        ensureGlobalLoaded()
        emitAll(globalState.filterNotNull().map { parse(it) })
    }

    /**
     * 当前项目生效的 MCP 条目流（全局 + 项目合并，项目优先覆盖同名），
     * 跟随当前工作区切换自动重载对应项目配置。
     */
    val effectiveEntriesFlow: Flow<List<McpServerEntry>> =
        workspaceRepository.current.flatMapLatest {
            val path = workspaceRepository.currentPath()
            ensureGlobalLoaded()
            ensureProjectLoaded(path)
            combine(globalState, getProjectState(path)) { g, p ->
                merge(parse(g ?: DEFAULT_JSON), parse(p ?: DEFAULT_JSON))
            }
        }

    suspend fun getGlobalServers(): List<McpServerConfig> {
        ensureGlobalLoaded()
        return parse(globalState.value ?: DEFAULT_JSON)
    }

    suspend fun getProjectServers(workspacePath: String = workspaceRepository.currentPath()): List<McpServerConfig> {
        ensureProjectLoaded(workspacePath)
        return parse(getProjectState(workspacePath).value ?: DEFAULT_JSON)
    }

    /** @return 是否真的落盘成功。写失败时只记日志、保留原状态，调用方必须据此报错——
     *  否则工具会回「添加成功」而磁盘上什么都没变。 */
    suspend fun setGlobalServers(servers: List<McpServerConfig>): Boolean {
        return mutex.withLock {
            // 写前与磁盘对齐：调用方拿的是内存快照，而用户可能在容器里直接改过 mcp.json，
            // 直接用旧快照整表覆盖会把他刚删/刚加的 server 抹掉（已发生过：删了 bot 又自己回来）。
            if (!alignWithDiskLocked()) return@withLock false
            // serialize 会做 Keystore 加密，放到 IO 线程；别在（可能为 Main 的）调用方线程上做。
            val json = withContext(Dispatchers.IO) { serialize(servers) }
            if (withContext(Dispatchers.IO) { writeFile(globalFile, json) }) {
                globalState.value = json
                selfChanges.tryEmit(Unit)
                true
            } else {
                FileLogger.w(TAG, "全局 MCP 配置未能落盘，保持原状态")
                false
            }
        }
    }

    /** @return 是否真的落盘成功（口径同 [setGlobalServers]）。 */
    suspend fun setProjectServers(
        servers: List<McpServerConfig>,
        workspacePath: String = workspaceRepository.currentPath()
    ): Boolean {
        return mutex.withLock {
            if (!alignWithDiskLocked(workspacePath)) return@withLock false
            val json = withContext(Dispatchers.IO) { serialize(servers) }
            if (withContext(Dispatchers.IO) { writeFile(projectFileForPath(workspacePath), json) }) {
                getProjectState(workspacePath).value = json
                selfChanges.tryEmit(Unit)
                true
            } else {
                FileLogger.w(TAG, "项目 MCP 配置未能落盘（$workspacePath），保持原状态")
                false
            }
        }
    }

    /**
     * 写前对齐：磁盘内容与内存不一致（外部直接改过文件）时，以磁盘为准刷新内存并拒绝本次写入。
     *
     * 拒绝而不是合并：调用方拿的是整表快照，合并语义不明确；让它重试一次，第二次读到新内存后
     * 自然基于最新内容做修改。
     *
     * @return true 表示内存与磁盘一致，可以继续写。
     */
    private suspend fun alignWithDiskLocked(workspacePath: String? = null): Boolean {
        val onDisk = withContext(Dispatchers.IO) { load(globalFile) }
        if (onDisk != (globalState.value ?: DEFAULT_JSON)) {
            globalState.value = onDisk
            FileLogger.w(TAG, "全局 MCP 配置在内存外被改动，已以磁盘为准刷新，拒绝本次覆盖；请重试")
            return false
        }
        if (workspacePath != null) {
            val state = getProjectState(workspacePath)
            if (state.value == null) return true
            val projectOnDisk = withContext(Dispatchers.IO) { load(projectFileForPath(workspacePath)) }
            if (projectOnDisk != state.value) {
                state.value = projectOnDisk
                FileLogger.w(TAG, "项目 MCP 配置在内存外被改动（$workspacePath），已刷新，拒绝本次覆盖；请重试")
                return false
            }
        }
        return true
    }

    /** 当前项目生效的合并配置（项目优先覆盖同名），供 [McpManager] 连接使用。 */
    suspend fun getEffectiveServers(): List<McpServerConfig> =
        getEffectiveEntries().map { it.server }

    /** 按**指定工作区**读合并配置：[McpManager] 按工作区维护连接时用它，不能用 currentPath。 */
    suspend fun getEffectiveServersFor(workspacePath: String): List<McpServerConfig> =
        getEffectiveEntriesFor(workspacePath).map { it.server }

    suspend fun getEffectiveEntriesFor(workspacePath: String): List<McpServerEntry> {
        ensureGlobalLoaded()
        ensureProjectLoaded(workspacePath)
        return merge(
            parse(globalState.value ?: DEFAULT_JSON),
            parse(getProjectState(workspacePath).value ?: DEFAULT_JSON)
        )
    }

    /** 当前项目生效的合并条目（含来源作用域），供设置页列表标注使用。 */
    suspend fun getEffectiveEntries(): List<McpServerEntry> {
        val path = workspaceRepository.currentPath()
        ensureGlobalLoaded()
        ensureProjectLoaded(path)
        return merge(
            parse(globalState.value ?: DEFAULT_JSON),
            parse(getProjectState(path).value ?: DEFAULT_JSON)
        )
    }

    fun serialize(servers: List<McpServerConfig>): String {
        val serversObj = buildJsonObject {
            servers.forEach { server ->
                putJsonObject(server.name) {
                    if (server.isStdio) {
                        put("command", server.command)
                        if (server.args.isNotEmpty()) {
                            putJsonArray("args") { server.args.forEach { add(it) } }
                        }
                        if (server.env.isNotEmpty()) {
                            putJsonObject("env") {
                                // 与 headers 同理：env 也可能装密钥（如 MCP stdio 服务器的 TOKEN），落盘前一并加密。
                                server.env.forEach { (key, value) -> put(key, encryptSensitive(key, value)) }
                            }
                        }
                    } else {
                        put("url", server.url ?: "")
                        if (server.headers.isNotEmpty()) {
                            putJsonObject("headers") {
                                // 敏感名（Authorization / 含 token·key·secret·password）落盘前加密：
                                // 项目级配置在 <工作区>/.aharou/mcp.json 里可能被 git 追踪推走。
                                server.headers.forEach { (key, value) -> put(key, encryptSensitive(key, value)) }
                            }
                        }
                        server.oauth?.let { oauth ->
                            putJsonObject("oauth") {
                                put("clientId", oauth.clientId)
                                if (oauth.clientSecret.isNotBlank()) put("clientSecret", encryptSecret(oauth.clientSecret))
                                put("authorizationEndpoint", oauth.authorizationEndpoint)
                                put("tokenEndpoint", oauth.tokenEndpoint)
                                if (oauth.registrationEndpoint.isNotBlank()) put("registrationEndpoint", oauth.registrationEndpoint)
                                put("redirectUri", oauth.redirectUri)
                                if (oauth.scope.isNotBlank()) put("scope", oauth.scope)
                                if (oauth.resource.isNotBlank()) put("resource", oauth.resource)
                                if (oauth.accessToken.isNotBlank()) put("accessToken", encryptSecret(oauth.accessToken))
                                if (oauth.refreshToken.isNotBlank()) put("refreshToken", encryptSecret(oauth.refreshToken))
                                put("tokenType", oauth.tokenType)
                                put("expiresAt", oauth.expiresAt)
                                if (oauth.authorizationServer.isNotBlank()) put("authorizationServer", oauth.authorizationServer)
                            }
                        }
                    }
                    put("enabled", server.enabled)
                    if (server.disabledTools.isNotEmpty()) {
                        putJsonArray("disabledTools") { server.disabledTools.forEach { add(it) } }
                    }
                }
            }
        }
        val root = buildJsonObject { put("mcpServers", serversObj) }
        return PRETTY_JSON.encodeToString(JsonObject.serializer(), root)
    }

    fun parse(raw: String): List<McpServerConfig> {
        val root = runCatching { JSON.parseToJsonElement(raw).jsonObject }.getOrElse {
            FileLogger.w(TAG, "MCP 配置 JSON 解析失败: ${it.message}")
            return emptyList()
        }
        val servers = (root["mcpServers"] as? JsonObject) ?: return emptyList()

        return servers.mapNotNull { (name, element) ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val enabled = (obj["enabled"] as? JsonPrimitive)?.booleanOrNull ?: true

            val command = (obj["command"] as? JsonPrimitive)?.contentOrNull
            val url = (obj["url"] as? JsonPrimitive)?.contentOrNull

            val disabledTools = (obj["disabledTools"] as? JsonArray)?.mapNotNull {
                (it as? JsonPrimitive)?.contentOrNull
            }?.toSet() ?: emptySet()

            when {
                !command.isNullOrBlank() -> {
                    val args = (obj["args"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.contentOrNull
                    } ?: emptyList()
                    val env = (obj["env"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to decryptSensitive(k, it) }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        command = command,
                        args = args,
                        env = env,
                        enabled = enabled,
                        disabledTools = disabledTools
                    )
                }
                !url.isNullOrBlank() -> {
                    val headers = (obj["headers"] as? JsonObject)?.mapNotNull { (k, v) ->
                        (v as? JsonPrimitive)?.contentOrNull?.let { k to decryptSensitive(k, it) }
                    }?.toMap() ?: emptyMap()
                    McpServerConfig(
                        name = name,
                        url = url,
                        headers = headers,
                        enabled = enabled,
                        disabledTools = disabledTools,
                        oauth = parseOAuth(obj["oauth"] as? JsonObject)
                    )
                }
                else -> {
                    // 既无 url 也无 command，无法识别，跳过。
                    FileLogger.i(TAG, "跳过无法识别的 MCP server（缺 url/command）: $name")
                    null
                }
            }
        }
    }

    /** 解析 oauth 块；敏感字段按落盘密文原样读出，使用方自己用 KeystoreCipher 解密。 */
    private fun parseOAuth(obj: JsonObject?): McpOAuthConfig? {
        obj ?: return null
        fun str(key: String): String = (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        return McpOAuthConfig(
            clientId = str("clientId"),
            clientSecret = str("clientSecret"),
            authorizationEndpoint = str("authorizationEndpoint"),
            tokenEndpoint = str("tokenEndpoint"),
            registrationEndpoint = str("registrationEndpoint"),
            redirectUri = str("redirectUri").ifBlank { MCP_OAUTH_CUSTOM_SCHEME_REDIRECT },
            scope = str("scope"),
            resource = str("resource"),
            accessToken = str("accessToken"),
            refreshToken = str("refreshToken"),
            tokenType = str("tokenType").ifBlank { "Bearer" },
            expiresAt = (obj["expiresAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
            authorizationServer = str("authorizationServer")
        )
    }

    /** 敏感字段落盘前确保为密文；已是密文则不重复加密。 */
    private fun encryptSecret(value: String): String =
        if (KeystoreCipher.isEncryptedString(value)) value else KeystoreCipher.encryptString(value)

    /**
     * 敏感 header 名：`Authorization`，或名字含 token / key / secret / password（大小写不敏感）。
     * 值落盘前加密，避免项目级 mcp.json 被 git 追踪时把密钥随文件带出去。
     */
    private fun isSensitiveName(name: String): Boolean {
        val n = name.lowercase()
        return n == "authorization" || n.contains("token") || n.contains("key") ||
            n.contains("secret") || n.contains("password")
    }

    /** 敏感 header 落盘前确保为密文；非敏感名原样返回。 */
    private fun encryptSensitive(name: String, value: String): String =
        if (isSensitiveName(name)) encryptSecret(value) else value

    /**
     * 敏感 header 读出时解密。向后兼容：旧明文值不是密文，[KeystoreCipher.decryptString] 对非密文
     * 原样返回；解密失败（如换机丢 Keystore 密钥）也回退原文，不阻断配置加载。
     */
    private fun decryptSensitive(name: String, value: String): String =
        if (isSensitiveName(name)) runCatching { KeystoreCipher.decryptString(value) }.getOrDefault(value) else value

    /** 按名称查找当前生效条目（含来源作用域）；不存在返回 null。 */
    suspend fun findEntry(name: String): McpServerEntry? =
        getEffectiveEntries().firstOrNull { it.server.name == name }

    /** 把某个 server 的 oauth 块整体替换后写回所属作用域。 */
    suspend fun updateServerOAuth(name: String, scope: McpScope, oauth: McpOAuthConfig?) {
        val base = if (scope == McpScope.GLOBAL) getGlobalServers() else getProjectServers()
        if (base.none { it.name == name }) return
        val updated = base.map { if (it.name == name) it.copy(oauth = oauth) else it }
        if (scope == McpScope.GLOBAL) setGlobalServers(updated) else setProjectServers(updated)
    }

    /** 合并全局与项目配置：全局按序在前，项目项覆盖同名，顺序 = 全局序 + 项目新增项。 */
    private fun merge(
        global: List<McpServerConfig>,
        project: List<McpServerConfig>
    ): List<McpServerEntry> {
        val byName = LinkedHashMap<String, McpServerEntry>()
        global.forEach { byName[it.name] = McpServerEntry(it, McpScope.GLOBAL) }
        project.forEach { byName[it.name] = McpServerEntry(it, McpScope.PROJECT) }
        return byName.values.toList()
    }
}