package com.aharou.feature.workspace.domain

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.settings.data.repository.ContainerSettingsRepository
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 在「容器内路径」与「宿主真实路径」之间互转，让 AI 只看到 / 只使用容器路径。
 *
 * 背景：容器是 PRoot 以当前 profile 的 rootfs 目录为根（`-r rootfs`）跑起来的，
 * 当前工作区目录又被 bind 成容器内的 [CONTAINER_ROOT]（`~/workspace`，展开为 `$HOME/workspace`）。因此「容器内路径」到「宿主真实文件」有两条确定映射：
 * - `~/workspace[/…]` → 宿主工作区目录（写它即写宿主，且容器内可见——bind mount）；
 * - 其它容器绝对路径 `/etc/…`、`/root/…` → 当前 profile rootfs 目录下对应文件（与终端在容器里看到的完全是同一批文件）。
 *
 * 这样文件类工具（read/write/edit）无需进 PRoot 即可读写整个容器文件系统，与 `execute_command` 看到的一致。
 *
 * **profile 感知**：rootfs 目录随当前选中 profile 变化（内置 Alpine 用 filesDir/rootfs，自定义用 filesDir/rootfs_<id>）。
 * [currentProfile] 缓存自 [ContainerSettingsRepository]，避免同步读 DataStore；启动首帧为内置 Alpine，等同改动前。
 *
 * 用本映射器统一：
 * - 工具入参（AI 给的路径）经 [toHostFile] 落到宿主真实文件；
 * - 工具回显/返回的路径经 [toContainerPath] 还原成容器视角（`~/workspace/…` 或 `/etc/…`），对 AI 只暴露容器路径。
 */
@Singleton
class WorkspacePathMapper @Inject constructor(
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val containerSettingsRepository: ContainerSettingsRepository,
    private val pathHomeResolver: PathHomeResolver
) {
    companion object {
        /** AI 看到的工作区根路径。用 `~` 形式让提示词/工具描述更自然，内部用 [resolvedContainerRoot] 展开后匹配。 */
        const val CONTAINER_ROOT = "~/workspace"
        /** AI 配置目录在容器内的**主**路径（宿主 filesDir/aharou，独立于 rootfs）；旧路径 /root/.aicode 兼容保留。 */
        const val AHAROU_ROOT = "/root/.aharou"
        /** 旧路径（兼容）：与 [AHAROU_ROOT] 指向同一宿主目录。 */
        const val AICODE_ROOT = "/root/.aicode"
        /** 记忆目录（SOUL.md / 核心档案 / 日志）在容器内的路径（宿主 filesDir/aharou-global/memory）。 */
        const val AHAROU_MEMORY_ROOT = "/root/.aharou/memory"
        /** 跨工作区共享区在容器内的路径（宿主 filesDir/shared）：所有工作区共用同一份。 */
        const val SHARED_ROOT = "/root/shared"
        private const val TAG = "WorkspacePathMapper"
    }

    /**
     * 当前选中的 profile（缓存，避免同步读 DataStore）。启动首帧为内置 Alpine，等同改动前。
     * profile 切换后由 flow collector 更新；切换瞬间与引擎缓存可能短暂不一致，但 ensureInstalled 与文件工具
     * 调用之间有自然顺序，实际不影响。
     */
    @Volatile
    private var currentProfile: ContainerProfile = ContainerProfile.BUILTIN_ALPINE

    init {
        CoroutineScope(Dispatchers.IO).launch {
            containerSettingsRepository.activeProfileIdFlow.collect { id ->
                currentProfile = resolveProfile(id)
            }
        }
    }

    private suspend fun resolveProfile(id: String): ContainerProfile {
        val profiles = containerSettingsRepository.customProfilesFlow.first()
        return profiles.firstOrNull { it.id == id }
            ?: profiles.firstOrNull()
            ?: ContainerProfile.BUILTIN_ALPINE
    }

    /** 当前工作区在宿主上的根目录。 */
    private fun hostRoot(): File = File(workspaceRepository.currentPath())

    /** [CONTAINER_ROOT] 展开后的绝对路径（`$HOME/workspace`），供路径匹配使用。home 未就绪时回退 `/root`。 */
    private fun resolvedContainerRoot(): String =
        pathHomeResolver.home().trimEnd('/') + "/workspace"

    /** 容器 rootfs 在宿主上的根目录（容器内 `/` 即此目录，随当前 profile 变化）。 */
    private fun rootfsRoot(): File = containerInstaller.rootfsDirFor(currentProfile)

    /** AI 配置目录在宿主上的根（容器内 `/root/.aharou` 与旧 `/root/.aicode` 即此目录，独立于 rootfs）。 */
    private fun aicodeRoot(): File = containerInstaller.aharouDir

    /** 记忆目录（SOUL.md 等）在宿主上的根（容器内 `/root/.aharou/memory`）。 */
    private fun aharouMemoryRoot(): File = File(aicodeRoot().parentFile, "aharou-global/memory")

    /** 跨工作区共享区在宿主上的根（容器内 `/root/shared`）。 */
    private fun sharedRoot(): File = containerInstaller.sharedDir

    /**
     * 把 AI 提供的路径解析为宿主真实文件。兼容以下写法：
     * - 容器绝对路径 `~/workspace[/…]`（或展开后的 `$HOME/workspace[/…]`）→ 映射到宿主工作区；
     * - 容器绝对路径 `/root/.aicode[/…]` → 映射到宿主 AI 配置目录（skill / mcp.json，独立于 rootfs）；
     * - 其它容器绝对路径 `/etc/…`、`/root/…` → 映射到 rootfs 内对应文件（容器系统文件）；
     * - 相对路径 `src/Main.kt` → 挂到宿主工作区根下；
     *
     * `/root/.aicode` 必须先于通用 `/`→rootfs 规则匹配，否则会落到 rootfs 内的临时副本（升级即丢）。
     */
    fun toHostFile(path: String): File {
        val root = hostRoot()
        val wsRoot = resolvedContainerRoot()
        val p = pathHomeResolver.expandHome(path.trim())
        val file = when {
            p == wsRoot || p == "$wsRoot/" || p == CONTAINER_ROOT || p == "$CONTAINER_ROOT/" -> root
            p.startsWith("$wsRoot/") -> File(root, p.removePrefix("$wsRoot/"))
            p == AHAROU_MEMORY_ROOT || p == "$AHAROU_MEMORY_ROOT/" -> aharouMemoryRoot()
            p.startsWith("$AHAROU_MEMORY_ROOT/") -> File(aharouMemoryRoot(), p.removePrefix("$AHAROU_MEMORY_ROOT/"))
            p == AHAROU_ROOT || p == "$AHAROU_ROOT/" -> aicodeRoot()
            p.startsWith("$AHAROU_ROOT/") -> File(aicodeRoot(), p.removePrefix("$AHAROU_ROOT/"))
            p == AICODE_ROOT || p == "$AICODE_ROOT/" -> aicodeRoot()
            p.startsWith("$AICODE_ROOT/") -> File(aicodeRoot(), p.removePrefix("$AICODE_ROOT/"))
            p == SHARED_ROOT || p == "$SHARED_ROOT/" -> sharedRoot()
            p.startsWith("$SHARED_ROOT/") -> File(sharedRoot(), p.removePrefix("$SHARED_ROOT/"))
            else -> mountedHostFile(p)?.let { return it }   // 用户显式配置的挂载点，信任其宿主路径
                ?: if (p.startsWith("/")) File(rootfsRoot(), p.removePrefix("/")) else File(root, p)
        }
        // 防穿越：上面的分支全是字符串前缀匹配，`..` 能逃出各根目录——
        // 例如 `/../../shared_prefs/x.xml` 会落到 App 私有数据区（DataStore / 凭据 / settings）。
        // 所以解析成真实路径后，必须确认它仍落在允许的根之内（工作区 / rootfs / AI 配置 / 记忆）。
        val resolved = runCatching { file.canonicalFile }.getOrElse { file }
        val allowed = listOf(root, rootfsRoot(), aicodeRoot(), aharouMemoryRoot(), sharedRoot())
        if (allowed.none { resolved.isUnder(it) }) {
            throw IllegalArgumentException("路径越界，拒绝访问：$path")
        }
        FileLogger.v(TAG, "toHostFile '$path' -> ${file.absolutePath}")
        return file
    }

    /** [this] 的规范化路径是否位于 [base] 之内（两侧都规范化，避免符号链接导致的误判）。 */
    private fun File.isUnder(base: File): Boolean {
        val basePath = runCatching { base.canonicalPath }.getOrElse { base.absolutePath }.trimEnd('/')
        val selfPath = runCatching { canonicalPath }.getOrElse { absolutePath }
        return selfPath == basePath || selfPath.startsWith("$basePath/")
    }

    /**
     * 把宿主路径还原为容器路径：
     * - 位于工作区内 → `~/workspace/…`；
     * - 位于 AI 配置目录内 → `/root/.aicode/…`；
     * - 位于 rootfs 内 → 去掉 rootfs 前缀的容器绝对路径（如 `/etc/apk/repositories`）；
     * - 其余原样返回（极少出现）。
     *
     * 工作区在 `filesDir/projects`、AI 配置在 `filesDir/aicode`、rootfs 在 `filesDir/rootfs`，三者互不重叠，
     * 判断顺序无歧义。
     */
    fun toContainerPath(hostPath: String): String {
        val rootPath = hostRoot().absolutePath.replace('\\', '/')
        val aicodePath = aicodeRoot().absolutePath.replace('\\', '/')
        val memoryPath = aharouMemoryRoot().absolutePath.replace('\\', '/')
        val sharedPath = sharedRoot().absolutePath.replace('\\', '/')
        val rootfsPath = rootfsRoot().absolutePath.replace('\\', '/')
        val abs = File(hostPath).absolutePath.replace('\\', '/')
        val raw = hostPath.trim().replace('\\', '/')
        val resolvedWs = resolvedContainerRoot().replace('\\', '/')
        return when {
            abs == rootPath -> CONTAINER_ROOT
            abs.startsWith("$rootPath/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$rootPath/")
            // 展开后的 $HOME/workspace 形式也还原为 ~/workspace（bind mount 路径可能以绝对形式出现）
            raw == resolvedWs || abs == resolvedWs -> CONTAINER_ROOT
            raw.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + raw.removePrefix("$resolvedWs/")
            abs.startsWith("$resolvedWs/") -> CONTAINER_ROOT + "/" + abs.removePrefix("$resolvedWs/")
            abs == memoryPath -> AHAROU_MEMORY_ROOT
            abs.startsWith("$memoryPath/") -> AHAROU_MEMORY_ROOT + "/" + abs.removePrefix("$memoryPath/")
            abs == aicodePath -> AHAROU_ROOT
            abs.startsWith("$aicodePath/") -> AHAROU_ROOT + "/" + abs.removePrefix("$aicodePath/")
            abs == sharedPath -> SHARED_ROOT
            abs.startsWith("$sharedPath/") -> SHARED_ROOT + "/" + abs.removePrefix("$sharedPath/")
            abs == rootfsPath -> "/"
            abs.startsWith("$rootfsPath/") -> "/" + abs.removePrefix("$rootfsPath/")
            else -> mountedContainerPath(abs) ?: hostPath
        }
    }

    /**
     * 当前 profile 的额外挂载（[ContainerProfile.extraBindings]，格式 `本地源:容器目标`）解析为
     * (宿主源, 展开并去尾斜杠的容器目标)，按容器目标长度降序（最长前缀优先，避免嵌套挂载歧义）。
     * 文件工具不进 PRoot，若不在此映射，挂载路径会被兜底落到 rootfs 内部，
     * 与容器内 shell（proot `-b` bind mount）看到的不一致。
     */
    private fun extraMounts(): List<Pair<String, String>> =
        currentProfile.extraBindings.mapNotNull { binding ->
            val idx = binding.indexOf(':')
            val src = (if (idx >= 0) binding.substring(0, idx) else binding).trim()
            val dstRaw = if (idx >= 0) binding.substring(idx + 1) else binding
            val dst = pathHomeResolver.expandHome(dstRaw.trim()).trimEnd('/')
            if (src.isEmpty() || dst.isEmpty()) null else src to dst
        }.sortedByDescending { it.second.length }

    /** [p]（已展开的容器路径）落在某额外挂载目标下时，映射到宿主源真实文件；否则 null。 */
    private fun mountedHostFile(p: String): File? {
        for ((src, dst) in extraMounts()) {
            when {
                p == dst || p == "$dst/" -> return File(src)
                p.startsWith("$dst/") -> return File(src, p.removePrefix("$dst/"))
            }
        }
        return null
    }

    /** 宿主 [abs] 落在某额外挂载源下时，还原为容器目标路径；否则 null。 */
    private fun mountedContainerPath(abs: String): String? {
        for ((src, dst) in extraMounts()) {
            val srcAbs = File(src).absolutePath
            when {
                abs == srcAbs -> return dst
                abs.startsWith("$srcAbs/") -> return "$dst/" + abs.removePrefix("$srcAbs/")
            }
        }
        return null
    }
}
