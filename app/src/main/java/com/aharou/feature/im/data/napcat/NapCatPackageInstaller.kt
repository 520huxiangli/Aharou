package com.aharou.feature.im.data.napcat

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AtomicFile
import com.aharou.core.util.ProcessIdentity
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.RootfsSource
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import com.aharou.feature.settings.data.remote.ContainerImageDownloader
import com.aharou.feature.settings.data.remote.ContainerImageChecksumException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class NapCatPackageInstaller @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val downloader: ContainerImageDownloader,
    private val container: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val settings: NapCatSettingsRepository,
    private val releases: NapCatReleaseSource,
    private val catalog: NapCatAssetCatalog
) {
    private val base get() = File(context.filesDir, "aharou-global/napcat")
    private val manifest get() = AtomicFile(File(base, "installed.json"))
    private val archive get() = File(base, "downloads/debian-rootfs.tar.gz")

    private fun installed(): JSONObject? = runCatching {
        manifest.openRead().bufferedReader().use { JSONObject(it.readText()) }
    }.getOrNull()

    fun installedVersion(): String = installed()?.optString("version").orEmpty()

    fun isEnvironmentReady(): Boolean = container.isInstalledFor(profile())

    fun profile(): ContainerProfile {
        val home = File(base, "data/home").apply { mkdirs() }
        return ContainerProfile(
            id = "napcat-debian", name = "NapCat Debian", isBuiltin = false,
            shellPath = "/bin/bash", rootfsSource = RootfsSource.LocalFile(Uri.fromFile(archive).toString()),
            extraBindings = listOf("${base.absolutePath}:/napcat", "${base.absolutePath}:/opt/aharou-napcat", "${home.absolutePath}:/root"),
            extraArgs = ContainerProfile.DEFAULT_PROOT_ARGS
        )
    }

    suspend fun install(
        update: Boolean = false,
        onStage: (String) -> Unit = {},
        onLog: (String) -> Unit = {},
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        suspend fun command(program: String, args: List<String>) = runCommand(program, args, onLog)
        onStage("preparing")
        val architecture = when (Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a" -> "aarch64"
            "x86_64" -> "x86_64"
            else -> error("NapCat requires arm64-v8a or x86_64")
        }
        val debArchitecture = if (architecture == "aarch64") "arm64" else "amd64"
        val selected = if (update) releases.latest(debArchitecture) else releases.bundled(debArchitecture)
        if (installed()?.let { it.optString("version") == selected.version && it.optString("qqVersion") == selected.qqVersion } == true) {
            onProgress(1f)
            return@withContext
        }
        File(base, "downloads").mkdirs()
        File(base, "releases").mkdirs()
        File(base, "data/config").mkdirs()
        File(base, "data/plugins").mkdirs()
        if (!container.isInstalledFor(profile())) {
            onStage("rootfs_download")
            val (rootfs, candidate) = downloadCandidates(catalog.rootfsCandidates(architecture), "napcat-debian-$architecture.archive") {
                onProgress(it * 0.2f)
            }
            onStage("rootfs_unpack")
            NapCatRootfs.normalize(rootfs, archive, preinstalled = candidate.preinstalledRootfs)
            container.installRootfsIfNeed(profile())
        }
        onProgress(0.25f)
        onStage("dependencies")
        if (NapCatDependencies.isReady { program, args -> command(program, args) }) {
            onLog("NapCat dependencies verified; skipping apt")
        } else {
            onLog("NapCat dependency probe incomplete; continuing apt installation")
            writeAtomic(File(base, NapCatAptSource.SOURCE_FILE), NapCatAptSource.sources)
            val lists = File(base, "${NapCatAptSource.LISTS_DIRECTORY}/partial")
            check(lists.isDirectory || lists.mkdirs()) { "Cannot prepare NapCat apt index directory" }
            onLog("APT source: mirrors.huaweicloud.com (Debian trixie)")
            onStage("dependency_index")
            command("/usr/bin/apt-get", NapCatAptSource.arguments("update"))
            onProgress(0.3f)
            onStage("dependencies")
            command("/usr/bin/apt-get", NapCatAptSource.arguments("install", NapCatDependencies.packages))
        }
        onProgress(0.4f)
        onStage("qq_download")
        val qq = download(selected.qqUrl, "linuxqq-${selected.qqVersion}-$debArchitecture.deb") { onProgress(0.4f + it * 0.2f) }
        onStage("napcat_download")
        val napcat = download(selected.napcatUrl, "NapCat-${selected.version}.Shell.zip") { onProgress(0.6f + it * 0.2f) }
        onStage("extract")
        val release = "release-${System.currentTimeMillis()}"
        val guest = "/napcat/releases/$release"
        File(base, "releases/$release").mkdirs()
        // PRoot 下 dpkg-deb 内部调用 tar 恢复目录权限会报 ENOENT，显式管道保留权限及两端退出检查。
        command("/bin/bash", listOf("-c", "set -euo pipefail; mkdir -p -- \"\$2\"; /usr/bin/dpkg-deb --fsys-tarfile \"\$1\" | /usr/bin/tar --extract --preserve-permissions --file=- --directory=\"\$2\"", "napcat-extract", guestPath(qq), "$guest/qq"))
        val napcatDir = "$guest/qq/opt/QQ/resources/app/app_launcher/napcat"
        command("/usr/bin/unzip", listOf("-q", guestPath(napcat), "-d", napcatDir))
        val app = File(base, "releases/$release/qq/opt/QQ/resources/app")
        val packageFile = File(app, "package.json")
        check(packageFile.isFile) { "QQ package layout is incompatible" }
        check(JSONObject(packageFile.readText()).optString("version") == selected.qqVersion) { "QQ package version does not match the official installer" }
        // NapCat 4.18.15 起部分版本改用 napcat.cjs 入口，写死 .mjs 会在升级后起不来。
        val entry = listOf("napcat.mjs", "napcat.cjs").map { File(base, "releases/$release/qq/opt/QQ/resources/app/app_launcher/napcat/$it") }
            .firstOrNull { it.isFile } ?: error("NapCat package layout is incompatible")
        app.resolve("loadNapCat.js").writeText(
            "const {pathToFileURL}=require('node:url'); import(pathToFileURL('$napcatDir/${entry.name}').href);\n"
        )
        packageFile.writeText(JSONObject(packageFile.readText()).put("main", "./loadNapCat.js").toString())
        command("/bin/bash", listOf("-c", "set -e; for name in config plugins; do target=\"\$1/napcat/\$name\"; if [ -d \"\$target\" ] && [ ! -L \"\$target\" ]; then if [ \"\$name\" = plugins ]; then cp -an \"\$target/.\" \"/napcat/data/\$name/\"; fi; mv \"\$target\" \"\$target.bundled\"; fi; ln -s \"/napcat/data/\$name\" \"\$target\"; done", "napcat-install", "$guest/qq/opt/QQ/resources/app/app_launcher"))
        onStage("configure")
        val metadata = JSONObject().put("version", selected.version).put("qqVersion", selected.qqVersion).put("release", release)
        configure(release)
        val output = manifest.startWrite()
        try {
            output.write(metadata.toString().toByteArray(Charsets.UTF_8))
            manifest.finishWrite(output)
        } catch (e: Exception) { manifest.failWrite(output); throw e }
        onProgress(1f)
    }

    suspend fun configure(releaseOverride: String? = null) = withContext(Dispatchers.IO) {
        val config = settings.current()
        val token = config.token.ifBlank { newToken() }
        val webToken = config.webUiToken.ifBlank { newToken() }
        val dir = File(base, "data/config").apply { mkdirs() }
        val webFile = File(dir, "webui.json")
        if (!webFile.isFile) writeAtomic(webFile, JSONObject().put("host", "127.0.0.1").put("port", 6099)
            .put("token", webToken).put("loginRate", 10).put("disableWebUI", false).toString())
        val oneBotFile = File(dir, "onebot11.json")
        if (!oneBotFile.isFile) writeAtomic(oneBotFile, oneBotConfig(token).toString())
        val logFile = File(dir, "napcat.json")
        if (!logFile.isFile) writeAtomic(logFile, JSONObject().put("fileLog", false).put("consoleLog", true).toString())
        syncConnection()
        if (config.accountId.matches(Regex("[0-9]{5,20}"))) ensureOneBotAccount(config.accountId)
        val release = releaseOverride ?: installed()?.getString("release") ?: error("NapCat is not installed")
        require(release.matches(Regex("release-[0-9]+")))
        // 屏幕参数显式给定：xvfb-run 各发行版默认值不一，1x1x8 这类会卡住 QQ 初始化。
        writeAtomic(File(base, "launch.sh"), "#!/bin/bash\nset -e\ncd '/napcat/releases/$release/qq/opt/QQ/resources/app/app_launcher/napcat'\nexec /usr/bin/xvfb-run -a -s '-screen 0 1280x1024x24' '/napcat/releases/$release/qq/opt/QQ/qq' --no-sandbox --disable-dev-shm-usage\n")
    }

    suspend fun ensureOneBotAccount(account: String) = withContext(Dispatchers.IO) {
        require(account.matches(Regex("[0-9]{5,20}")))
        val dir = File(base, "data/config")
        val file = NapCatLocalConfig.accountFile(dir, account)
        // 登录后 NapCat 会自己生成一份空 network 的账号配置，而账号级文件优先于模板；
        // 只判“文件不存在”会让先登录再配置的账号永远开不了 HTTP/WebSocket。
        val template = File(dir, "onebot11.json")
        if (!template.isFile) return@withContext
        val usable = runCatching { NapCatLocalConfig.hasEnabledServer(JSONObject(file.readText())) }.getOrDefault(false)
        if (!usable) writeAtomic(file, template.readText())
    }

    suspend fun syncConnection(account: String? = null) = withContext(Dispatchers.IO) {
        val config = settings.current()
        val dir = File(base, "data/config")
        val selectedAccount = account ?: config.accountId
        val accountFile = if (selectedAccount.matches(Regex("[0-9]{5,20}"))) NapCatLocalConfig.accountFile(dir, selectedAccount) else null
        val oneBotFile = accountFile?.takeIf { it.isFile } ?: File(dir, "onebot11.json")
        val connection = NapCatLocalConfig.connection(JSONObject(File(dir, "webui.json").readText()), JSONObject(oneBotFile.readText()))
        if (config.baseUrl != connection.httpUrl || config.webUiUrl != connection.webUiUrl ||
            config.token != connection.token || config.webUiToken != connection.webUiToken) {
            settings.setConnection(connection.httpUrl, connection.webUiUrl, connection.token, connection.webUiToken)
        }
        if (config.websocketUrl != connection.websocketUrl) settings.setWebsocketUrl(connection.websocketUrl)
    }

    suspend fun accountConfig(account: String): JSONObject = withContext(Dispatchers.IO) {
        ensureOneBotAccount(account)
        syncConnection(account)
        JSONObject(NapCatLocalConfig.accountFile(File(base, "data/config"), account).readText())
    }

    suspend fun updateConnection(field: String, value: String, account: String = "") = withContext(Dispatchers.IO) {
        check(installedVersion().isNotEmpty()) { "Install NapCat before changing its connection" }
        val previous = settings.current()
        val dir = File(base, "data/config")
        val webFile = File(dir, "webui.json")
        val template = File(dir, "onebot11.json")
        val accountFile = if (account.matches(Regex("[0-9]{5,20}"))) NapCatLocalConfig.accountFile(dir, account).takeIf { it.isFile } else null
        val originals = (listOf(webFile, template) + listOfNotNull(accountFile)).associateWith { it.readText() }
        val web = JSONObject(originals.getValue(webFile))
        val changed = mutableMapOf<File, JSONObject>()
        (listOf(template) + listOfNotNull(accountFile)).forEach { file ->
            val oneBot = JSONObject(originals.getValue(file))
            NapCatLocalConfig.update(field, value, web, oneBot)
            NapCatLocalConfig.connection(web, oneBot)
            changed[file] = oneBot
        }
        try {
            writeAtomic(webFile, web.toString())
            changed.forEach { (file, config) -> writeAtomic(file, config.toString()) }
            syncConnection(account.ifEmpty { null })
        } catch (e: Exception) {
            val restored = withContext(NonCancellable + Dispatchers.IO) {
                var success = true
                originals.forEach { (file, text) -> runCatching { writeAtomic(file, text) }.onFailure { success = false } }
                runCatching {
                    settings.setConnection(previous.baseUrl, previous.webUiUrl, previous.token, previous.webUiToken)
                    settings.setWebsocketUrl(previous.websocketUrl)
                }.onFailure { success = false }
                success
            }
            if (e is CancellationException && restored) throw e
            throw IllegalStateException(if (restored) "NapCat connection update failed; original configuration restored" else "NapCat connection update failed; configuration rollback failed", e)
        }
    }

    fun runtimeEnvironment(lastAccountId: String = ""): Map<String, String> {
        require(lastAccountId.isEmpty() || lastAccountId.matches(Regex("[0-9]{5,20}"))) { "Invalid QQ recovery account" }
        val selection = File(base, "data/qq-profile")
        val selected = if (selection.isFile) selection.readText().trim() else "profile-initial"
        require(selected.matches(Regex("profile-[A-Za-z0-9-]+"))) { "Invalid QQ profile selection" }
        File(base, "data/qq-profiles/$selected").mkdirs()
        return mapOf(
            "HOME" to "/root", "XDG_CONFIG_HOME" to "/root/.config",
            "NAPCAT_QQ_DATA_DIR" to "/napcat/data/qq-profiles/$selected",
            "NAPCAT_WORKDIR" to "/napcat/data",
            "NAPCAT_QUICK_ACCOUNT" to lastAccountId, "NAPCAT_QUICK_PASSWORD" to "", "NAPCAT_QUICK_PASSWORD_MD5" to ""
        )
    }

    fun rotateLoginProfile() {
        // 官方覆盖变量让新档案从未登录状态启动，旧票据与聊天数据原封不动保留，不冒充服务端吊销。
        val name = "profile-${java.util.UUID.randomUUID()}"
        File(base, "data/qq-profiles/$name").mkdirs()
        val webFile = File(base, "data/config/webui.json")
        val webConfig = JSONObject(webFile.readText()).put("autoLoginAccount", "")
        writeAtomic(webFile, webConfig.toString())
        writeAtomic(File(base, "data/qq-profile"), name)
    }

    fun oneBotConfig(token: String): JSONObject = JSONObject().put("network", JSONObject()
        .put("httpServers", JSONArray().put(JSONObject().put("name", "aharou-http").put("enable", true)
            .put("host", "127.0.0.1").put("port", 3001).put("token", token)
            .put("enableCors", false).put("enableWebsocket", false)))
        .put("websocketServers", JSONArray().put(JSONObject().put("name", "aharou-ws").put("enable", true)
            .put("host", "127.0.0.1").put("port", 3002).put("token", token)
            .put("reportSelfMessage", true).put("enableForcePushEvent", true).put("messagePostFormat", "array")))
        .put("httpClients", JSONArray()).put("websocketClients", JSONArray()))
        .put("musicSignUrl", "").put("enableLocalFile2Url", false).put("parseMultMsg", false)

    private suspend fun download(url: String, name: String, sha256: String? = null, progress: (Float) -> Unit): File =
        downloadCandidates(catalog.candidates(url, sha256), name, progress).first

    private suspend fun downloadCandidates(
        candidates: List<NapCatDownloadCandidate>,
        name: String,
        progress: (Float) -> Unit
    ): Pair<File, NapCatDownloadCandidate> {
        val failures = mutableListOf<String>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val host = URI(candidate.url).host ?: "unknown-host"
            var stage = "download/verification"
            var source: File? = null
            val temporary = File(base, "downloads/$name.part")
            try {
                val uri = downloader.download(
                    candidate.url, name, candidate.sha256,
                    connectTimeoutSeconds = 30, readTimeoutSeconds = 120
                ) { read, total ->
                    progress(if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f)
                }
                source = File(Uri.parse(uri).path ?: throw IOException("Missing download path"))
                currentCoroutineContext().ensureActive()
                stage = "cache"
                source.inputStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
                val target = File(base, "downloads/$name")
                if (!temporary.renameTo(target)) throw IOException("Cannot commit NapCat download")
                return target to candidate
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (e is ContainerImageChecksumException) stage = "checksum"
                failures += "$host: $stage (${e.javaClass.simpleName})"
            } finally {
                source?.delete()
                temporary.delete()
            }
        }
        throw IOException("NapCat download failed: $name; ${failures.joinToString("; ")}")
    }

    private fun guestPath(file: File): String = "/napcat/" + file.relativeTo(base).invariantSeparatorsPath

    private suspend fun runCommand(program: String, args: List<String>, onLog: (String) -> Unit) = withTimeout(30 * 60 * 1000L) {
        withContext(Dispatchers.IO) {
            val tracked = engine.startTrackedStdioProcess(program, args, null, "napcat:install",
                extraEnv = mapOf("DEBIAN_FRONTEND" to "noninteractive"), profile = profile(), inheritUserConfig = false)
            val identity = ProcessIdentity.capture(tracked.process, fallbackPid = tracked.pid)
            fun terminate() {
                if (!tracked.process.isAlive) return
                if (identity != null) check(ProcessIdentity.terminateTree(identity)) { "NapCat installation process tree did not stop" }
                else tracked.process.destroyForcibly()
            }
            try {
                tracked.process.outputStream.close()
                onLog("Command started: $program")
                coroutineScope {
                    val tail = NapCatInstallOutput(onLog)
                    val stdout = async { readNapCatCommandOutput(tracked.process.inputStream, { tracked.process.isAlive }, tail::append) }
                    val stderr = async { readNapCatCommandOutput(tracked.process.errorStream, { tracked.process.isAlive }, tail::append) }
                    val finished = try {
                        runInterruptible { tracked.process.waitFor(29, TimeUnit.MINUTES) }
                    } finally {
                        terminate()
                    }
                    check(finished) { "NapCat installation command timed out" }
                    stdout.await(); stderr.await()
                    check(tracked.process.exitValue() == 0) { "NapCat installation command failed: $program (${tracked.process.exitValue()}): ${tail.text()}" }
                    onLog("Command completed: $program")
                }
            } finally {
                try {
                    terminate()
                    if (tracked.process.waitFor(5, TimeUnit.SECONDS)) tracked.clearLedger()
                } finally {
                    runCatching { tracked.process.inputStream.close() }
                    runCatching { tracked.process.errorStream.close() }
                    runCatching { tracked.process.outputStream.close() }
                }
            }
        }
    }

    private fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(text.toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (e: Exception) { atomic.failWrite(output); throw e }
    }

    private fun newToken(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }


}
