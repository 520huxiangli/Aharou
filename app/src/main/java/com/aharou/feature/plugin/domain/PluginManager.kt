package com.aharou.feature.plugin.domain

import com.aharou.core.util.FileLogger
import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.RuntimeProcessStore
import com.aharou.feature.agent.domain.container.TrackedStdioProcess
import com.aharou.feature.plugin.data.PluginRecord
import com.aharou.feature.plugin.data.PluginRecordStore
import com.aharou.feature.plugin.domain.model.InstalledPlugin
import com.aharou.feature.plugin.domain.model.PluginManifest
import com.aharou.feature.plugin.domain.model.PluginStatus
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 插件管理器：把 `.txplugin` 包（zip：manifest.json + payload/）落到容器里安装、启动、停止与卸载。
 *
 * 定位与边界：插件本体（payload 下的脚本与运行时）在**容器**里跑，不进 App 进程——所以这不是「动态加载
 * 代码」，而是「声明式包 + 容器脚本 + 本地服务」，与 Aharou 既有架构一致。宿主只负责读清单、跑安装脚本、
 * 拉起服务、探测端口，以及在设置页里给一个入口。
 *
 * 容器内路径映射：宿主 `filesDir/aharou` 经 PRoot 绑定为容器内 `/root/.aharou`（读写），
 * 故插件落在 `filesDir/aharou/plugins/<id>`，容器里就是 `/root/.aharou/plugins/<id>`。
 */
@Singleton
class PluginManager @Inject constructor(
    private val engine: LinuxContainerEngine,
    private val containerInstaller: ContainerInstaller,
    private val recordStore: PluginRecordStore,
    private val runtimeProcessStore: RuntimeProcessStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 正在运行的插件服务句柄（内存态；App 重启后为空，靠端口探测与进程账本兜底）。 */
    private val running = ConcurrentHashMap<String, TrackedStdioProcess>()

    private fun pluginsRoot(): File = File(containerInstaller.aharouDir, "plugins").apply { mkdirs() }

    /** 宿主侧插件根目录：`filesDir/aharou/plugins/<id>`。 */
    fun hostDir(id: String): File = File(pluginsRoot(), id)

    /** 容器内插件根目录（宿主 [hostDir] 的绑定路径）。 */
    private fun containerDir(id: String): String = "/root/.aharou/plugins/$id"

    /** 读取插件目录里的 manifest.json；不存在返回 null。 */
    fun readManifest(id: String): PluginManifest? {
        val f = File(hostDir(id), MANIFEST_FILE)
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<PluginManifest>(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /**
     * 导入一个插件包：校验 zip 结构、读出 manifest.json、把 payload/ 解到插件目录。
     * 只落盘，不执行任何脚本（安装是 [install] 的事）。
     */
    suspend fun importPackage(zipFile: File): Result<PluginManifest> = withContext(Dispatchers.IO) {
        runCatching {
            require(zipFile.isFile) { "插件包不存在：${zipFile.path}" }
            val (manifestText, manifest) = ZipFile(zipFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_FILE) ?: error("包内缺少 $MANIFEST_FILE")
                val text = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                text to json.decodeFromString<PluginManifest>(text)
            }
            require(manifest.id.isNotBlank()) { "manifest 缺少 id" }
            require(manifest.name.isNotBlank()) { "manifest 缺少 name" }

            val root = hostDir(manifest.id)
            // 重装：先清掉旧 payload，避免残留旧版本文件
            File(root, PAYLOAD_DIR).deleteRecursively()
            root.mkdirs()

            ZipFile(zipFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    if (!e.name.startsWith("$PAYLOAD_DIR/")) continue
                    val out = File(root, e.name)
                    // 防 zip-slip：解出的路径必须仍在插件目录内
                    require(out.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                        "包内路径越界：${e.name}"
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
                }
            }
            File(root, MANIFEST_FILE).writeText(manifestText, Charsets.UTF_8)
            FileLogger.i(TAG, "导入插件 ${manifest.id} v${manifest.version}")
            manifest
        }
    }

    /** 安装：在容器里依次执行 installSteps，再跑 verifyCommand。任一步失败即中止并返回错误。 */
    suspend fun install(id: String): Result<Unit> = runCatching {
        val manifest = readManifest(id) ?: error("插件未导入：$id")
        ensureContainer()
        val dir = containerDir(id)
        manifest.installSteps.forEachIndexed { index, step ->
            val r = engine.runCommandSync(envPrefix(dir) + step, null, INSTALL_TIMEOUT_MS)
            if (r.isBlank()) return@forEachIndexed
            FileLogger.d(TAG, "install step ${index + 1} 输出：${r.take(400)}")
        }
        if (manifest.verifyCommand.isNotBlank()) {
            val result = engine.runCommandSyncWithExit(envPrefix(dir) + manifest.verifyCommand, null, INSTALL_TIMEOUT_MS)
            if (result.exitCode != null && result.exitCode != 0) {
                error("自检失败（退出码 ${result.exitCode}）：${result.output.take(300)}")
            }
        }
        recordStore.save(
            PluginRecord(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                installedAt = System.currentTimeMillis(),
            )
        )
        FileLogger.i(TAG, "安装插件完成 ${manifest.id}")
    }

    /** 启动常驻服务：解析 launchCommand → 起进程 → 轮询端口就绪。非服务型插件不启动。 */
    suspend fun launch(id: String): Result<Unit> = runCatching {
        val manifest = readManifest(id) ?: error("插件未导入：$id")
        require(manifest.isService) { "插件 ${manifest.name} 不是常驻服务，无需启动" }
        ensureContainer()

        if (isPortReady(manifest)) {
            FileLogger.i(TAG, "插件 ${id} 服务已在运行")
            return@runCatching
        }

        val dir = containerDir(id)
        val program = resolveLaunchCommand(manifest, dir)
        val env = manifest.environment +
            mapOf("TAIXU_TOOL_DIR" to dir, "TAIXU_TOOL_DATA" to "$dir/data")
        val handle = engine.startTrackedStdioProcess(
            program = program,
            programArgs = emptyList(),
            projectPath = null,
            tag = tagOf(id),
            extraEnv = env,
        )
        running[id] = handle

        // 轮询端口就绪；超时则杀掉进程并报错，避免留下半死不活的服务
        val deadline = System.currentTimeMillis() + START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (isPortReady(manifest)) {
                FileLogger.i(TAG, "插件 ${id} 服务就绪（端口 ${manifest.servicePort}）")
                return@runCatching
            }
            if (!handle.process.isAlive) {
                handle.clearLedger()
                running.remove(id)
                error("启动进程已退出（检查容器内 node / 依赖是否齐备）")
            }
            delay(500)
        }
        handle.process.destroy()
        handle.clearLedger()
        running.remove(id)
        error("启动超时：端口 ${manifest.servicePort} 未就绪")
    }

    /**
     * 停止常驻服务。三条路一起走，任一条命中就杀：
     * 1. 本会话启动的句柄——按进程树终止（PRoot 忽略 SIGTERM，用 [ProcessIdentity.terminateTree] 才杀得掉）；
     * 2. 进程账本里同 tag 的残留（上次会话遗留、句柄已丢）；
     * 3. 兜底：容器里凡是从插件目录起来的进程一律清掉——覆盖「非本 App 启动」的（如手动跑的）。
     *
     * 最后等服务端口释放，未释放则报错，避免 UI 仍显示「运行中」。
     */
    suspend fun stop(id: String): Result<Unit> = runCatching {
        val manifest = readManifest(id)
        val dir = containerDir(id)

        // 1. 本会话句柄
        running.remove(id)?.let { handle ->
            handle.pid?.let { pid -> ProcessIdentity.terminateTree(ProcessIdentity.Handle(pid, null)) }
            handle.clearLedger()
        }

        // 2. 账本残留（上次会话遗留）
        val tag = tagOf(id)
        runtimeProcessStore.stale()
            .filter { it.command == tag }
            .forEach { runtimeProcessStore.terminate(it) }

        // 3. 兜底：按插件目录杀（pkill 不可用时静默跳过）
        runCatching {
            engine.runCommandSync("pkill -f '$dir/[a-z]' 2>/dev/null; true", null, PKILL_TIMEOUT_MS)
        }

        // 4. 等端口释放；仍被占用则视为失败
        if (manifest != null && manifest.isService) {
            val deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline && isPortReady(manifest)) delay(300)
            if (isPortReady(manifest)) error("停止失败：端口 ${manifest.servicePort} 仍被占用")
        }
    }

    /** 卸载：先停服务、跑 uninstallSteps，再删目录与记录。 */
    suspend fun uninstall(id: String): Result<Unit> = runCatching {
        stop(id)
        val manifest = readManifest(id)
        val dir = containerDir(id)
        manifest?.uninstallSteps?.forEach { step ->
            runCatching { engine.runCommandSync(envPrefix(dir) + step, null, INSTALL_TIMEOUT_MS) }
        }
        hostDir(id).deleteRecursively()
        recordStore.remove(id)
        FileLogger.i(TAG, "卸载插件 $id")
    }

    /** 列出已安装插件：记录 + 目录里的清单 + 实时状态。目录已不存在的记录视为孤儿，顺手清掉。 */
    suspend fun listInstalled(): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        recordStore.all().values.mapNotNull { record ->
            val manifest = readManifest(record.id)
            if (manifest == null) {
                recordStore.remove(record.id)
                return@mapNotNull null
            }
            val status = if (!manifest.isService) {
                PluginStatus.STOPPED
            } else if (isPortReady(manifest)) {
                PluginStatus.RUNNING
            } else {
                PluginStatus.STOPPED
            }
            InstalledPlugin(
                manifest = manifest,
                hostDir = hostDir(record.id).absolutePath,
                installedAt = record.installedAt,
                status = status,
            )
        }.sortedBy { it.manifest.name.lowercase() }
    }

    // ── 内部 ──

    private suspend fun ensureContainer() {
        if (!engine.isContainerInstalled()) engine.ensureInstalled()
    }

    /** launchCommand 是绝对路径就原样用；否则优先取插件自带 bin/ 下的同名入口，再回退 PATH。 */
    private fun resolveLaunchCommand(manifest: PluginManifest, containerDir: String): String {
        val cmd = manifest.launchCommand
        if (cmd.startsWith("/")) return cmd
        val inBin = "$containerDir/bin/$cmd"
        // 宿主侧对应文件存在就用容器内绝对路径，避免依赖 PATH 里的软链（/opt/taixu 在 rootfs，重装即失）
        val hostFile = File(hostDir(manifest.id), "bin/$cmd")
        return if (hostFile.isFile) inBin else cmd
    }

    /** 安装/卸载脚本与启动注入的 TAIXU_* 环境变量前缀（沿用太墟包约定的变量名）。 */
    private fun envPrefix(containerDir: String): String =
        "export TAIXU_PLUGIN_PAYLOAD='$containerDir/payload' " +
            "TAIXU_TOOL_DIR='$containerDir' " +
            "TAIXU_TOOL_DATA='$containerDir/data'; "

    private fun tagOf(id: String): String = "$TAG_TAG_PREFIX$id"

    /** 端口就绪探测：GET servicePath，拿到任意 HTTP 响应即认为服务已起（含 4xx，说明在监听）。 */
    private suspend fun isPortReady(manifest: PluginManifest): Boolean = withContext(Dispatchers.IO) {
        val port = manifest.servicePort
        if (port !in 1..65535) return@withContext false
        val path = manifest.servicePath.ifBlank { "/" }.let { if (it.startsWith("/")) it else "/$it" }
        runCatching {
            val conn = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
                instanceFollowRedirects = false
            }
            try {
                conn.responseCode in 200..499
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private companion object {
        const val TAG = "PluginManager"
        const val MANIFEST_FILE = "manifest.json"
        const val PAYLOAD_DIR = "payload"
        const val INSTALL_TIMEOUT_MS = 600_000L
        const val START_TIMEOUT_MS = 60_000L
        const val STOP_TIMEOUT_MS = 20_000L
        const val PKILL_TIMEOUT_MS = 20_000L
        const val TAG_TAG_PREFIX = "plugin:"
    }
}
