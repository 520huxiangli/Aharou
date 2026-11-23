package com.aharou.feature.plugin.runtime

import com.aharou.core.util.FileLogger
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.plugin.domain.PluginHostApiOrigin
import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.PluginRuntime
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.plugin.overlay.PluginOverlayManager
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.PathHomeResolver
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 插件运行时的宿主门面：按插件版本管理容器内进程，把工具/hook 调用转成 JSON-RPC。
 *
 * 进程在本地容器中运行（SSH 模式下同样在手机端），cwd 是插件自己的安装目录，
 * 因此可以直接读取包内文件、`node_modules` 或虚拟环境。插件主动发来的 host API
 * 请求交给 [PluginHostApiDispatcher]，与页面桥共用同一套方法与文件限制。
 */
@Singleton
class PluginRuntimeManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val pluginManager: PluginManager,
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val fileAccess: FileAccessProvider,
    private val homeResolver: PathHomeResolver,
    private val workspaceRepository: WorkspaceRepository,
    private val executionModeHolder: ExecutionModeHolder,
    private val eventBus: PluginEventBus,
    private val overlayManager: PluginOverlayManager,
    private val hostApiDispatcher: PluginHostApiDispatcher
) {

    private class Entry(
        val pluginId: String,
        val key: String,
        val transport: PluginProcessTransport,
        var lastUsed: Long,
        var resident: Boolean
    )

    private val processes = ConcurrentHashMap<String, Entry>()

    /** 打开中的面板给进程打的租约计数，键与 [processes] 一致：`<插件ID>@<版本>`。 */
    private val leases = ConcurrentHashMap<String, Int>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        overlayManager.setEventSink { pluginId, overlayId, payload ->
            scope.launch {
                val plugin = pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
                    ?: return@launch
                val runtime = plugin.content.runtime ?: return@launch
                runCatching {
                    invoke(
                        pluginId = pluginId,
                        version = plugin.record.version,
                        runtime = runtime,
                        handler = "overlay.message",
                        args = buildJsonObject {
                            put("overlayId", overlayId)
                            put("payload", payload)
                        },
                        method = "ui.invoke",
                        timeoutMs = OVERLAY_MESSAGE_TIMEOUT_MS
                    )
                }.onFailure {
                    FileLogger.w(TAG, "overlay message failed: $pluginId", it)
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                pruneIdle()
            }
        }
    }

    suspend fun invoke(
        pluginId: String,
        version: String,
        runtime: PluginRuntime,
        handler: String,
        args: JsonObject,
        method: String = "tool.invoke",
        timeoutMs: Long = TOOL_TIMEOUT_MS
    ): JsonElement {
        requireActive(pluginId)
        val entry = getOrCreate(pluginId, version, runtime)
        if (!isActive(pluginId)) {
            synchronized(processes) { processes.remove(entry.key, entry) }
            entry.transport.close()
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
        entry.lastUsed = System.currentTimeMillis()
        return try {
            entry.transport.request(
                method = method,
                params = buildJsonObject {
                    put("handler", handler)
                    put("args", args)
                },
                timeoutMs = timeoutMs
            )
        } catch (e: PluginProcessException) {
            if (e.code == "PLUGIN_EXITED" || (e.code == "PLUGIN_TIMEOUT" && !entry.resident)) {
                synchronized(processes) { processes.remove(entry.key, entry) }
                entry.transport.close()
            }
            throw e
        }
    }

    /** 插件版本替换后关闭旧版本进程，避免 resident 旧进程永远不回收。 */    fun closeOtherVersions(pluginId: String, currentVersion: String) {
        val keepKey = "$pluginId@$currentVersion"
        synchronized(processes) {
            processes.entries
                .filter { (key, entry) -> entry.pluginId == pluginId && key != keepKey }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    /**
     * 面板等长驻界面打开期间给进程加一层租约。
     *
     * 没有租约时最后一次调用过去 10 分钟就会连 handler 带 action 表一起被收走，
     * 用户再点按钮只会得到 HANDLER_NOT_FOUND。返回的函数释放这一份租约，可重复调用。
     */
    fun acquireLease(pluginId: String, version: String): () -> Unit {
        val key = leaseKey(pluginId, version)
        leases.compute(key) { _, count -> (count ?: 0) + 1 }
        val released = AtomicBoolean(false)
        return {
            if (released.compareAndSet(false, true)) {
                leases.compute(key) { _, count ->
                    val next = (count ?: 1) - 1
                    if (next > 0) next else null
                }
            }
        }
    }

    /** 插件停用、卸载或版本替换时关闭它的进程。 */
    fun closePlugin(pluginId: String) {
        synchronized(processes) {
            processes.entries
                .filter { (_, entry) -> entry.pluginId == pluginId }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    private suspend fun getOrCreate(pluginId: String, version: String, runtime: PluginRuntime): Entry {
        val key = "$pluginId@$version"
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            throw PluginProcessException("PLUGIN_RUNTIME_UNAVAILABLE", it)
        }
        val dataDir = dataDirectory(pluginId)
        val wsRoot = workspaceRoot()
        // 上面的挂起调用期间可能已有并发调用创建了同一版本的进程。
        processes[key]?.let { entry ->
            entry.resident = runtime.resident
            return entry
        }
        var createdEntry: Entry? = null
        val transport = PluginProcessTransport(
            pluginId = pluginId,
            engine = engine,
            profile = profile,
            workingDir = containerDir(runtime.installDir),
            program = runtime.command.firstOrNull()
                ?: throw PluginProcessException("PLUGIN_COMMAND_MISSING", "[$pluginId] 运行时没有启动命令"),
            programArgs = runtime.command.drop(1),
            extraEnv = buildMap {
                put("AICODE_PLUGIN_ID", pluginId)
                put("AICODE_PLUGIN_VERSION", version)
                put("AICODE_DATA_DIR", dataDir)
                put("AICODE_WORKSPACE", wsRoot)
                // Operit 转换来的插件入口会 require 这个 shim 才能拿到 Tools.*；
                // 拿不到就根本没接上，所以只要落盘成功就必须带出去。
                operitShimPath()?.let { put("AHAROU_OPERIT_SHIM", it) }
            },
            onRequest = { method, params -> hostRequest(pluginId, runtime, method, params) },
            onNotification = { method, params -> handleNotification(pluginId, method, params) },
            onExit = {
                createdEntry?.let { entry ->
                    synchronized(processes) { processes.remove(key, entry) }
                }
            },
            closeOnTimeout = !runtime.resident
        )
        val entry = Entry(pluginId, key, transport, System.currentTimeMillis(), runtime.resident)
        createdEntry = entry
        val existing = synchronized(processes) { processes.putIfAbsent(key, entry) }
        if (existing != null) {
            transport.close()
            return existing
        }
        return entry
    }

    private suspend fun hostRequest(
        pluginId: String,
        runtime: PluginRuntime,
        method: String,
        params: JsonObject
    ): JsonElement = try {
        hostApiDispatcher.dispatch(
            pluginId = pluginId,
            method = method,
            params = params,
            origin = PluginHostApiOrigin.PROCESS,
            workspaceRoot = ::workspaceRoot,
            modelOrigin = PROCESS_ORIGIN
        )
    } catch (e: PluginHostException) {
        // 进程通道只认 PluginProcessException，其余错误会被 transport 归成 HOST_ERROR。
        throw PluginProcessException(e.code, e.message)
    }

    private fun isActive(pluginId: String): Boolean {
        pluginManager.ensureLoaded()
        return pluginManager.plugins.value.any { it.id == pluginId && it.isActive }
    }

    private fun requireActive(pluginId: String) {
        if (!isActive(pluginId)) {
            throw PluginProcessException("PLUGIN_INACTIVE", "[$pluginId] 插件已停用或卸载")
        }
    }

    private fun handleNotification(pluginId: String, method: String, params: JsonObject) {
        if (method != "plugin.event") {
            FileLogger.d(TAG, "[$pluginId] 忽略插件通知: $method")
            return
        }
        eventBus.publish(pluginId, params)
    }

    /** 本地默认容器；SSH 模式下插件进程仍跑在手机端，与 MCP stdio 一致。 */
    private suspend fun runtimeProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 宿主文件路径映射到容器内路径：插件目录由 `.aharou` 绑定挂载到 `/root/.aharou`。 */
    private fun containerDir(file: File): String = installer.containerPathFor(file)

    /**
     * 插件数据目录在当前执行环境中的真实路径，并确保目录存在。本地落在
     * `/…/.aharou/plugins/data/<id>`（与宿主 `aharouDir/plugins/data/<id>` 同一份），远程落在服务器同名路径。
     */
    private suspend fun dataDirectory(pluginId: String): String {
        val path = PluginHostPaths.dataRoot(homeResolver, pluginId)
        fileAccess.mkdirs(path)
        return path
    }

    /** 当前工作区在插件可见路径空间里的根：远程用远端绝对路径，本地用容器内 `$HOME/workspace`。 */
    private fun workspaceRoot(): String =
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            workspaceRepository.currentPath()
        } else {
            homeResolver.home().trimEnd('/') + "/workspace"
        }

    private fun pruneIdle() {
        val now = System.currentTimeMillis()
        synchronized(processes) {
            processes.entries
                .filter {
                    !it.value.resident &&
                        (leases[it.key] ?: 0) <= 0 &&
                        now - it.value.lastUsed > IDLE_TIMEOUT_MS
                }
                .forEach { (key, entry) ->
                    processes.remove(key, entry)
                    entry.transport.close()
                }
        }
    }

    private fun leaseKey(pluginId: String, version: String): String = "$pluginId@$version"

    /**
     * 把 Operit 兼容层落到宿主的 `.aharou/plugins/sdk/`，返回它在容器里的路径。
     *
     * `.aharou` 在容器内被绑成 `/root/.aharou`，所以宿主写文件就等于写进容器，不必再走引擎。
     * 内容没变就不重写，避免每次起插件都碰一次磁盘。远程模式下容器在服务器上，本轮不处理。
     */
    private fun operitShimPath(): String? {
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) return null
        return runCatching {
            val dir = File(installer.aharouDir, SHIM_DIR)
            if (!dir.isDirectory && !dir.mkdirs()) error("无法创建 ${dir.absolutePath}")
            SHIM_FILES.forEach { name ->
                val target = File(dir, name)
                val bytes = context.assets.open("plugin-ui/$name").use { it.readBytes() }
                if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                    target.writeBytes(bytes)
                }
            }
            "$CONTAINER_SHIM_DIR/${SHIM_FILES.first()}"
        }.onFailure {
            FileLogger.w(TAG, "Operit 兼容层落盘失败，插件里的 Tools.* 将不可用", it)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "PluginRuntime"
        /** 兼容层文件：shim 本体与它 require 的 Compose DSL 运行时，必须同目录。 */
        val SHIM_FILES = listOf("operit-shim.js", "compose-dsl-runtime.js")
        const val SHIM_DIR = "plugins/sdk"
        const val CONTAINER_SHIM_DIR = "/root/.aharou/plugins/sdk"
        const val TOOL_TIMEOUT_MS = 120_000L
        const val IDLE_TIMEOUT_MS = 10 * 60_000L
        const val IDLE_CHECK_MS = 60_000L
        const val OVERLAY_MESSAGE_TIMEOUT_MS = 30_000L
        const val PROCESS_ORIGIN = "process"
    }
}
