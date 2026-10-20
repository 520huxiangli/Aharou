package com.aharou.feature.agent.domain.inbox

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.WorkspaceIdentity
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 跨工作区信箱：共享区 `~/shared/inbox/<工作区标识>.md`，一行一条消息。
 *
 * 不同工作区刻意隔离（会话、记忆互相看不见），信箱是它们之间唯一的「留话」通道：
 * 每轮开始组装系统提示词时，把本工作区文件里比已读位置更新的行汇总成一条提示注入。
 *
 * - 文件**懒创建**：只在第一次真往里写消息（由 AI 用 writeFile 完成）时才存在，读到不存在的文件就是空信箱，
 *   不挂到工作区创建流程上。
 * - 已读位置按工作区记在 `~/.aharou/inbox-state/<工作区标识>.txt`，内容就是已读行数；
 *   跟着 App 数据走，升级不丢，清 App 数据后归零 → 信箱里现存的消息会被再提示一次（不丢消息，宁可多提示一遍）。
 * - 无新消息时不写盘、不注入，零开销。
 * - 仅本地执行模式：远程 SSH 工作区的 `~/shared` 在对端服务器上，并不指向 App 这份共享目录，
 *   收发会是两张皮，所以远程模式直接停用（返回空、不提示地址）。
 */
@Singleton
class SharedInbox @Inject constructor(
    private val containerInstaller: ContainerInstaller,
    private val executionModeHolder: ExecutionModeHolder
) {
    /** AI 视角的信箱路径（容器内写法）；没有工作区或远程模式时返回 null。 */
    fun containerPath(workspacePath: String): String? {
        if (!enabled()) return null
        return WorkspaceIdentity.idFor(workspacePath)?.let { "$CONTAINER_INBOX_DIR/$it.md" }
    }

    /** 只读不推进已读位置。 */
    fun peek(workspacePath: String): List<String> = synchronized(lock) {
        if (!enabled()) return emptyList()
        unreadMessages(messages(workspacePath), readCount(workspacePath))
    }

    /** 取未读并把已读位置推进到文件末尾；无新消息时不写盘。 */
    fun consume(workspacePath: String): List<String> = synchronized(lock) {
        if (!enabled()) return emptyList()
        val all = messages(workspacePath)
        val read = readCount(workspacePath)
        val unread = unreadMessages(all, read)
        if (all.size != read) writeCount(workspacePath, all.size)
        unread
    }

    /** 信箱当前是否可用（远程 SSH 模式下共享区不在本机，收发是两张皮）。 */
    fun enabled(): Boolean =
        executionModeHolder.currentMode() != ExecutionMode.REMOTE_SSH

    /** 信箱正文的全部消息行；文件不存在视为空信箱（只读不建）。 */
    private fun messages(workspacePath: String): List<String> {
        val file = inboxFile(workspacePath) ?: return emptyList()
        if (!file.isFile) return emptyList()
        return try {
            splitMessages(file.readText())
        } catch (e: Exception) {
            FileLogger.w(TAG, "读取共享区信箱失败: ${e.message}", e)
            emptyList()
        }
    }

    private fun inboxFile(workspacePath: String): File? =
        WorkspaceIdentity.idFor(workspacePath)
            ?.let { File(File(containerInstaller.sharedDir, INBOX_DIR), "$it.md") }

    private fun stateFile(workspacePath: String): File? =
        WorkspaceIdentity.idFor(workspacePath)
            ?.let { File(File(containerInstaller.aharouDir, STATE_DIR), "$it.txt") }

    private fun readCount(workspacePath: String): Int {
        val file = stateFile(workspacePath) ?: return 0
        if (!file.isFile) return 0
        return runCatching { file.readText().trim().toInt() }.getOrDefault(0)
    }

    private fun writeCount(workspacePath: String, count: Int) {
        val file = stateFile(workspacePath) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(count.toString())
        }.onFailure { FileLogger.w(TAG, "写共享区信箱已读位置失败: ${it.message}", it) }
    }

    companion object {
        private const val TAG = "SharedInbox"
        private const val INBOX_DIR = "inbox"
        private const val STATE_DIR = "inbox-state"

        /** 信箱通知的发送方标识：通知卡片据此把「跨工作区消息」和「子代理消息」区分开。 */
        const val NOTIFICATION_SENDER_ID = "shared-inbox"

        /** 信箱目录的容器内路径，提示词与文档里都用这个写法。 */
        const val CONTAINER_INBOX_DIR = "~/shared/inbox"

        /** 一次最多注入多少条：积压过多时只给最近的，避免一条通知把上下文撑爆。 */
        const val MAX_INJECTED = 20

        /** 已读位置的读改写要串行：同一工作区可能有两个会话同时开轮。 */
        private val lock = Any()

        /** 把文件正文切成消息行：去掉首尾空白与空行。 */
        fun splitMessages(text: String): List<String> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        /**
         * 未读 = 已读位置之后的行。
         *
         * 已经读到的条数大于当前行数说明文件被重写过、旧位置失效，按「全部都是新的」处理——
         * 宁可多提示一遍，也不让新消息被旧位置吃掉。
         */
        fun unreadMessages(all: List<String>, readCount: Int): List<String> = when {
            readCount <= 0 -> all
            readCount > all.size -> all
            else -> all.drop(readCount)
        }

        /** 注入前截断：只保留最近 [max] 条。 */
        fun latest(unread: List<String>, max: Int = MAX_INJECTED): List<String> =
            if (max <= 0 || unread.size <= max) unread else unread.takeLast(max)
    }
}
