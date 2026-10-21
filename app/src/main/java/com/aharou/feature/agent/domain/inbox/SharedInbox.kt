package com.aharou.feature.agent.domain.inbox

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.domain.WorkspaceIdentity
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
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
 * - **投递留痕**：删行的同时往 `~/shared/inbox/delivered.log` 追一行回执（时间、目标工作区、投递方式、
 *   收件会话、原文摘要）。发送方由此能查「到了没、什么时候被谁取走」，而队列语义一点没变。
 *   回执是旁证：写日志失败只记 FileLogger，绝不影响投递。
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
     * 把已经交给会话的前 [count] 行从文件里删掉，并给这批消息写一条投递回执。
     *
     * [count] 传 peek 时拿到的条数：期间又追加进来的新行不在删除范围内，会留在文件里等下一次投递。
     * 删空就连文件一起删掉，回到「懒创建」状态。
     *
     * [channel] / [sessionId] 只用于回执：前者是投递方式（提示词注入 / 通知投递），后者是收件会话，
     * 让发送方能查到「被谁取走」。
     *
     * 调用方必须在**投递成功之后**才调它：反过来的话，投递路上任何一处岔子都会让消息凭空消失。
     */
    fun dropDelivered(
        workspacePath: String,
        count: Int,
        channel: String = DELIVERY_CHANNEL_UNSPECIFIED,
        sessionId: String? = null
    ) = synchronized(lock) {
        if (count <= 0 || !enabled()) return@synchronized
        val file = inboxFile(workspacePath) ?: return@synchronized
        if (!file.isFile) return@synchronized
        val all = messages(workspacePath)
        if (all.isEmpty()) return@synchronized
        val taken = count.coerceAtMost(all.size)
        val rest = all.drop(taken)
        runCatching {
            if (rest.isEmpty()) file.delete() else file.writeText(rest.joinToString("\n") + "\n")
        }.onFailure { FileLogger.w(TAG, "清理已投递信箱消息失败: ${it.message}", it) }
        recordDelivered(workspacePath, all.take(taken), channel, sessionId)
    }

    /**
     * 追加投递回执到 `~/shared/inbox/delivered.log`：一行一条消息，含时间、目标工作区、投递方式、收件会话、原文摘要。
     *
     * 只写日志、不回写信箱：回执是**旁证**，投递主流程（投递成功才删行）不受它影响——
     * 目录不存在、写失败都只记 [FileLogger]，绝不让一次投递因为「记账失败」而失败。
     */
    fun recordDelivered(workspacePath: String, messages: List<String>, channel: String, sessionId: String?) {
        if (messages.isEmpty() || !enabled()) return
        val workspaceId = WorkspaceIdentity.idFor(workspacePath) ?: return
        val stamp = runCatching { OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) }
            .getOrDefault("")
        synchronized(lock) {
            runCatching {
                val file = deliveryLogFile()
                file.parentFile?.mkdirs()
                val existing = if (file.isFile) {
                    runCatching { file.readLines() }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                val fresh = messages.map { deliveryLine(stamp, workspaceId, channel, sessionId, it) }
                file.writeText((existing + fresh).takeLast(MAX_LOG_LINES).joinToString("\n") + "\n")
            }.onFailure { FileLogger.w(TAG, "写信箱投递回执失败: ${it.message}", it) }
        }
    }

    /**
     * 最近 [limit] 条投递回执（时间早的在前）；日志不存在或读取失败时返回空表。
     *
     * 只读查询：给工具层/调试用（当前还没有工具入口，需要时在工具里注入本类调它）。
     */
    fun recentDeliveries(limit: Int = RECENT_DELIVERY_LIMIT): List<DeliveryRecord> = synchronized(lock) {
        if (!enabled()) return@synchronized emptyList<DeliveryRecord>()
        val file = runCatching { deliveryLogFile() }.getOrNull() ?: return@synchronized emptyList<DeliveryRecord>()
        if (!file.isFile) return@synchronized emptyList<DeliveryRecord>()
        val text = runCatching { file.readText() }
            .onFailure { FileLogger.w(TAG, "读取信箱投递回执失败: ${it.message}", it) }
            .getOrNull() ?: return@synchronized emptyList<DeliveryRecord>()
        text.lineSequence().mapNotNull { parseDelivery(it) }.toList().takeLast(limit.coerceAtLeast(0))
    }

    /** 一条投递回执，对应 `delivered.log` 的一行。 */
    data class DeliveryRecord(
        val deliveredAt: String,
        val workspaceId: String,
        val channel: String,
        val sessionId: String,
        val summary: String
    )

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

    /** 回执日志文件：与各工作区信箱同目录（[File] 不在时由写入处 mkdirs）。 */
    private fun deliveryLogFile(): File = File(File(containerInstaller.sharedDir, INBOX_DIR), DELIVERY_LOG)

    companion object {
        private const val TAG = "SharedInbox"
        private const val INBOX_DIR = "inbox"

        /** 信箱通知的发送方标识：通知卡片据此把「跨工作区消息」和「子代理消息」区分开。 */
        const val NOTIFICATION_SENDER_ID = "shared-inbox"

        /** 信箱目录的容器内路径，提示词与文档里都用这个写法。 */
        const val CONTAINER_INBOX_DIR = "~/shared/inbox"

        /** 投递回执日志：与 `<工作区>.md` 同目录，一行一条已投递消息。 */
        const val DELIVERY_LOG = "delivered.log"

        /** 回执日志的容器内路径（给提示词/文档/查询结果引用）。 */
        const val CONTAINER_DELIVERY_LOG = "$CONTAINER_INBOX_DIR/$DELIVERY_LOG"

        /** 投递方式：轮首提示词注入。 */
        const val DELIVERY_CHANNEL_PROMPT = "提示词注入"

        /** 投递方式：主动通知投递（空闲会话被起一轮，忙碌会话搭车进工具结果）。 */
        const val DELIVERY_CHANNEL_NOTIFY = "通知投递"

        /** 调用方没传投递方式时的兜底标记：宁可写「未标注」，也不猜一个填上去。 */
        const val DELIVERY_CHANNEL_UNSPECIFIED = "未标注"

        /** 回执默认取最近多少条。 */
        const val RECENT_DELIVERY_LIMIT = 20

        /** 回执日志最多保留多少行，超出丢最旧的（旁证不值得无限增长）。 */
        private const val MAX_LOG_LINES = 500

        /** 单条回执里保留的原文摘要长度。 */
        private const val SUMMARY_MAX = 120

        private const val FIELD_WORKSPACE = "workspace"
        private const val FIELD_SESSION = "session"
        private const val FIELD_CHANNEL = "channel"
        private const val FIELD_MESSAGE = "msg"

        /**
         * 一行回执：`<时间戳>\tworkspace=<id>\tsession=<id>\tchannel=<方式>\tmsg=<摘要>`。
         * 用 `key=value` + 摘要居末，消息正文里的空格/冒号都不影响解析。
         */
        fun deliveryLine(
            stamp: String,
            workspaceId: String,
            channel: String,
            sessionId: String?,
            message: String
        ): String = listOf(
            stamp,
            "$FIELD_WORKSPACE=$workspaceId",
            "$FIELD_SESSION=${sessionId?.takeIf { it.isNotBlank() } ?: "-"}",
            "$FIELD_CHANNEL=$channel",
            "$FIELD_MESSAGE=${summarize(message)}"
        ).joinToString("\t")

        /** 摘要压成一行：换行与制表符折成单空格（否则会撑坏按行/按制表的解析），过长截断。 */
        fun summarize(message: String): String {
            val flat = message.replace(Regex("\\s+"), " ").trim()
            return if (flat.length <= SUMMARY_MAX) flat else flat.take(SUMMARY_MAX) + "…"
        }

        /** 解析一行回执；格式不认识的（手改、被截断）返回 null，不抛。 */
        fun parseDelivery(line: String): DeliveryRecord? {
            val parts = line.split('\t')
            if (parts.size < 2) return null
            val fields = parts.drop(1).mapNotNull { field ->
                val at = field.indexOf('=')
                if (at <= 0) null else field.substring(0, at) to field.substring(at + 1)
            }.toMap()
            val workspaceId = fields[FIELD_WORKSPACE]?.takeIf { it.isNotBlank() } ?: return null
            return DeliveryRecord(
                deliveredAt = parts[0].trim(),
                workspaceId = workspaceId,
                channel = fields[FIELD_CHANNEL].orEmpty(),
                sessionId = fields[FIELD_SESSION].orEmpty().takeUnless { it == "-" }.orEmpty(),
                summary = fields[FIELD_MESSAGE].orEmpty()
            )
        }

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
