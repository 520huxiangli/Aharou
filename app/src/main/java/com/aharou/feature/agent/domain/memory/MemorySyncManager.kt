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
import kotlinx.coroutines.sync.Mutex
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemorySyncManager"

/** 容器内的工作树：bind 自宿主 `filesDir/aharou/memory-repo`（`files/aharou` → `/root/.aharou`）。 */
private const val CONTAINER_REPO_DIR = "/root/.aharou/memory-repo"

/**
 * 容器里的 git 凭据 helper。
 *
 * 容器初始化的 includeIf 只覆盖 `$HOME/workspace/` 与 `$HOME/.aharou/memory/`，本工作树不在其中，
 * 不在这里补一条仓库级 helper，push 就会以 `could not read Username for 'https://…'` 失败。
 */
private const val CREDENTIAL_HELPER = "/root/.aicode/git-credential-aicode"

/** 提交与 rebase 的署名：仓库是用户的，不用写进仓库配置。 */
private const val GIT_AUTHOR_NAME = "Aharou"
private const val GIT_AUTHOR_EMAIL = "aharou@local"

/** 仓库里全局记忆的子目录。 */
private const val GLOBAL_DIR = "global"

/** 仓库里项目记忆的子目录，下面按 `<项目名-短哈希>` 分。 */
private const val PROJECTS_DIR = "projects"

/** 远程工作区的项目记忆在仓库里的目录前缀。 */
private const val REMOTE_PREFIX = "remote-"

/** 冲突归档的子目录名，与 `MemorySource` 的归档目录同名（`archive/`）。 */
private const val ARCHIVE_DIR = "archive"

/** 冲突归档的前缀：与记忆自身的归档条目区分开，避免重名互相覆盖。 */
private const val REMOTE_ARCHIVE_PREFIX = "remote-"

/** 一轮 rebase 冲突最多处理几轮，异常情况下防止空转。 */
private const val MAX_REBASE_ROUNDS = 10

/** frontmatter 里已声明待确认：这些条目不重复追加冲突标记，否则每次同步都会叠一层日期。 */
private val PENDING_LINE = Regex("(?im)^pending:\\s*true\\s*\$")

/**
 * 一次同步的结果。
 *
 * @param success 是否成功。
 * @param message 给用户看的一句话（成功时含收集数、补回数、冲突数、疑似删除数）。
 * @param collected 本轮收进仓库的本地文件数（**新增与更新合并计数**）。
 * @param conflicts 本轮两侧都改过、已按「保留本机 + 归档远端 + 新条目标待确认」处理的条目数。
 * @param pendingDeletions 上次提交里存在、本次远端与本机都不存在的条目（仓库相对路径），
 *   疑似在其他设备被删除；**仅报告，不执行任何删除**，删不删由用户决定。
 */
data class MemorySyncOutcome(
    val success: Boolean,
    val message: String,
    val collected: Int = 0,
    val conflicts: Int = 0,
    val pendingDeletions: List<String> = emptyList(),
)

/**
 * 把记忆同步到用户自己的 git 仓库。
 *
 * 仓库是一个**收集桶**，不等于任何一处记忆目录：同步时把全局记忆与各工作区的项目记忆分别拷进
 * `global/` 与 `projects/<项目名-短哈希>/`，再提交推送；拉取后按「本机未改动才覆盖」补回本机
 * （本机真实删除的条目会同步成仓库里的删除，不会再被补回）。
 *
 * 为什么不沿用「仓库根就是记忆目录」：那只能是单一目录。项目记忆散在各工作区下，要一起走就得有汇合点。
 * 代价是仓库里存的是副本。合并语义以 **base = 上次提交** 为准（本轮 collect 之前的工作树内容，
 * 也就是上一轮 push 之后、本轮 pull 之前那一刻）：本机改、远端改由 git 三方判定；
 * 两侧都改且不同则**保留本机版本继续推、远端版本另存进该条目的 `archive/`**，
 * 并给双方都标上待确认，绝不静默覆盖本机改动、也不让远端改动无声消失。
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

    /**
     * 进程内互斥：UI（MemorySyncViewModel）与 WorkManager 的 Worker 同进程、共享同一个单例，
     * 两条路径会同时戳同一棵 git 工作树。拿不到锁立即返回，不排队——排队只会让后到的那次
     * 对着刚被改过的工作树再跑一遍，徒增一次空转。
     */
    private val syncMutex = Mutex()

    /** 同步一次：拿锁 → 收集 → 提交 → 拉取合并 → 推送 → 补回本机缺的。 */
    suspend fun sync(): MemorySyncOutcome {
        if (!syncMutex.tryLock()) return MemorySyncOutcome(false, "已有同步正在进行中")
        return try {
            syncLocked()
        } finally {
            // 异常路径也要放锁，否则一次崩溃就把后续所有同步永久挡在门外。
            syncMutex.unlock()
        }
    }

    private suspend fun syncLocked(): MemorySyncOutcome {
        val url = settings.repoUrl
        if (url.isBlank()) return finish(MemorySyncOutcome(false, "还没有填记忆仓库地址"))
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            // 记忆目录在本机，容器也在本机；远程模式下命令会发到服务器，那里没有这份记忆。
            return finish(MemorySyncOutcome(false, "记忆目录在本机，远程模式下同步不了，先切回本地模式"))
        }
        val branch = settings.branch
        return try {
            val repoDir = repoDir().apply { mkdirs() }
            // 上一次异常中断可能把工作树留在 rebase / merge 中间态；先收干净，
            // 否则 base 快照会掺进冲突标记、提交也会把冲突标记当内容写进仓库。
            abortInterruptedState(repoDir)
            migrateLegacyLayout(repoDir)
            writeGitIgnore(repoDir)
            // base = 本轮 collect 之前的仓库内容 = 上次提交（上一轮 push 之后、本轮 pull 之前的那一刻），
            // 用来判定「上次提交里存在、本次两边都没有」的疑似删除条目。
            val basePaths = repoPathsUnder(repoDir, GLOBAL_DIR) + repoPathsUnder(repoDir, PROJECTS_DIR)
            val localPaths = mutableSetOf<String>()
            val locallyModified = mutableSetOf<String>()
            val collected = collect(repoDir, localPaths, locallyModified)

            // 本机删掉的条目要同步成仓库里的删除。否则：collect 只收「本机存在」的文件，
            // 仓库里那份一直留着 → addAndCommit 把它当原样提交 → distribute 又照仓库把它写回本机，
            // 表现为「删了记忆、同步一次又回来」（2026-10-18 实测 mtime 全是同一秒）。
            // basePaths 里没有的不算删除，避免首次同步或换设备时误删。
            val deletedLocally = basePaths - localPaths
            if (deletedLocally.isNotEmpty()) {
                deletedLocally.forEach { rel -> runCatching { File(repoDir, rel).delete() } }
                FileLogger.i(TAG, "本机删除同步到仓库：${deletedLocally.size} 个")
            }

            ensureRepo()
            ensureRemote(url)
            addAndCommit()

            // 远端分支在不在、有没有新提交，先问一次，别拿「拉不到」当「没有」。
            val ls = run("git ls-remote --heads origin ${quote("refs/heads/$branch")}")
            if (ls.exitCode != 0) {
                val reason = ls.output.trim().takeLast(400).ifBlank { "git 退出码 ${ls.exitCode}" }
                return finish(MemorySyncOutcome(false, "无法访问远端仓库：$reason", collected))
            }
            val remoteRef = ls.output.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.substringBefore('\t')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

            var remotePaths: Set<String> = emptySet()
            var conflicts = 0
            if (remoteRef != null) {
                val fetch = run("git fetch --quiet origin ${quote(branch)}")
                if (fetch.exitCode != 0) {
                    val reason = fetch.output.trim().takeLast(400).ifBlank { "git 退出码 ${fetch.exitCode}" }
                    return finish(MemorySyncOutcome(false, "拉取远端失败：$reason", collected))
                }
                remotePaths = readRemotePaths(remoteRef)
                // 把本地提交重放到远端最新提交之上：rebase 的三方基正是「上次与远端共享的提交」，
                // 即需求里的 base。本机改 / 远端改 / 两侧都改由 git 判定，两侧都改则下面逐条保留双方。
                val rebase = run(
                    "git -c user.name=$GIT_AUTHOR_NAME -c user.email=$GIT_AUTHOR_EMAIL rebase ${quote(remoteRef)}"
                )
                if (rebase.exitCode != 0) {
                    if (conflictedPaths().isEmpty() && !rebaseInProgress(repoDir)) {
                        // 不是冲突能解释的失败（无共同祖先、工作树不干净、网络等）：干净中止并回报。
                        val reason = rebase.output.trim().takeLast(400).ifBlank { "git 退出码 ${rebase.exitCode}" }
                        return finish(MemorySyncOutcome(false, "拉取失败：$reason", collected))
                    }
                    val resolution = resolveRebaseConflicts(repoDir, locallyModified)
                    conflicts = resolution.conflicts
                    val failure = resolution.failure
                    if (failure != null) {
                        return finish(MemorySyncOutcome(false, failure, collected, conflicts))
                    }
                }
            }

            // 冲突归档与 rebase 结束后的收尾是在上一步之后写进工作树的，单独补一次提交。
            addAndCommit()
            val restored = distribute(repoDir, locallyModified)
            val push = run("git push -u origin ${quote(branch)}")
            if (push.exitCode != 0) {
                val reason = push.output.trim().takeLast(400).ifBlank { "git 退出码 ${push.exitCode}" }
                return finish(MemorySyncOutcome(false, "推送失败：$reason", collected, conflicts))
            }
            // 删除同步：本机真实删除的条目已在 collect 之后从仓库移除（见 deletedLocally）；
            // 这里只报告「base 有、本机与远端都没了」的条目，那是别的设备的删除，本机不跟随。
            val pending = if (remoteRef != null) pendingDeletions(basePaths, localPaths, remotePaths) else emptyList()
            val message = successMessage(collected, restored, conflicts, pending.size)
            finish(MemorySyncOutcome(true, message, collected, conflicts, pending))
        } catch (e: Exception) {
            FileLogger.w(TAG, "记忆同步失败: ${e.message}", e)
            val reason = e.message.orEmpty().take(400).ifBlank { "同步失败" }
            finish(MemorySyncOutcome(false, reason))
        }
    }

    /** 统一收尾：把结果落到设置里（供界面回显），成功才更新 lastSyncAt。 */
    private fun finish(outcome: MemorySyncOutcome): MemorySyncOutcome {
        settings.lastResult = outcome.message
        settings.lastPendingDeletions = outcome.pendingDeletions.joinToString("\n")
        if (outcome.success) settings.lastSyncAt = System.currentTimeMillis()
        return outcome
    }

    /** 给用户看的那句话：把收集数、补回数、冲突数、疑似删除数都摊开。 */
    private fun successMessage(collected: Int, restored: Int, conflicts: Int, pendingDeletions: Int): String {
        val parts = mutableListOf("收上 $collected 个文件")
        if (restored > 0) parts += "补回本机 $restored 个文件"
        if (conflicts > 0) parts += "冲突 $conflicts 条（双方已标待确认，远端版本存进 archive/）"
        if (pendingDeletions > 0) parts += "疑似被其他设备删除 $pendingDeletions 条"
        return "已同步到记忆仓库（${parts.joinToString("，")}）"
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
     * 收集：把各来源的记忆拷进仓库工作树，返回本轮实际写盘（新增或内容更新）的文件数。
     *
     * 只覆盖内容不同的同名文件、不清理仓库里已有的内容——别的设备推上来的项目目录得留着。
     * 同时回填两组仓库相对路径：所有本地条目的 [localPaths]，以及本机改动过的 [locallyModified]
     * （后者决定分发时哪些本地文件绝不被仓库版本覆盖）。
     */
    private fun collect(repoDir: File, localPaths: MutableSet<String>, locallyModified: MutableSet<String>): Int {
        var copied = 0
        localRoots().forEach { root ->
            walkFiles(root.dir) { src, relWithin ->
                val rel = "${root.repoPrefix}/$relWithin"
                localPaths += rel
                val target = File(repoDir, rel)
                // 跳过判据只看内容：长度相同不代表内容相同，mtime 跨设备、拷来拷去都不可信。
                if (sameContent(target, src)) return@walkFiles
                runCatching {
                    target.parentFile?.mkdirs()
                    src.copyTo(target, overwrite = true)
                    locallyModified += rel
                    copied++
                }.onFailure { FileLogger.w(TAG, "拷贝失败: ${src.absolutePath}", it) }
            }
        }
        FileLogger.i(TAG, "收集完成：$copied 个文件")
        return copied
    }

    /**
     * 分发：把仓库里的最新内容带回本机，返回实际写盘（补回或更新）的条数。
     *
     * 只动**本机没改过**的条目（[locallyModified] 之外）：本机改过的一律保留，冲突条目也在其中。
     * 于是「远端改了、本机没改」会更新本机；「双方都改」保持本机不变（远端版本已在 archive/）。
     */
    private fun distribute(repoDir: File, locallyModified: Set<String>): Int {
        var restored = 0
        localRoots().forEach { root ->
            val repoRoot = File(repoDir, root.repoPrefix)
            walkFiles(repoRoot) { repoFile, relWithin ->
                val rel = "${root.repoPrefix}/$relWithin"
                if (rel in locallyModified) return@walkFiles
                val target = File(root.dir, relWithin)
                if (sameContent(target, repoFile)) return@walkFiles
                runCatching {
                    target.parentFile?.mkdirs()
                    repoFile.copyTo(target, overwrite = true)
                    restored++
                }.onFailure { FileLogger.w(TAG, "补回失败: ${target.absolutePath}", it) }
            }
        }
        return restored
    }

    /**
     * 处理 rebase 冲突：**保留本机版本、把远端版本另存进该条目的 `archive/`**，直到 rebase 结束。
     *
     * rebase 冲突里 `--ours` 是被 rebase 到的上游（远端）、`--theirs` 是正在重放的本地提交，
     * 所以保留本机用 `--theirs`，远端版本从索引 stage 2（`<rev>:2:<path>`）取。
     * 两侧都标待确认：只归档不标，远端改动就会被静默埋进 archive/，用户永远看不到冲突发生过。
     * 全部冲突处理完仍收不了尾则 `rebase --abort` 干净中止，绝不把工作树留在中间态。
     */
    private suspend fun resolveRebaseConflicts(
        repoDir: File,
        locallyModified: MutableSet<String>,
    ): RebaseResolution {
        var conflicts = 0
        var rounds = 0
        while (rebaseInProgress(repoDir) && rounds++ < MAX_REBASE_ROUNDS) {
            conflictedPaths().forEach { path ->
                val archived = archiveRemoteVersion(repoDir, path)
                run("git checkout --theirs -- ${quote(path)}")
                // 必须在 git add 之前打标：待确认标记要跟着这次 rebase 提交一起进仓库。
                markConflictPending(repoDir, path, archived)
                run("git add -- ${quote(path)}")
                locallyModified += path
                conflicts++
            }
            run(
                "GIT_EDITOR=true git -c user.name=$GIT_AUTHOR_NAME -c user.email=$GIT_AUTHOR_EMAIL rebase --continue"
            )
        }
        if (rebaseInProgress(repoDir)) {
            run("git rebase --abort")
            return RebaseResolution(conflicts, "拉取冲突无法自动处理，已中止（本机改动未动，可稍后重试）")
        }
        return RebaseResolution(conflicts, null)
    }

    /**
     * 把冲突条目的远端版本写进同目录的 `archive/remote-<名>`（`remote-` 前缀防重名）。
     *
     * @return 归档文件的仓库相对路径；取不到远端版本或写盘失败时 null。
     */
    private suspend fun archiveRemoteVersion(repoDir: File, path: String): String? {
        val remote = run("git show ${quote(":2:$path")}")
        if (remote.exitCode != 0) return null
        val dir = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val archiveRel = if (dir.isEmpty()) {
            "$ARCHIVE_DIR/$REMOTE_ARCHIVE_PREFIX$name"
        } else {
            "$dir/$ARCHIVE_DIR/$REMOTE_ARCHIVE_PREFIX$name"
        }
        return runCatching {
            val target = File(repoDir, archiveRel)
            target.parentFile?.mkdirs()
            target.writeText(remote.output)
            archiveRel
        }.onFailure { FileLogger.w(TAG, "远端版本归档失败: $archiveRel", it) }.getOrNull()
    }

    /** 一条冲突的双方都标待确认；本机那份在仓库工作树与本机记忆目录里各标一次。 */
    private fun markConflictPending(repoDir: File, path: String, archived: String?) {
        markPending(File(repoDir, path), "远端版本")
        // 仓库副本会被下次 collect 用本机文件覆盖，所以本机原件也得标，否则标记下一轮就丢了。
        localFileFor(path)?.let { markPending(it, "远端版本") }
        archived?.let { markPending(File(repoDir, it), "本机版本") }
    }

    /**
     * 给一条记忆打待确认：frontmatter 补 `pending: true`，description 追加冲突说明。
     *
     * 已带 `pending: true` 的条目直接跳过，避免反复同步把同一件事标成多条冲突。
     */
    private fun markPending(file: File, counterpart: String) {
        val text = runCatching { file.readText() }.getOrNull() ?: return
        val normalized = text.replace("\r\n", "\n")
        val headEnd = if (normalized.startsWith("---\n")) normalized.indexOf("\n---", startIndex = 4) else -1
        val head = if (headEnd >= 0) normalized.substring(4, headEnd) else ""
        if (PENDING_LINE.containsMatchIn(head)) return
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val marker = "[待确认 $date，与${counterpart}冲突]"
        val lines = if (head.isEmpty()) mutableListOf<String>() else head.split("\n").toMutableList()
        val descIndex = lines.indexOfFirst { it.trimStart().startsWith("description:") }
        if (descIndex >= 0) lines[descIndex] = appendMarkerToLine(lines[descIndex], marker)
        lines.add("pending: true")
        val body = if (headEnd >= 0) normalized.substring(headEnd) else "\n---\n$normalized"
        runCatching { file.writeText("---\n${lines.joinToString("\n")}$body") }
            .onFailure { FileLogger.w(TAG, "待确认标记写入失败: ${file.absolutePath}", it) }
    }

    /** 把标记塞进 description 值：有引号就塞在收尾引号前，没有就整体包成双引号标量。 */
    private fun appendMarkerToLine(line: String, marker: String): String {
        val colon = line.indexOf(':')
        if (colon < 0) return line
        val prefix = line.substring(0, colon + 1)
        val raw = line.substring(colon + 1).trim()
        val value = when {
            raw.length >= 2 && raw.first() == '"' && raw.last() == '"' -> raw.dropLast(1) + " $marker\""
            raw.length >= 2 && raw.first() == '\'' && raw.last() == '\'' -> raw.dropLast(1) + " $marker'"
            raw.isEmpty() -> "\"$marker\""
            else -> "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + " $marker\""
        }
        return "$prefix $value"
    }

    /** 仓库相对路径对应的本机记忆文件；项目在本机不可用（没有对应目录）时返回 null。 */
    private fun localFileFor(repoPath: String): File? {
        localRoots().forEach { root ->
            val prefix = "${root.repoPrefix}/"
            if (repoPath.startsWith(prefix)) return File(root.dir, repoPath.removePrefix(prefix))
        }
        return null
    }

    /** 上次提交里存在、本次远端与本机都不存在的条目：疑似在其他设备被删，仅报告不删除。 */
    private fun pendingDeletions(base: Set<String>, local: Set<String>, remote: Set<String>): List<String> =
        base.filter { it !in local && it !in remote }.sorted()

    /** 上一次异常中断留下的 rebase / merge 中间态，开工前先收干净。 */
    private suspend fun abortInterruptedState(repoDir: File) {
        if (!File(repoDir, ".git").isDirectory) return
        if (rebaseInProgress(repoDir)) run("git rebase --abort")
        if (File(File(repoDir, ".git"), "MERGE_HEAD").isFile) run("git merge --abort")
    }

    private fun rebaseInProgress(repoDir: File): Boolean {
        val gitDir = File(repoDir, ".git")
        return File(gitDir, "rebase-merge").exists() || File(gitDir, "rebase-apply").exists()
    }

    private suspend fun conflictedPaths(): List<String> {
        val out = run("git -c core.quotePath=false diff --name-only --diff-filter=U")
        if (out.exitCode != 0) return emptyList()
        return out.output.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    /** 远端分支上参与同步的条目（仓库相对路径），供疑似删除判定。 */
    private suspend fun readRemotePaths(ref: String): Set<String> {
        val out = run("git -c core.quotePath=false ls-tree -r --name-only ${quote(ref)}")
        if (out.exitCode != 0) return emptySet()
        return out.output.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("$GLOBAL_DIR/") || it.startsWith("$PROJECTS_DIR/") }
            .toSet()
    }

    /** 参与同步的本地根：仓库前缀 ↔ 本机落盘目录。global 一处、每个可用工作区一处、每个远程 key 一处。 */
    private data class LocalRoot(val repoPrefix: String, val dir: File)

    private fun localRoots(): List<LocalRoot> {
        val roots = mutableListOf(LocalRoot(GLOBAL_DIR, memoryStore.dir))
        workspaceRepository.workspaces.value
            .filter { it.available }
            .forEach { ws ->
                roots += LocalRoot("$PROJECTS_DIR/${projectKey(ws)}", File(File(ws.path, ".aharou"), "memory"))
            }
        remoteProjectsRoot()?.listFiles()?.filter { it.isDirectory }?.forEach { keyDir ->
            roots += LocalRoot("$PROJECTS_DIR/$REMOTE_PREFIX${keyDir.name}", keyDir)
        }
        return roots
    }

    private fun repoPathsUnder(repoDir: File, prefix: String): Set<String> {
        val out = mutableSetOf<String>()
        walkFiles(File(repoDir, prefix)) { _, relWithin -> out += "$prefix/$relWithin" }
        return out
    }

    /** 递归遍历目录下的文件；符号链接一律跳过（详见 [walkInto]）。 */
    private fun walkFiles(root: File, onFile: (File, String) -> Unit) = walkInto(root, "", onFile)

    private fun walkInto(dir: File, prefix: String, onFile: (File, String) -> Unit) {
        dir.listFiles()?.forEach { child ->
            // 跳过符号链接（判断源侧）：循环链接会无限递归，指向目录外的链接会把不该同步的文件收进仓库。
            if (isIgnored(child.name) || isSymlink(child)) return@forEach
            val rel = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
            if (child.isDirectory) walkInto(child, rel, onFile) else if (child.isFile) onFile(child, rel)
        }
    }

    private fun isSymlink(file: File): Boolean =
        runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)

    /** 内容比对：长度或内容不同即视为不同（不用 mtime——它跨设备、拷来拷去都不可信）。 */
    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile) return false
        if (a.length() != b.length()) return false
        return runCatching { a.readBytes().contentEquals(b.readBytes()) }.getOrDefault(false)
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
        // 仓库级配置而非全局：只让这一个仓库拿到凭据，不扩散到容器里的其它目录。
        run("git config --local credential.helper ${quote(CREDENTIAL_HELPER)}")
    }

    private suspend fun addAndCommit() {
        val add = run("git add -A")
        if (add.exitCode != 0) throw IllegalStateException("暂存失败：${add.output.trim().take(200)}")
        // 没有改动时 commit 会非零退出，这不是错误，故不判退出码。
        // 用 -c 传署名而不是写进仓库配置：这个仓库是用户的，别留下我们的痕迹。
        run("git -c user.name=$GIT_AUTHOR_NAME -c user.email=$GIT_AUTHOR_EMAIL commit -m ${quote("memory: ${timestamp()}")}")
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

    /** 一次 rebase 冲突处理的结果：处理了几条，以及是否已干净中止。 */
    private data class RebaseResolution(val conflicts: Int, val failure: String?)

    private companion object {
        /** 单条 git 命令的超时：推送要联网，给足时间但仍设上限，避免卡住界面。 */
        const val TIMEOUT_MS = 120_000L
    }
}
