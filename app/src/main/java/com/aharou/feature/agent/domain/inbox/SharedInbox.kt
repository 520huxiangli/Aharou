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
 * 不同工作区刻意隔离（会话、记忆互相看不见），信箱是它们之间唯一的「留话」通道。
 *
 * - 文件**懒创建**：只在第一次真往里写消息（由 AI 用 writeFile 完成）时才存在，读到不存在的文件就是空信箱，
 *   不挂到工作区创建流程上。
 * - **文件即队列**：消息交给会话之后由 [dropDelivered] 从文件里删掉，文件里只留还没送达的消息。
 *   不另存「已读位置」——那份行号指针一旦和文件对不上（投递半路中断、文件被重写、多会话并发），
 *   消息就会被永久标成已读而没人看见。
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

    /** 信箱里全部未送达的消息行；文件不存在视为空信箱（只读不建）。 */
    fun peek(workspacePath: String): List<String> = synchronized(lock) {
        if (!enabled()) return emptyList()
        messages(workspacePath)
    }

    /**
     * 把已经交给会话的前 [count] 行从文件里删掉。
     *
     * [count] 传 peek 时拿到的条数：期间又追加进来的新行不在删除范围内，会留在文件里等下一次投递。
     * 删空就连文件一起删掉，回到「懒创建」状态。
     *
     * 调用方必须在**投递成功之后**才调它：反过来的话，投递路上任何一处岔子都会让消息凭空消失。
     */
    fun dropDelivered(workspacePath: String, count: Int) = synchronized(lock) {
        if (count <= 0 || !enabled()) return@synchronized
        val file = inboxFile(workspacePath) ?: return@synchronized
        if (!file.isFile) return@synchronized
        val all = messages(workspacePath)
        if (all.isEmpty()) return@synchronized
        val rest = all.drop(count.coerceAtMost(all.size))
        runCatching {
            if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("\n") + "\n")
        }.onFailure { FileLogger.w(TAG, "清理已投递信箱消息失败: ${it.message}", it) }
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

    companion object {
        private const val TAG = "SharedInbox"
        private const val INBOX_DIR = "inbox"

        /** 信箱通知的发送方标识：通知卡片据此把「跨工作区消息」和「子代理消息」区分开。 */
        const val NOTIFICATION_SENDER_ID = "shared-inbox"

        /** 信箱目录的容器内路径，提示词与文档里都用这个写法。 */
        const val CONTAINER_INBOX_DIR = "~/shared/inbox"

        /** 一次最多注入多少条：积压过多时只给最近的，避免一条通知把上下文撑爆。 */
        const val MAX_INJECTED = 20

        /** 读写要串行：同一工作区可能有两个会话同时开轮。 */
        private val lock = Any()

        /** 写入路径是否落在共享区信箱目录：写入工具据此判断要不要通知投递。 */
        fun isInboxPath(path: String): Boolean {
            val normalized = path.replace('\\', '/').trim()
            return normalized.contains("shared/$INBOX_DIR/") && normalized.endsWith(".md")
        }

        /** 把文件正文切成消息行：去掉首尾空白与空行。 */
        fun splitMessages(text: String): List<String> =
            text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        /**
         * 按「已读行数」切未读。
         *
         * 信箱改成投递即清空之后运行时不再走这条路径，保留它是给 `SharedInboxTest` 里的旧用例恒温；
         * 新代码请直接用 [peek]（返回的就已经是未送达的全部消息）。
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
