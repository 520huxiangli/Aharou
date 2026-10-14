package com.aharou.feature.agent.domain.memory

import com.aharou.core.memory.AharouMemoryStore
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MemorySyncManager"

/**
 * 容器内的记忆目录：bind 自宿主 `filesDir/aharou-global/memory`
 * （挂载表见 LinuxContainerEngine 的 argv 组装）。
 */
private const val CONTAINER_MEMORY_DIR = "/root/.aharou/memory"

/** 一次同步的结果：成功与否，外加一句给用户看的话。 */
data class MemorySyncOutcome(val success: Boolean, val message: String)

/**
 * 把全局记忆同步到用户自己的 git 仓库（原地仓库：仓库根就是记忆目录）。
 *
 * 为什么仓库根是记忆目录本身、而不是它上层的 `~/.aharou`：上层放着 `git-credentials`
 * 与 `.gitconfig`，仓库根设在那一层，第一次 `git add -A` 就会把凭据推上去。
 *
 * 只同步全局记忆：项目记忆散在各工作区目录下，收进来需要一层拷贝与映射，
 * 价值（换机带走工程记忆）不足以抵掉「仓库里是副本、与原件两份」的代价。
 *
 * 远端凭据由容器里的 credential helper 提供，但那个 helper 默认只对 `$HOME/workspace/`
 * 下的仓库生效，所以记忆仓库的凭据作用域要在容器初始化脚本里单独加一条（见 env-common.sh）。
 */
@Singleton
class MemorySyncManager @Inject constructor(
    private val engine: CommandEngine,
    private val settings: MemorySyncSettings,
    private val memoryStore: AharouMemoryStore,
    private val executionModeHolder: ExecutionModeHolder,
) {

    /** 同步一次：初始化 → 配远端 → 提交 → 拉取合并 → 推送。 */
    suspend fun sync(): MemorySyncOutcome {
        val url = settings.repoUrl
        if (url.isBlank()) return MemorySyncOutcome(false, "还没有填记忆仓库地址")
        if (executionModeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            // 记忆目录在本机，容器也在本机；远程模式下命令会发到服务器，那里没有这份记忆。
            return MemorySyncOutcome(false, "记忆目录在本机，远程模式下同步不了，先切回本地模式")
        }
        val branch = settings.branch
        return try {
            writeGitIgnore()
            ensureRepo()
            ensureRemote(url)
            addAndCommit()
            // 首次推送时远端还没有这个分支，pull 必然失败——它只是「尽量跟上别的设备」的尽力而为。
            run("git pull --rebase origin ${quote(branch)}")
            val push = run("git push -u origin ${quote(branch)}")
            if (push.exitCode == 0) {
                settings.lastSyncAt = System.currentTimeMillis()
                settings.lastResult = "已同步"
                MemorySyncOutcome(true, "已同步到记忆仓库")
            } else {
                val reason = push.output.trim().takeLast(400).ifBlank { "git 退出码 ${push.exitCode}" }
                settings.lastResult = "推送失败：$reason"
                MemorySyncOutcome(false, "推送失败：$reason")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "记忆同步失败: ${e.message}", e)
            val reason = e.message.orEmpty().take(400).ifBlank { "同步失败" }
            settings.lastResult = reason
            MemorySyncOutcome(false, reason)
        }
    }

    /** 把本机统计与临时文件挡在仓库外：它们跟着设备走，进仓库只会互相覆盖。 */
    private fun writeGitIgnore() {
        val file = File(memoryStore.dir, GITIGNORE_NAME)
        val expected = ".memory-usage.json\n*.tmp\n"
        val current = runCatching { file.readText() }.getOrNull()
        if (current != expected) runCatching { file.writeText(expected) }
    }

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

    /** 命令自带 cd，不依赖引擎的路径映射（记忆目录不在工作区映射表里）。 */
    private suspend fun run(command: String): CommandResult =
        engine.runCommandSyncWithExit(
            "cd ${quote(CONTAINER_MEMORY_DIR)} && $command",
            projectPath = null,
            timeoutMs = TIMEOUT_MS,
        )

    /** 单引号包裹并转义内部单引号，保证仓库地址/分支名里的特殊字符不会被 shell 解释。 */
    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    private companion object {
        const val GITIGNORE_NAME = ".gitignore"

        /** 单条 git 命令的超时：推送要联网，给足时间但仍设上限，避免卡住界面。 */
        const val TIMEOUT_MS = 120_000L
    }
}
