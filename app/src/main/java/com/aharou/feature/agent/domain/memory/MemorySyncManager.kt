package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.model.Workspace
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemorySyncManager"

/** 容器内的工作树：bind 自宿主 `filesDir/aharou/memory-repo`（`files/aharou` → `/root/.aharou`）。 */
private const val CONTAINER_REPO_DIR = "/root/.aharou/memory-repo"

/** 仓库里全局记忆的子目录。 */
private const val GLOBAL_DIR = "global"

/** 仓库里项目记忆的子目录，下面按 `<项目名-短哈希>` 分。 */
private const val PROJECTS_DIR = "projects"

/** 远程工作区的项目记忆在仓库里的目录前缀。 */
private const val REMOTE_PREFIX = "remote-"

/** 一次同步的结果：成功与否，外加一句给用户看的话。 */
data class MemorySyncOutcome(val success: Boolean, val message: String)

/**
 * 把记忆同步到用户自己的 git 仓库。
 *
 * 仓库是一个**收集桶**，不等于任何一处记忆目录：同步时把全局记忆与各工作区的项目记忆分别拷进
 * `global/` 与 `projects/<项目名-短哈希>/`，再提交推送；拉取后只把本机缺的文件补回来。
 *
 * 为什么不沿用「仓库根就是记忆目录」：那只能是单一目录。项目记忆散在各工作区下，要一起走就得有汇合点。
 * 代价是仓库里存的是副本，所以这里**只做单向增补**——本地有的一律覆盖仓库、仓库里别的东西不动；
 * 拉取只补本地没有的，绝不拿仓库的版本去盖本机（多数情况下本地那份才新，盖过去等于抹掉本机改动）。
 *
 * 远端凭据由容器里的 credential helper 提供，作用域靠容器初始化的 includeIf（见 env-common.sh）。
 */
@Singleton
class MemorySyncManager @Inject constructor(
    private val engine: CommandEngine,
    private val settings: MemorySyncSettings,
    private val memoryStore: AharouMemoryStore,
    private val workspaceRepository: WorkspaceRepository,
    private val containerInstaller: ContainerInstaller,
    private val executionModeHolder: ExecutionModeHolder,
) {

    /** 同步一次：收集 → 初始化仓库 → 配远端 → 提交 → 拉取合并 → 推送 → 补回本机缺的。 */
    suspend fun sync(): MemorySyncOutcome {
        val url = settings.repoUrl
        if (url.isBlank()) return MemorySyncOutcome(false, "还没有填记忆仓库地址")
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            // 记忆目录在本机，容器也在本机；远程模式下命令会发到服务器，那里没有这份记忆。
            return MemorySyncOutcome(false, "记忆目录在本机，远程模式下同步不了，先切回本地模式")
        }
        val branch = settings.branch
        return try {
            val repoDir = repoDir().apply { mkdirs() }
            migrateLegacyLayout(repoDir)
            writeGitIgnore(repoDir)
            val collected = collect(repoDir)
            ensureRepo()
            ensureRemote(url)
            addAndCommit()
            // 首次推送时远端还没有这个分支，pull 必然失败——它只是「尽量跟上别的设备」的尽力而为。
            run("git pull --rebase origin ${quote(branch)}")
            val push = run("git push -u origin ${quote(branch)}")
            if (push.exitCode != 0) {
                val reason = push.output.trim().takeLast(400).ifBlank { "git 退出码 ${push.exitCode}" }
                settings.lastResult = "推送失败：$reason"
                return MemorySyncOutcome(false, "推送失败：$reason")
            }
            val restored = distribute(repoDir)
            settings.lastSyncAt = System.currentTimeMillis()
            settings.lastResult = "已同步"
            val tail = if (restored > 0) "，补回本机 $restored 个文件" else ""
            MemorySyncOutcome(true, "已同步到记忆仓库（收上 $collected 个文件$tail）")
        } catch (e: Exception) {
            FileLogger.w(TAG, "记忆同步失败: ${e.message}", e)
            val reason = e.message.orEmpty().take(400).ifBlank { "同步失败" }
            settings.lastResult = reason
            MemorySyncOutcome(false, reason)
        }
    }

    /**
     * A 时代的布局是「仓库根就是全局记忆目录」（散件摊在根下）。改成收集桶后，
     * 把根下不属于 `global/` / `projects/` 的东西一次性归进 `global/`，免得它们永远赖在根上。
     */
    private fun migrateLegacyLayout(repoDir: File) {
        val keep = setOf(GLOBAL_DIR, PROJECTS_DIR, ".git", ".gitignore")
        val globalDir = File(repoDir, GLOBAL_DIR).apply { mkdirs() }
        repoDir.listFiles()?.filter { it.name !in keep }?.forEach { item ->
            val target = File(globalDir, item.name)
            if (!target.exists()) runCatching { item.renameTo(target) }
        }
    }

    /** 把本机统计与临时文件挡在仓库外：它们跟着设备走，进仓库只会互相覆盖。 */
    private fun writeGitIgnore(repoDir: File) {
        val file = File(repoDir, ".gitignore")
        val expected = ".memory-usage.json\n*.tmp\n"
        if (runCatching { file.readText() }.getOrNull() != expected) {
            runCatching { file.writeText(expected) }
        }
    }

    /**
     * 收集：把各来源的记忆拷进仓库工作树，返回拷进去的文件数。
     *
     * 只覆盖同名文件、不清理仓库里已有的内容——别的设备推上来的项目目录得留着。
     */
    private fun collect(repoDir: File): Int {
        var copied = copyTree(memoryStore.dir, File(repoDir, GLOBAL_DIR))
        workspaceRepository.workspaces.value
            .filter { it.available }
            .forEach { ws ->
                val src = File(File(ws.path, ".aharou"), "memory")
                if (src.isDirectory) {
                    copied += copyTree(src, File(File(repoDir, PROJECTS_DIR), projectKey(ws)))
                }
            }
        // 远程工作区的项目记忆落在私有目录里，本来就按 key 分目录
        remoteProjectsRoot()?.listFiles()?.filter { it.isDirectory }?.forEach { keyDir ->
            copied += copyTree(keyDir, File(File(repoDir, PROJECTS_DIR), "$REMOTE_PREFIX${keyDir.name}"))
        }
        FileLogger.i(TAG, "收集完成：$copied 个文件")
        return copied
    }

    /**
     * 分发：把仓库里有、本机没有的文件补回去，返回补回的条数。
     *
     * 只增不改：本机已有同名文件一律不动——多数情况下本地那份才是最新的，
     * 拿仓库的版本去盖它等于把本机改动抹掉。
     */
    private fun distribute(repoDir: File): Int {
        var restored = copyMissing(File(repoDir, GLOBAL_DIR), memoryStore.dir)
        val projects = File(repoDir, PROJECTS_DIR)
        val workspaces = workspaceRepository.workspaces.value
        projects.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val name = dir.name
            val target = if (name.startsWith(REMOTE_PREFIX)) {
                remoteProjectsRoot()?.let { File(it, name.removePrefix(REMOTE_PREFIX)) }
            } else {
                workspaces.firstOrNull { projectKey(it) == name }
                    ?.let { File(File(it.path, ".aharou"), "memory") }
            }
            if (target != null) restored += copyMissing(dir, target)
        }
        return restored
    }

    /** 递归拷贝 [src] 到 [dst]（同名覆盖，内容相同则跳过），返回实际写入的文件数。 */
    private fun copyTree(src: File, dst: File): Int {
        val children = src.listFiles() ?: return 0
        var copied = 0
        children.forEach { child ->
            if (isIgnored(child.name)) return@forEach
            val target = File(dst, child.name)
            if (child.isDirectory) {
                copied += copyTree(child, target)
            } else if (child.isFile) {
                runCatching {
                    if (target.isFile && target.length() == child.length() &&
                        target.lastModified() >= child.lastModified()
                    ) {
                        return@runCatching
                    }
                    target.parentFile?.mkdirs()
                    child.copyTo(target, overwrite = true)
                    copied++
                }.onFailure { FileLogger.w(TAG, "拷贝失败: ${child.absolutePath}", it) }
            }
        }
        return copied
    }

    /** 递归补回 [src] 里 [dst] 缺失的文件（同名一律不碰），返回补回的条数。 */
    private fun copyMissing(src: File, dst: File): Int {
        val children = src.listFiles() ?: return 0
        var restored = 0
        children.forEach { child ->
            if (isIgnored(child.name)) return@forEach
            val target = File(dst, child.name)
            if (child.isDirectory) {
                restored += copyMissing(child, target)
            } else if (child.isFile && !target.isFile) {
                runCatching {
                    target.parentFile?.mkdirs()
                    child.copyTo(target, overwrite = false)
                    restored++
                }.onFailure { FileLogger.w(TAG, "补回失败: ${target.absolutePath}", it) }
            }
        }
        return restored
    }

    /** 不跟着记忆走的东西：仓库自身的元数据、设备本地统计、临时文件。 */
    private fun isIgnored(name: String): Boolean =
        name == ".git" || name == ".gitignore" || name == ".memory-usage.json" || name.endsWith(".tmp")

    private suspend fun ensureRepo() {
        if (run("git rev-parse --is-inside-work-tree").exitCode == 0) return
        val init = run("git init -b main")
        if (init.exitCode != 0) {
            throw IllegalStateException("初始化仓库失败：${init.output.trim().take(200)}")
        }
    }

    private suspend fun ensureRemote(url: String) {
        val current = run("git remote get-url origin")
        if (current.exitCode == 0) {
            if (current.output.trim() != url.trim()) run("git remote set-url origin ${quote(url)}")
        } else {
            run("git remote add origin ${quote(url)}")
        }
    }

    private suspend fun addAndCommit() {
        val add = run("git add -A")
        if (add.exitCode != 0) throw IllegalStateException("暂存失败：${add.output.trim().take(200)}")
        // 没有改动时 commit 会非零退出，这不是错误，故不判退出码。
        // 用 -c 传署名而不是写进仓库配置：这个仓库是用户的，别留下我们的痕迹。
        run("git -c user.name=Aharou -c user.email=aharou@local commit -m ${quote("memory: ${timestamp()}")}")
    }

    /** 仓库工作树（宿主路径）。 */
    private fun repoDir(): File = File(containerInstaller.aharouDir, "memory-repo")

    /** 远程工作区的项目记忆根：`<aharou>/memory/projects`。 */
    private fun remoteProjectsRoot(): File? =
        File(File(containerInstaller.aharouDir, "memory"), "projects").takeIf { it.isDirectory }

    /**
     * 项目目录名：`<工作区名>-<项目身份短哈希>`。
     *
     * 身份优先取该项目自己的 git remote 地址，没 git 的工作区退回用工作区名。
     * **不能用工作区路径**：路径是「这个项目在这台设备上放哪儿」，换台设备就变了，
     * 同一个项目会被当成两个目录，跨设备同步回来的项目记忆也就归不了位。
     */
    private fun projectKey(ws: Workspace): String {
        val identity = gitRemoteOf(ws.path) ?: ws.name
        return "${sanitize(ws.name).take(40)}-${shortHash(identity)}"
    }

    /** 直接读 `.git/config` 的 remote 地址作为项目身份；没有 git 或没配 remote 时返回 null。 */
    private fun gitRemoteOf(workspacePath: String): String? {
        val config = File(File(workspacePath, ".git"), "config")
        if (!config.isFile) return null
        return runCatching {
            config.readLines()
                .map { it.trim() }
                .firstOrNull { it.startsWith("url = ") }
                ?.removePrefix("url = ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    /** 目录名安全化：去掉路径分隔符等不进文件名的字符。 */
    private fun sanitize(raw: String): String =
        raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "project" }

    private fun shortHash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(8)

    /** 命令自带 cd，不依赖引擎的路径映射（仓库目录不在工作区映射表里）。 */
    private suspend fun run(command: String): CommandResult =
        engine.runCommandSyncWithExit(
            "cd ${quote(CONTAINER_REPO_DIR)} && $command",
            projectPath = null,
            timeoutMs = TIMEOUT_MS,
        )

    /** 单引号包裹并转义内部单引号，保证仓库地址/分支名里的特殊字符不会被 shell 解释。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private companion object {
        /** 单条 git 命令的超时：推送要联网，给足时间但仍设上限，避免卡住界面。 */
        const val TIMEOUT_MS = 120_000L
    }
}
