package com.aharou.feature.plugin.runtime

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.plugin.data.PluginPackageReader
import com.aharou.feature.plugin.domain.PluginRuntime
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** 依赖准备结果；UI 按类型展示状态与原因。 */
sealed interface PluginDependencyResult {
    /** 依赖已就绪；[hash] 是依赖输入文件的摘要，文件变化后需要重新准备。 */
    data class Ready(val hash: String) : PluginDependencyResult

    /** 容器里缺少 node/npm 或 python3/venv，引导用户去终端「环境安装」。 */
    data class MissingRuntime(val message: String) : PluginDependencyResult

    /** 安装命令失败，[message] 带截断后的输出。 */
    data class Failed(val message: String, val code: String? = null) : PluginDependencyResult
}

/** 依赖产出目录名与工具名；装机命令与缓存布局都按它定位。 */
internal object DependencyLayout {
    const val NODE_MODULES = "node_modules"
    const val VENV = ".venv"
    const val SITE_PACKAGES = "site-packages"
    const val TOOL_NPM = "npm"
    const val TOOL_PIP = "pip"
}

/**
 * 依赖目录的搬迁工具。
 *
 * 依赖树里有 npm 生成的相对链接与可执行位：按普通文件递归复制会把链接摊成文件、丢掉权限位，
 * 缓存里的 `.bin` 命令随即失效，所以这里逐项按 NOFOLLOW 处理，能保链接就保链接、能保属性就保属性。
 */
internal object DependencyFiles {

    fun exists(file: File): Boolean = Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** 删除文件、目录或链接本体；链接只删链接，不进目标目录。 */
    fun delete(file: File) {
        if (!exists(file)) return
        Files.walkFileTree(
            file.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }

    fun copy(from: File, to: File) {
        val source = from.toPath()
        val target = to.toPath()
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val destination = target.resolve(source.relativize(file))
                    if (attrs.isSymbolicLink) {
                        val link = Files.readSymbolicLink(file)
                        val linked = runCatching { Files.createSymbolicLink(destination, link) }
                        if (linked.isFailure) {
                            Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                        }
                    } else {
                        Files.copy(file, destination, StandardCopyOption.COPY_ATTRIBUTES)
                    }
                    return FileVisitResult.CONTINUE
                }
            }
        )
    }
}

/**
 * 在本地容器准备插件运行时依赖。
 *
 * 命令完全由宿主构造，不接受插件传入；依赖装好之后由 [PluginDependencyStore] 收进独立缓存，
 * 插件包目录只保留一个指向缓存的链接，升级替换插件包时依赖不会跟着被删掉。
 */
@Singleton
class PluginDependencyPreparer @Inject constructor(
    private val installer: ContainerInstaller,
    private val engine: LinuxContainerEngine,
    private val containerSettingsRepository: ContainerSettingsRepository
) {

    suspend fun prepare(runtime: PluginRuntime): PluginDependencyResult {
        val dependency = runtime.dependencies
            ?: return PluginDependencyResult.Ready("")
        val profile = runtimeProfile()
        engine.notReadyHintFor(profile)?.let {
            return PluginDependencyResult.MissingRuntime(it)
        }

        val missing = if (dependency.tool == DependencyLayout.TOOL_NPM) checkCommands("node", "npm")
        else checkCommands("python3")
        if (missing.isNotEmpty()) {
            return PluginDependencyResult.MissingRuntime(
                "容器缺少 ${missing.joinToString("、")}，请到终端执行「环境安装」后重试。"
            )
        }

        return when (dependency.tool) {
            DependencyLayout.TOOL_NPM -> prepareNpm(runtime, dependency.file)
            DependencyLayout.TOOL_PIP -> preparePip(runtime, dependency.file)
            else -> PluginDependencyResult.Failed("不支持的依赖工具: ${dependency.tool}")
        }
    }

    /**
     * npm 装在插件目录之外的暂存目录里，再整体搬进来。
     *
     * 直接 `cd 插件目录 && npm install` 会把 devDependencies 一起装进来、体积翻倍；
     * 清单由宿主读出来只留生产依赖，插件包里的安装脚本也不会被执行。
     */
    private suspend fun prepareNpm(runtime: PluginRuntime, dependencyFile: File): PluginDependencyResult {
        val hash = sha256(dependencyFile)
        val manifest = runCatching { productionPackageJson(dependencyFile) }.getOrElse { error ->
            return PluginDependencyResult.Failed(
                "依赖清单无法解析: ${error.message ?: error.javaClass.simpleName}"
            )
        }
        // 空清单下 npm 不会生成 node_modules，按失败报会误导使用者。
        if (!manifest.hasDependencies) return PluginDependencyResult.Ready(hash)
        val parent = runtime.installDir.parentFile
            ?: return PluginDependencyResult.Failed("插件目录没有父目录，无法准备依赖")
        val tempDir = File(parent, ".deps-${UUID.randomUUID()}")
        return try {
            if (!tempDir.mkdirs()) throw IOException("无法创建 npm 依赖暂存目录")
            // npm 只认固定文件名，清单声明成别的名字时也得落到 package.json 上。
            File(tempDir, "package.json").writeText(manifest.text)
            val tempPath = shellQuote(installer.containerPathFor(tempDir))
            val command = "cd $tempPath && npm install --no-save --package-lock=false --no-audit --no-fund"
            FileLogger.i(TAG, "[${runtime.pluginId}] 准备依赖: npm ${dependencyFile.name}")
            val result = runCommand(command)
            if (result.exitCode != 0) {
                FileLogger.w(TAG, "[${runtime.pluginId}] 依赖安装失败 exitCode=${result.exitCode}")
                return PluginDependencyResult.Failed(tail(result.output))
            }
            val installed = File(tempDir, DependencyLayout.NODE_MODULES)
            if (!installed.isDirectory) {
                return PluginDependencyResult.Failed("npm 安装完成但未生成 node_modules")
            }
            replaceDependencyOutput(runtime.installDir, installed)
            FileLogger.i(TAG, "[${runtime.pluginId}] 依赖准备完成")
            PluginDependencyResult.Ready(hash)
        } catch (e: Exception) {
            FileLogger.w(TAG, "[${runtime.pluginId}] 依赖准备失败", e)
            PluginDependencyResult.Failed("依赖安装失败: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            DependencyFiles.delete(tempDir)
        }
    }

    private suspend fun preparePip(runtime: PluginRuntime, dependencyFile: File): PluginDependencyResult {
        val dir = shellQuote(installer.containerPathFor(runtime.installDir))
        val file = shellQuote(installer.containerPathFor(dependencyFile))
        // 上一次的 .venv 可能已经是指向缓存的链接：venv 会隔着链接往旧缓存里写，先摘掉链接再重建。
        val venv = File(runtime.installDir, DependencyLayout.VENV)
        if (Files.isSymbolicLink(venv.toPath())) {
            val unlinked = runCatching { Files.delete(venv.toPath()) }
            if (unlinked.isFailure) {
                return PluginDependencyResult.Failed(
                    "无法解除旧的依赖链接: ${unlinked.exceptionOrNull()?.message ?: "IO 失败"}"
                )
            }
        }
        val command = "cd $dir && python3 -m venv .venv && .venv/bin/pip install --disable-pip-version-check -r $file"
        FileLogger.i(TAG, "[${runtime.pluginId}] 准备依赖: pip ${dependencyFile.name}")
        val result = runCommand(command)
        if (result.exitCode != 0) {
            FileLogger.w(TAG, "[${runtime.pluginId}] 依赖安装失败 exitCode=${result.exitCode}")
            return PluginDependencyResult.Failed(tail(result.output))
        }
        FileLogger.i(TAG, "[${runtime.pluginId}] 依赖准备完成")
        return PluginDependencyResult.Ready(sha256(dependencyFile))
    }

    private class NpmManifest(val text: String, val hasDependencies: Boolean)

    /** 只保留生产依赖，devDependencies 与 scripts 一律不进容器。 */
    private fun productionPackageJson(file: File): NpmManifest {
        val source = Json.parseToJsonElement(file.readText()).jsonObject
        val dependencies = source["dependencies"] as? JsonObject ?: JsonObject(emptyMap())
        val production = buildJsonObject {
            source["name"]?.let { put("name", it) }
            source["version"]?.let { put("version", it) }
            put("private", true)
            put("dependencies", dependencies)
        }
        val text = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), production)
        return NpmManifest(text, dependencies.isNotEmpty())
    }

    /** 暂存目录与插件目录在同一文件系统，先 rename 省一次全量复制，失败再老老实实复制。 */
    private fun replaceDependencyOutput(pluginDir: File, source: File) {
        val target = File(pluginDir, DependencyLayout.NODE_MODULES)
        DependencyFiles.delete(target)
        if (source.renameTo(target)) return
        DependencyFiles.copy(source, target)
    }

    private suspend fun runCommand(command: String): CommandResult {
        return try {
            val result = engine.runCommandSyncWithExit(
                command,
                projectPath = null,
                timeoutMs = PREPARE_TIMEOUT_MS
            )
            CommandResult(result.exitCode ?: 1, result.output)
        } catch (error: Exception) {
            CommandResult(1, error.message ?: error.javaClass.simpleName)
        }
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private suspend fun checkCommands(vararg commands: String): List<String> {
        val missing = mutableListOf<String>()
        commands.forEach { name ->
            val result = runCatching {
                engine.runCommandSyncWithExit(
                    "command -v ${shellQuote(name)}",
                    projectPath = null,
                    timeoutMs = CHECK_TIMEOUT_MS
                )
            }.getOrNull()
            if (result == null || result.exitCode != 0) missing += name
        }
        return missing
    }

    private fun sha256(file: File): String =
        PluginPackageReader.sha256Hex(file.readBytes())

    private fun tail(output: String): String {
        val lines = output.trim().lines().takeLast(MAX_TAIL_LINES).joinToString("\n")
        return lines.take(MAX_TAIL_CHARS).ifBlank { "依赖安装失败，未产生输出" }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private suspend fun runtimeProfile(): ContainerProfile {
        val id = containerSettingsRepository.defaultContainerIdFlow.first()
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id && it.mode == ExecutionMode.LOCAL_PROOT }
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    private companion object {
        const val TAG = "PluginDeps"
        const val PREPARE_TIMEOUT_MS = 10 * 60_000L
        const val CHECK_TIMEOUT_MS = 30_000L
        const val MAX_TAIL_LINES = 20
        const val MAX_TAIL_CHARS = 2_000
    }
}

/**
 * 插件运行时依赖的独立缓存。
 *
 * 依赖留在插件包目录里时，升级要整包替换、依赖跟着被删，同版本重装也得重装一遍依赖；
 * 这里按「插件 id + 依赖输入指纹」分目录存放，插件包目录只留一个指向缓存的相对链接，
 * 依赖树因此能在插件版本之间复用。缓存目录写完完成标记才算就绪，中途崩溃只留下暂存目录。
 *
 * 目录由宿主构造，全部落在 `~/.aharou/plugins/dependencies` 下——容器里是同一份绑定目录，
 * 所以链接在容器内也能解析。
 */
class PluginDependencyStore(private val rootDir: File) {

    private val dependenciesDir = File(rootDir, DEPENDENCIES_DIR)
    private val stagingDir = File(dependenciesDir, STAGING_DIR)

    /** 依赖输入指纹：运行环境 + 工具 + 清单文件与锁文件的内容。任一变化都要重新准备。 */
    fun fingerprint(runtime: PluginRuntime): String {
        val input = buildString {
            append(runtime.type).append('\n')
            append(runtime.dependencies?.tool.orEmpty()).append('\n')
            append(runtime.dependencies?.file?.name.orEmpty()).append('\n')
            val files = runtime.dependencies?.file?.let { dependencyFiles(it) }.orEmpty()
            files.sortedBy { it.name }.forEach { file ->
                append(file.name).append('\n')
                append(PluginPackageReader.sha256Hex(file.readBytes())).append('\n')
            }
        }
        return PluginPackageReader.sha256Hex(input.toByteArray(Charsets.UTF_8))
    }

    fun directory(pluginId: String, fingerprint: String): File =
        File(File(dependenciesDir, pluginId), fingerprint)

    fun isReady(pluginId: String, fingerprint: String): Boolean {
        val dir = directory(pluginId, fingerprint)
        val marker = File(dir, COMPLETE_MARKER)
        return marker.isFile && existingOutput(dir).let { DependencyFiles.exists(it) }
    }

    /**
     * 插件目录里该放依赖的位置。
     *
     * 已知工具时按固定名字取，不能用存在性判断：迁移后这里是悬空链接，`exists()` 会返回 false。
     */
    fun dependencyOutput(installDir: File, tool: String?): File = when (tool) {
        DependencyLayout.TOOL_NPM -> File(installDir, DependencyLayout.NODE_MODULES)
        DependencyLayout.TOOL_PIP -> File(installDir, DependencyLayout.VENV)
        else -> existingOutput(installDir)
    }

    /** 插件目录里是真实的依赖目录（不是指向缓存的链接）。 */
    fun hasRealOutput(installDir: File, tool: String?): Boolean {
        val output = dependencyOutput(installDir, tool)
        return DependencyFiles.exists(output) && !Files.isSymbolicLink(output.toPath())
    }

    /** 插件目录里的依赖目录是链接（含悬空链接）。 */
    fun isLinked(installDir: File, tool: String?): Boolean =
        Files.isSymbolicLink(dependencyOutput(installDir, tool).toPath())

    /**
     * 把插件目录里已经装好的依赖收进缓存。
     *
     * 旧布局的插件不重装即可迁移：先整树复制到暂存目录，提交成功才替换，失败时原目录一动不动。
     */
    fun capture(runtime: PluginRuntime, fingerprint: String): File {
        val source = dependencyOutput(runtime.installDir, runtime.dependencies?.tool)
        if (!DependencyFiles.exists(source) || Files.isSymbolicLink(source.toPath())) {
            throw IOException("插件目录里没有可迁移的依赖")
        }
        val staging = File(stagingDir, "${runtime.pluginId}-${UUID.randomUUID()}")
        if (!staging.mkdirs()) throw IOException("无法创建依赖暂存目录")
        try {
            DependencyFiles.copy(source, File(staging, source.name))
        } catch (e: IOException) {
            DependencyFiles.delete(staging)
            throw e
        }
        return commit(runtime.pluginId, fingerprint, staging)
    }

    /**
     * 把缓存挂回插件目录，返回是否用了链接。
     *
     * 换链接分三步：真实目录先改名挪成备份 → 新链接换名到目标 → 删备份。每一步都是原子 rename，
     * 进程在任意一步被杀都只会停在「备份还在、目标缺失」或「链接已就位、备份未删」两种可恢复状态。
     * 链接建不出来（文件系统不支持）时退回整树复制，插件照样能跑，只是失去多版本复用。
     */
    fun link(pluginDir: File, fingerprint: String, pluginId: String): Boolean {
        val cached = directory(pluginId, fingerprint)
        if (!isReady(pluginId, fingerprint)) throw IOException("依赖缓存未准备完成")
        val output = existingOutput(cached)
        val target = File(pluginDir, output.name)
        restoreInterruptedLink(target)
        cleanupLinkRemnants(pluginDir, output.name)
        val base = pluginDir.toPath().toAbsolutePath().normalize()
        val relative = base.relativize(output.toPath().toAbsolutePath().normalize())
        val tempLink = File(pluginDir, ".${output.name}.link-${UUID.randomUUID()}")
        val created = runCatching {
            Files.createSymbolicLink(tempLink.toPath(), relative)
            true
        }.getOrElse { error ->
            FileLogger.w(TAG, "[$pluginId] 无法创建依赖链接，改为复制", error)
            DependencyFiles.delete(tempLink)
            false
        }
        if (created) {
            val backup = replacedPath(target)
            // 真实目录挪不开就维持原样：宁可继续用包里的依赖，也不能让插件少了依赖目录。
            if (DependencyFiles.exists(target) && !target.renameTo(backup)) {
                DependencyFiles.delete(tempLink)
                return false
            }
            if (!tempLink.renameTo(target)) {
                if (DependencyFiles.exists(backup)) backup.renameTo(target)
                DependencyFiles.delete(tempLink)
                return false
            }
            DependencyFiles.delete(backup)
            return true
        }
        if (DependencyFiles.exists(target)) return false
        val copied = File(pluginDir, ".${output.name}.copy-${UUID.randomUUID()}")
        try {
            DependencyFiles.copy(output, copied)
        } catch (e: IOException) {
            DependencyFiles.delete(copied)
            throw e
        }
        if (!copied.renameTo(target)) {
            DependencyFiles.delete(copied)
            return false
        }
        return false
    }

    /** 旧布局（依赖真实留在插件目录里）就地迁移；返回是否完成迁移，失败时原目录保留。 */
    fun migrateLegacy(runtime: PluginRuntime, pluginId: String): Boolean {
        val output = dependencyOutput(runtime.installDir, runtime.dependencies?.tool)
        restoreInterruptedLink(output)
        if (!DependencyFiles.exists(output) || Files.isSymbolicLink(output.toPath())) return false
        val fingerprint = runCatching { fingerprint(runtime) }.getOrNull() ?: return false
        return runCatching {
            if (!isReady(pluginId, fingerprint)) capture(runtime, fingerprint)
            link(runtime.installDir, fingerprint, pluginId)
            true
        }.getOrElse { error ->
            FileLogger.w(TAG, "[$pluginId] 依赖缓存迁移失败，继续使用包目录里的依赖", error)
            false
        }
    }

    /**
     * 上一次迁移在「真实目录已挪走、链接还没就位」时被打断，先把真实目录还原回去。
     * 还原优先于迁移，否则插件目录会少一个依赖目录，插件直接起不来。
     */
    private fun restoreInterruptedLink(output: File) {
        val backup = replacedPath(output)
        if (!DependencyFiles.exists(backup)) return
        if (DependencyFiles.exists(output)) DependencyFiles.delete(backup)
        else backup.renameTo(output)
    }

    private fun replacedPath(output: File): File = File(output.parentFile, ".${output.name}.replaced")

    private fun cleanupLinkRemnants(pluginDir: File, name: String) {
        val linkPrefix = ".$name.link-"
        val copyPrefix = ".$name.copy-"
        pluginDir.listFiles().orEmpty()
            .filter { it.name.startsWith(linkPrefix) || it.name.startsWith(copyPrefix) }
            .forEach { DependencyFiles.delete(it) }
    }

    fun commit(pluginId: String, fingerprint: String, staging: File): File {
        val target = directory(pluginId, fingerprint)
        target.parentFile?.mkdirs()
        if (isReady(pluginId, fingerprint)) {
            DependencyFiles.delete(staging)
            return target
        }
        DependencyFiles.delete(target)
        if (!staging.renameTo(target)) {
            DependencyFiles.delete(staging)
            throw IOException("无法提交插件依赖缓存")
        }
        File(target, COMPLETE_MARKER).writeText("$fingerprint\n")
        return target
    }

    fun cleanupStaging() {
        DependencyFiles.delete(stagingDir)
    }

    fun deleteForPlugin(pluginId: String) {
        val dir = File(dependenciesDir, pluginId)
        requireInside(dependenciesDir, dir)
        DependencyFiles.delete(dir)
    }

    /**
     * 清掉不再被引用的缓存。
     *
     * 只清理插件还在、但指纹已换掉的旧版本目录；指纹算不出来（清单读不出）的插件整份保留，
     * 不认识的插件 id 也不动——宁可留下垃圾，也不能在记录读坏时把还能用的依赖删掉。
     */
    fun pruneUnreferenced(knownIds: Set<String>, referenced: Map<String, Set<String>>) {
        dependenciesDir.listFiles().orEmpty()
            .filter { it.isDirectory && it.name != STAGING_DIR }
            .forEach { pluginDir ->
                if (pluginDir.name !in knownIds) return@forEach
                val keep = referenced[pluginDir.name] ?: return@forEach
                requireInside(dependenciesDir, pluginDir)
                pluginDir.listFiles().orEmpty()
                    .filter { it.isDirectory && it.name !in keep }
                    .forEach { DependencyFiles.delete(it) }
            }
    }

    private fun existingOutput(root: File): File =
        File(root, DependencyLayout.NODE_MODULES).takeIf { DependencyFiles.exists(it) }
            ?: File(root, DependencyLayout.VENV).takeIf { DependencyFiles.exists(it) }
            ?: File(root, DependencyLayout.SITE_PACKAGES)

    private fun dependencyFiles(file: File): List<File> {
        val parent = file.parentFile ?: return listOf(file)
        return listOf(file) + LOCK_FILES.map { File(parent, it) }.filter { it.isFile && it != file }
    }

    private fun requireInside(root: File, file: File) {
        val base = root.toPath().toAbsolutePath().normalize()
        val path = file.toPath().toAbsolutePath().normalize()
        if (path == base || !path.startsWith(base)) throw IOException("依赖缓存操作越界: $path")
    }

    private companion object {
        const val TAG = "PluginDeps"
        const val DEPENDENCIES_DIR = "dependencies"
        const val STAGING_DIR = ".staging"
        const val COMPLETE_MARKER = ".complete"
        val LOCK_FILES = listOf(
            "package-lock.json",
            "npm-shrinkwrap.json",
            "requirements.lock"
        )
    }
}
