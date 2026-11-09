package com.aharou.feature.plugin.domain

import android.os.Build
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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

    /** 本进程内最近一次启动失败的原因（插件 id → 原因）。只用于把「失败」状态与副标题带到界面上。 */
    private val lastFailure = ConcurrentHashMap<String, String>()

    /** 导入时发现的非致命问题（插件 id → 提示）。不阻挠安装，只让用户在卡片上看到。 */
    private val importWarnings = ConcurrentHashMap<String, List<String>>()

    /** 连续未就绪次数（插件 id → 次数）。健康巡检用；就绪一次即清零。 */
    private val healthFailures = ConcurrentHashMap<String, Int>()

    /** 下次允许自动重启的时间点（插件 id → 毫秒）。退避期内不再重试。 */
    private val nextRestartAt = ConcurrentHashMap<String, Long>()

    /** 巡检跑在进程级 scope：与任何页面生命周期无关，页面关了也照样盯。 */
    private val healthScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var healthJob: Job? = null

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
            // id 会拼进插件目录路径，必须拦住 `../` 这类写法，否则 payload 能落到插件根之外；
            // 「至少含一个字母或数字」是专门堵 `.` / `..` / `___` 的，单靠字符集挡不住。
            require(ID_PATTERN.matches(manifest.id) && manifest.id.any { it.isLetterOrDigit() }) {
                "manifest 的 id 只能包含字母、数字、点、下划线与连字符，且至少含一个字母或数字（最长 $ID_MAX_LENGTH）"
            }
            require(manifest.name.isNotBlank()) { "manifest 缺少 name" }
            require(manifest.schemaVersion <= SUPPORTED_SCHEMA_VERSION) {
                "插件包清单版本 ${manifest.schemaVersion} 高于本应用支持的 $SUPPORTED_SCHEMA_VERSION，请先升级应用"
            }
            // 架构不符的包在容器里跑不起来，等安装脚本报错更难查，导入时就拦下
            require(manifest.architectures.isEmpty() || manifest.architectures.any { abiSupported(it) }) {
                "插件包不支持本机架构（需要 ${manifest.architectures.joinToString("/")}，" +
                    "本机 ${Build.SUPPORTED_ABIS.joinToString("/")}）"
            }

            val root = hostDir(manifest.id)
            // 重装：先清掉旧 payload，避免残留旧版本文件
            File(root, PAYLOAD_DIR).deleteRecursively()
            root.mkdirs()

            val warnings = unpackPayload(zipFile, root, manifest)
            File(root, MANIFEST_FILE).writeText(manifestText, Charsets.UTF_8)
            if (warnings.isEmpty()) importWarnings.remove(manifest.id) else importWarnings[manifest.id] = warnings
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
            // 只拿文本判不了成败：脚本非零退出（缺依赖、下载失败）会静默算作装好
            val r = engine.runCommandSyncWithExit(envPrefix(dir) + step, null, INSTALL_TIMEOUT_MS)
            if (r.output.isNotBlank()) FileLogger.d(TAG, "install step ${index + 1} 输出：${r.output.take(400)}")
            errorIfFailed(r.exitCode, r.output, "安装步骤 ${index + 1}")
        }
        if (manifest.verifyCommand.isNotBlank()) {
            val result = engine.runCommandSyncWithExit(envPrefix(dir) + manifest.verifyCommand, null, INSTALL_TIMEOUT_MS)
            errorIfFailed(result.exitCode, result.output, "自检")
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
        // 重新装过一遍，旧的启动失败原因不再作数
        lastFailure.remove(manifest.id)
        // 装了插件就该开始盯它的服务；无需用户进插件页碰一下才启动巡检
        startHealthSupervisor()
    }

    /** 启动常驻服务：解析 launchCommand → 起进程 → 轮询端口就绪。非服务型插件不启动。 */
    suspend fun launch(id: String): Result<Unit> {
        val result = runCatchingLaunch(id)
        // 失败原因留给 listInstalled 换算成 FAILED 状态与卡片副标题
        result.onSuccess {
            lastFailure.remove(id)
            markEnabled(id, true)
        }.onFailure { lastFailure[id] = it.message ?: it::class.java.simpleName }
        return result
    }

    private suspend fun runCatchingLaunch(id: String): Result<Unit> = runCatching {
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
        // 主动停止成功：清掉上次的启动失败记录，否则卡片会一直挂着「失败」
        lastFailure.remove(id)
        markEnabled(id, false)
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
        healthFailures.remove(id)
        nextRestartAt.remove(id)
        importWarnings.remove(id)
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
            // 失败原因只在本进程内有意义：重启后无从判断上次为什么没起来，回落成「已停止」
            val failure = if (manifest.isService) lastFailure[record.id] else null
            val status = when {
                !manifest.isService -> PluginStatus.STOPPED
                isPortReady(manifest) -> PluginStatus.RUNNING
                failure != null -> PluginStatus.FAILED
                else -> PluginStatus.STOPPED
            }
            InstalledPlugin(
                manifest = manifest,
                hostDir = hostDir(record.id).absolutePath,
                installedAt = record.installedAt,
                status = status,
                // 只有失败态有可说的原因，其余态留空避免把内部信息洒到卡片上
                detail = if (status == PluginStatus.FAILED) failure.orEmpty() else "",
            )
        }.sortedBy { it.manifest.name.lowercase() }
    }

    // ── 内部 ──

    /**
     * 把 `payload/` 解到插件目录，同时做包内加固与完整性核对。
     *
     * 重复文件名拒绝（否则包内可以藏一个覆盖前者的隐藏文件），并限制条目数与总解压体积（防 zip 炸弹）。
     * 完整性不符只当作可跳过的提示，写进返回值交给界面展示，不影响安装。
     */
    private fun unpackPayload(zipFile: File, root: File, manifest: PluginManifest): List<String> {
        val mismatched = mutableListOf<String>()
        ZipFile(zipFile).use { zip ->
            val entries = zip.entries()
            val seen = HashSet<String>()
            var scanned = 0
            var totalBytes = 0L
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                scanned++
                require(scanned <= MAX_ZIP_ENTRIES) { "包内条目过多（上限 $MAX_ZIP_ENTRIES 个）" }
                if (e.isDirectory) continue
                if (!e.name.startsWith("$PAYLOAD_DIR/")) continue
                require(seen.add(e.name)) { "包内存在重复条目：${e.name}" }
                val out = File(root, e.name)
                // 防 zip-slip：解出的路径必须仍在插件目录内
                require(out.canonicalPath.startsWith(root.canonicalPath + File.separator)) {
                    "包内路径越界：${e.name}"
                }
                out.parentFile?.mkdirs()
                val digest = MessageDigest.getInstance("SHA-256")
                zip.getInputStream(e).use { input ->
                    out.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalBytes += read
                            require(totalBytes <= MAX_UNCOMPRESSED_BYTES) {
                                "包解压后体积超过上限（$MAX_UNCOMPRESSED_BYTES 字节）"
                            }
                            digest.update(buffer, 0, read)
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (manifest.integrity.isNotEmpty()) {
                    val relative = e.name.removePrefix("$PAYLOAD_DIR/")
                    val expected = manifest.integrity[relative] ?: manifest.integrity[e.name]
                    val actual = digest.digest().joinToString("") { byte ->
                        // Byte 是带符号的，直接 %02x 会得到 8 位长串
                        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
                    }
                    if (expected != null && !expected.equals(actual, ignoreCase = true)) mismatched.add(relative)
                }
            }
        }
        if (mismatched.isEmpty()) return emptyList()
        FileLogger.w(TAG, "插件 ${manifest.id} 完整性校验不一致：${mismatched.joinToString()}")
        // 返回文件名而不是现成文案：用户可见提示由 UI 层按语种拼，这边只给事实
        return mismatched
    }

    /** 取走导入时留下的完整性不符文件名（取完即清），供界面提示一次。 */
    fun takeImportWarnings(id: String): List<String> = importWarnings.remove(id).orEmpty()

    /** 启停成功后才记意愿：失败的不该被巡检当成「应该在跑」反复拉起来。 */
    private fun markEnabled(id: String, enabled: Boolean) {
        val record = recordStore.get(id) ?: return
        if (record.enabled == enabled) return
        recordStore.save(record.copy(enabled = enabled))
    }

    /** 启动健康巡检（幂等）。安装流程与插件页都会调，重复调不会多开一份。 */
    fun startHealthSupervisor() {
        if (healthJob?.isActive == true) return
        healthJob = healthScope.launch {
            while (isActive) {
                delay(HEALTH_INTERVAL_MS)
                runCatching { healthCheckOnce() }
                    .onFailure { FileLogger.w(TAG, "插件健康巡检异常", it) }
            }
        }
    }

    /**
     * 巡检一轮：只盯用户启用过的常驻服务。
     *
     * 连续多次未就绪才动手重启（网络抖动、启动慢都算正常），重启失败按指数退避封顶 5 分钟，
     * 避免一个永远起不来的插件每 30 秒就被拉起一次。
     */
    private suspend fun healthCheckOnce() {
        recordStore.all().values.filter { it.enabled }.forEach { record ->
            val manifest = readManifest(record.id) ?: return@forEach
            if (!manifest.isService) return@forEach
            if (isPortReady(manifest)) {
                healthFailures.remove(record.id)
                nextRestartAt.remove(record.id)
                return@forEach
            }
            val failures = (healthFailures[record.id] ?: 0) + 1
            healthFailures[record.id] = failures
            if (failures < UNHEALTHY_THRESHOLD) return@forEach
            val now = System.currentTimeMillis()
            if (now < (nextRestartAt[record.id] ?: 0L)) return@forEach
            val step = (failures - UNHEALTHY_THRESHOLD).coerceAtMost(4)
            nextRestartAt[record.id] = now + min(MAX_RESTART_BACKOFF_MS, RESTART_BACKOFF_MS shl step)
            FileLogger.i(TAG, "插件 ${record.id} 连续 $failures 次未就绪，尝试重启")
            this@PluginManager.launch(record.id)
        }
    }

    private suspend fun ensureContainer() {
        if (!engine.isContainerInstalled()) engine.ensureInstalled()
    }

    /** 退出码非 0 表示失败；null 表示超时或容器未就绪——两种情况都不能当作成功。 */
    private fun errorIfFailed(code: Int?, output: String, what: String) {
        if (code == null) error("$what 未返回退出码（超时或容器未就绪）：${output.take(300)}")
        if (code != 0) error("$what 失败（退出码 $code）：${output.take(300)}")
    }

    /** 清单里的架构名（ARM64 / amd64 等）是否被本机 ABI 覆盖；不认识的写法按原样比对。 */
    private fun abiSupported(arch: String): Boolean {
        val key = arch.trim().lowercase()
        val abis = Build.SUPPORTED_ABIS.map { it.lowercase() }
        return when (key) {
            "arm64", "aarch64", "arm64-v8a" -> "arm64-v8a" in abis
            "amd64", "x86_64", "x64" -> "x86_64" in abis
            "arm", "armv7", "armv7l", "armhf", "armeabi-v7a" -> "armeabi-v7a" in abis
            "x86", "i686" -> "x86" in abis
            else -> key in abis
        }
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

        /** 本应用能读懂的清单版本上限；更高版本的包字段语义可能变了，宁可拒装。 */
        const val SUPPORTED_SCHEMA_VERSION = 1
        const val START_TIMEOUT_MS = 60_000L
        const val STOP_TIMEOUT_MS = 20_000L
        const val PKILL_TIMEOUT_MS = 20_000L
        const val TAG_TAG_PREFIX = "plugin:"

        /** 包内条目数上限；正常插件包远达不到这个量级。 */
        const val MAX_ZIP_ENTRIES = 20_001

        /** 解压后总字节上限：防止构造小体积、大膨胀的包把存储吃满。 */
        const val MAX_UNCOMPRESSED_BYTES = 8L * 1024 * 1024 * 1024

        /** id 会直接拼成目录名，只允许安全字符且不允许 `..`。 */
        const val ID_MAX_LENGTH = 64
        val ID_PATTERN = Regex("[A-Za-z0-9._-]{1,$ID_MAX_LENGTH}")

        const val HEALTH_INTERVAL_MS = 30_000L

        /** 连续这么多次探测不到端口才认为真的挂了，避开启动慢与瞬时抖动。 */
        const val UNHEALTHY_THRESHOLD = 3
        const val RESTART_BACKOFF_MS = 30_000L
        const val MAX_RESTART_BACKOFF_MS = 300_000L
    }
}
