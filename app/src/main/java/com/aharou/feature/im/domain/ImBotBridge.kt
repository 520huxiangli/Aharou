package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImCallbackQuery
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.pairing.ImPairingStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 机器人接入的公共编排层：长轮询 → 配对校验 → 跑一轮 agent → 把流式结果回写。
 *
 * 与平台无关——收发的脏活交给 [ImChannel]，配置与游标交给 [ImSettingsSource]，
 * 这里只负责「谁该被回答、回答了什么、怎么分段发出去」。
 *
 * 三个平台共用这一份，平台差异全部靠 [ImChannel] 的可选能力降级：
 * 不支持编辑消息的（微信）只在结束时发最终文本；不支持按钮的，审批改成纯文本一问一答。
 *
 * 不是 `@Singleton`：每个渠道一个实例，由 Hilt 模块按 `@Named` 提供。
 */
class ImBotBridge (
    /** 渠道标识，用于日志与会话 id 前缀，如 `telegram`。 */
    private val channelId: String,
    private val channel: ImChannel,
    private val settings: ImSettingsSource,
    private val pairing: ImPairingStore,
    private val agentTurnRunner: AgentTurnRunner,
    private val sessionUseCase: SessionUseCase,
    private val toolPermissionManager: ToolPermissionManager,
    @param:ApplicationContext private val context: Context
) {
    /** 设置界面的 ViewModel 拿它读写本渠道的配对记录。 */
    val id: String get() = channelId

    private val _state = MutableStateFlow<ImChannelState>(ImChannelState.Stopped)
    val state: StateFlow<ImChannelState> = _state.asStateFlow()

    /** 已配对名单，设置界面据此展示与移除。审批动作在 ImPairingStore 上，这里只做快照。 */
    private val _pairedChats = MutableStateFlow<List<PairedChat>>(emptyList())
    val pairedChats: StateFlow<List<PairedChat>> = _pairedChats.asStateFlow()

    @Volatile private var bridgeScope: CoroutineScope? = null
    @Volatile private var bridgeJob: Job? = null
    @Volatile private var identity = ""
    @Volatile private var cursor = 0L
    @Volatile private var primed = false

    /** 同一聊天串行处理，避免两条消息并发跑 agent 把会话搅乱。 */
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    /** 已发过审批卡片的请求 id，防重复推送。 */
    private val askedApprovals = ConcurrentHashMap.newKeySet<String>()

    /** 无按钮平台（微信/QQ）的待批审批：chatId → 请求，用户回「允许/拒绝」时消化。 */
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    private data class PendingApproval(val id: String, val single: Boolean)

    /** 这个聊天已经映射到的会话 id（审批要知道发给谁）。 */
    private val chatOfSession = ConcurrentHashMap<String, String>()

    /** 最近一次有消息进来的聊天：工具调用时拿不到 chatId，靠它定位回哪个聊天。 */
    @Volatile private var lastActiveChat: String? = null

    fun start(parent: CoroutineScope) {
        if (bridgeJob?.isActive == true) return
        bridgeScope = parent
        // 工具审批：有按钮就发卡片，没按钮就发文本并记住待批，等用户回一个字
        parent.launch {
            toolPermissionManager.pendingRequest.collect { request ->
                request?.let {
                    askForApproval(it.id, it.sessionId, single = true, title = it.title, summary = it.summary)
                }
            }
        }
        parent.launch {
            toolPermissionManager.pendingBatch.collect { batch ->
                batch?.let {
                    val title = it.items.firstOrNull()?.title.orEmpty()
                    val summary = it.items.joinToString("\n") { item -> item.summary }.take(1500)
                    askForApproval(it.id, it.sessionId, single = false, title = title, summary = summary)
                }
            }
        }
        bridgeJob = parent.launch {
            try {
                pollLoop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FileLogger.e(TAG, "[$channelId] 轮询异常退出", e)
                _state.value = ImChannelState.Error(channel.redact(e.message.orEmpty(), settings.currentConfig()))
            } finally {
                _state.value = ImChannelState.Stopped
            }
        }
    }

    fun stop() {
        bridgeJob?.cancel()
        bridgeJob = null
        bridgeScope = null
        _state.value = ImChannelState.Stopped
    }

    fun isRunning(): Boolean = bridgeJob?.isActive == true

    /** 最近活跃的聊天 id；从没收到过消息时为 null。 */
    fun activeChatId(): String? = lastActiveChat

    /**
     * 把本地文件发到最近活跃的聊天，给 agent 侧的工具用。
     *
     * 只认「刚说过话」的那个聊天：没有活跃会话就不发，避免把用户的文件误发到别处。
     */
    suspend fun sendFile(
        file: File,
        kind: ImAttachment.Kind = ImAttachment.Kind.FILE,
        caption: String = ""
    ): Boolean {
        val chatId = lastActiveChat ?: return false
        if (!file.exists() || !file.isFile) return false
        val cfg = settings.currentConfig()
        if (!cfg.enabled || !cfg.ready) return false
        return runCatching { channel.sendMedia(cfg, chatId, file, kind, caption) }.getOrNull()?.isNotBlank() == true
    }

    /** 重新拉一次本渠道的已配对名单（approve/deny/remove 后调）。 */
    suspend fun refreshPaired() {
        _pairedChats.value = runCatching { pairing.approved(channelId) }.getOrDefault(emptyList())
    }

    private suspend fun pollLoop() {
        cursor = settings.lastCursor().toLongOrNull() ?: 0L
        primed = cursor > 0L
        var delayMs = RETRY_MIN_MS
        while (currentCoroutineContext().isActive) {
            val cfg = settings.currentConfig()
            if (!cfg.enabled || !cfg.ready) {
                _state.value = ImChannelState.Stopped
                return
            }
            try {
                if (identity.isBlank()) {
                    val me = channel.identity(cfg)
                    identity = me.username
                    // 首次运行丢弃积压：刚配好的机器人不该回答几天前堆下的消息
                    channel.prepare(cfg, dropHistory = !primed)
                    primed = true
                }
                if (_state.value !is ImChannelState.Connected) {
                    _state.value = ImChannelState.Connected(identity)
                }
                val timeout = if (channelId == "weixin") WEIXIN_POLL_TIMEOUT_S else TELEGRAM_POLL_TIMEOUT_S
                val updates = channel.poll(cfg, cursor, timeout)
                delayMs = RETRY_MIN_MS
                if (updates.isNotEmpty()) {
                    FileLogger.d(TAG, "[$channelId] 收到 ${updates.size} 条更新")
                    updates.forEach { update ->
                        cursor = maxOf(cursor, update.updateId + 1)
                        bridgeScope?.launch { handleUpdate(cfg, update) }
                    }
                    settings.saveCursor(cursor.toString())
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val msg = channel.redact(e.message ?: e::class.java.simpleName, cfg)
                _state.value = ImChannelState.Error(msg)
                FileLogger.w(TAG, "[$channelId] 轮询失败，${delayMs}ms 后重试：$msg")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    private suspend fun handleUpdate(cfg: ImChannelConfig, update: ImUpdate) {
        when (update) {
            is ImUpdate.Message -> handleMessage(cfg, update.msg)
            is ImUpdate.Callback -> handleCallback(cfg, update.callback)
        }
    }

    private suspend fun handleMessage(cfg: ImChannelConfig, msg: ImInboundMessage) {
        runCatching {
            val approved = pairing.isApproved(channelId, msg.senderId)
            if (!approved) {
                // 未配对：回一个配对码。已经在等审批的不重复发，避免刷屏。
                FileLogger.i(TAG, "[$channelId] 未配对用户 ${msg.senderId} 发来消息，回复配对码")
                val code = pairing.pending(channelId).firstOrNull { it.senderId == msg.senderId }?.code
                    ?: pairing.request(channelId, msg.senderId, msg.senderName, msg.chatId)
                channel.send(cfg, msg.chatId, pairingPrompt(code))
                return
            }
            if (msg.isGroup && !mentioned(msg)) {
                FileLogger.d(TAG, "[$channelId] 群里没有 @机器人，忽略 chat=${msg.chatId}")
                return
            }
            val text = stripMention(msg.text)
            // 只发图片/文件、不带文字也是有效消息：补一句占位提示再交给 agent，
            // 否则纯附件消息会被当成空消息丢掉
            val attachmentOnly = text.isBlank() && msg.attachments.isNotEmpty()
            if (text.isBlank() && !attachmentOnly) {
                FileLogger.d(TAG, "[$channelId] 消息正文为空且无附件，忽略")
                return
            }
            val prompt = if (attachmentOnly) imText("im_attachment_only_hint") else text
            FileLogger.d(
                TAG,
                "[$channelId] 处理消息 chat=${msg.chatId} 长度=${prompt.length} 附件=${msg.attachments.size}"
            )
            // 记下最近活跃聊天，sendFile 这类无 chatId 的调用要靠它
            lastActiveChat = msg.chatId
            // 无按钮平台的审批回复：先当审批处理，不是审批词再走正常对话
            if (!attachmentOnly && consumeApprovalReply(msg.chatId, text)) return
            runTurn(cfg, msg.chatId, prompt, msg.attachments)
        }.onFailure { e ->
            if (e is CancellationException) throw e
            FileLogger.w(TAG, "[$channelId] 处理消息失败：${channel.redact(e.message.orEmpty(), cfg)}")
        }
    }

    /**
     * 无按钮平台的审批回复。
     *
     * Telegram 点按钮直接走 callback；微信/QQ 没有内联按钮，只能让用户回一个字。
     * 只在该聊天确实有待批请求、且文本命中肯定/否定词时才消化，否则交回正常对话。
     */
    private suspend fun consumeApprovalReply(chatId: String, text: String): Boolean {
        val pending = pendingApprovals[chatId] ?: return false
        val choice = when (text.trim().lowercase()) {
            "允许", "同意", "批准", "y", "yes", "ok", "好" -> PermissionChoice.ONCE
            "拒绝", "不允许", "no", "n", "不行" -> PermissionChoice.REJECT
            else -> return false
        }
        pendingApprovals.remove(chatId)
        if (pending.single) {
            toolPermissionManager.resolve(pending.id, choice)
        } else {
            toolPermissionManager.resolveBatch(pending.id, choice)
        }
        return true
    }

    /** 推送一次审批请求：能按钮就按钮，不能就文本 + 记下待批。 */
    private suspend fun askForApproval(
        id: String,
        sessionId: String,
        single: Boolean,
        title: String,
        summary: String
    ) {
        if (id.isBlank() || sessionId.isBlank()) return
        val chatId = chatOfSession[sessionId] ?: return
        if (!askedApprovals.add(id)) return
        val cfg = settings.currentConfig()
        if (!cfg.enabled || !cfg.ready) return
        val body = buildString {
            if (title.isNotBlank()) append(title)
            if (summary.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(summary)
            }
        }.take(1500).ifBlank { imText("im_telegram_approval_default_title") }
        val prompt = imText("im_telegram_approval_prompt", body)
        if (channel.supportsButtons) {
            val allow = imText("im_telegram_approval_allow")
            val deny = imText("im_telegram_approval_deny")
            val prefix = if (single) "a" else "b"
            channel.sendWithButtons(
                cfg,
                chatId,
                prompt,
                listOf(listOf(allow to "$prefix:y:$id", deny to "$prefix:n:$id"))
            )
        } else {
            // 没有按钮：记住待批，用户回「允许/拒绝」即可；同时告诉他怎么回
            pendingApprovals[chatId] = PendingApproval(id, single)
            channel.send(cfg, chatId, prompt + "\n\n" + imText("im_approval_reply_hint"))
        }
    }

    private suspend fun handleCallback(cfg: ImChannelConfig, cb: ImCallbackQuery) {
        channel.acknowledgeCallback(cfg, cb.id)
        if (cb.messageId.isNotBlank()) channel.clearMarkup(cfg, cb.chatId, cb.messageId)
        if (!pairing.isApproved(channelId, cb.senderId)) return
        val parts = cb.data.split(":", limit = 3)
        if (parts.size < 3 || parts[2].isBlank()) return
        val choice = if (parts[1] == "y") PermissionChoice.ONCE else PermissionChoice.REJECT
        when (parts[0]) {
            "a" -> toolPermissionManager.resolve(parts[2], choice)
            "b" -> toolPermissionManager.resolveBatch(parts[2], choice)
            else -> return
        }
        channel.send(cfg, cb.chatId, if (choice == PermissionChoice.ONCE) approvalAllowed() else approvalDenied())
    }

    private suspend fun runTurn(
        cfg: ImChannelConfig,
        chatId: String,
        text: String,
        attachments: List<ImAttachment> = emptyList()
    ) {
        val lock = chatLocks.getOrPut(chatId) { Mutex() }
        lock.withLock {
            val sessionId = ensureSession(chatId) ?: return
            // 记下会话→聊天的映射，审批请求只有 sessionId 没有 chatId
            chatOfSession[sessionId] = chatId
            streamTurn(cfg, chatId, sessionId, text, attachments)
        }
    }

    /**
     * 把对方发来的附件落到本地目录，再包成 agent 认识的 [AgentAttachment]。
     *
     * 下载失败不阻断对话：拿到几份算几份，一份都拿不到就只发文字。
     */
    private suspend fun materializeAttachments(
        cfg: ImChannelConfig,
        attachments: List<ImAttachment>
    ): List<AgentAttachment> {
        if (attachments.isEmpty()) return emptyList()
        val dir = File(context.filesDir, "aharou/im_files").apply { mkdirs() }
        return attachments.mapNotNull { att ->
            val file = runCatching { channel.downloadAttachment(cfg, att) }.getOrNull()
            if (file == null) {
                FileLogger.w(TAG, "[$channelId] 附件下载失败：${att.fileName.ifBlank { att.mimeType }}")
                return@mapNotNull null
            }
            val name = att.fileName.ifBlank { file.name }
            val target = File(dir, "${System.currentTimeMillis()}-$name")
            val moved = runCatching {
                if (file.absolutePath != target.absolutePath) {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
                target
            }.getOrElse { file }
            AgentAttachment(
                fileName = name,
                containerPath = moved.absolutePath,
                localPath = moved.absolutePath,
                mimeType = att.mimeType.ifBlank { "application/octet-stream" },
                sizeBytes = if (att.sizeBytes > 0) att.sizeBytes else moved.length(),
                isImage = att.kind == ImAttachment.Kind.IMAGE ||
                    att.mimeType.startsWith("image/")
            )
        }
    }

    private suspend fun ensureSession(chatId: String): String? {
        val sessionId = sessionIdFor(chatId)
        if (sessionUseCase.getSessionById(sessionId) != null) return sessionId
        val workspace = agentTurnRunner.currentWorkspacePath()
        if (workspace.isBlank()) {
            FileLogger.w(TAG, "[$channelId] 工作区未就绪，跳过会话 $sessionId")
            return null
        }
        val entity = agentTurnRunner.createSessionEntity(workspace).copy(
            id = sessionId,
            title = "$channelLabel · $chatId"
        )
        sessionUseCase.upsertSession(entity)
        return sessionId
    }

    private suspend fun streamTurn(
        cfg: ImChannelConfig,
        chatId: String,
        sessionId: String,
        text: String,
        attachments: List<ImAttachment> = emptyList()
    ) {
        val inputs = materializeAttachments(cfg, attachments)
        var live: LiveMessage? = null
        var latest = ""
        agentTurnRunner.run(
            AgentTurnRequest(
                sessionId = sessionId,
                text = text,
                inputAttachments = inputs,
                skipTitleUpdate = true
            )
        ).collect { event ->
            when (event) {
                is AgentEvent.AssistantDelta -> if (event.accumulated.isNotBlank()) {
                    latest = event.accumulated
                    (live ?: LiveMessage(cfg, chatId).also { live = it }).onDelta(event.accumulated)
                }
                // Completed 不带正文：收尾时用最后一次累积的文本
                AgentEvent.Completed -> live?.finish(latest) ?: Unit
                is AgentEvent.Failed -> {
                    val shown = channel.redact(event.error, cfg)
                    live?.finish(errorText(shown)) ?: channel.send(cfg, chatId, errorText(shown))
                }
                else -> Unit
            }
        }
    }

    private fun mentioned(msg: ImInboundMessage): Boolean =
        msg.mentionsBot || msg.text.contains("@") || msg.replyToSenderId != null

    private fun stripMention(text: String): String = text.trim()

    // 文案走 Context：编排层在多平台复用，语言跟随 App 当前设置
    private fun pairingPrompt(code: String): String =
        imText("im_pairing_prompt", code, channelLabel)
    private fun approvalAllowed(): String = imText("im_telegram_approval_allowed")
    private fun approvalDenied(): String = imText("im_telegram_approval_denied")
    private fun errorText(detail: String): String = imText("im_telegram_error", detail)

    private fun imText(key: String, vararg args: Any): String {
        val id = context.resources.getIdentifier(key, "string", context.packageName)
        return if (id == 0) key else context.getString(id, *args)
    }

    /** 渠道名跟随 App 语言，配对提示里要告诉用户去哪个设置页通过。 */
    private val channelLabel: String
        get() = imText(
            when (channelId) {
                "weixin" -> "im_weixin_service_title"
                "feishu" -> "im_feishu_service_title"
                "qq" -> "im_qq_service_title"
                else -> "im_telegram_service_title"
            }
        )

    /**
     * 流式回写。
     *
     * 支持编辑的平台：先发一条，之后按 [EDIT_INTERVAL_MS] 原地刷新；
     * 不支持的（微信）：只在结束时发最终文本，中间过程不打扰用户。
     */
    private inner class LiveMessage(private val cfg: ImChannelConfig, private val chatId: String) {
        private var messageId: String? = null
        private var buffer = ""
        private var lastEditAt = 0L

        suspend fun onDelta(accumulated: String) {
            buffer = accumulated
            // 不支持编辑的平台一条都不发：只在 finish 时发最终稿（同一条里追加会变成多条）
            if (!channel.supportsEdit) return
            val now = System.currentTimeMillis()
            val id = messageId
            if (id == null) {
                messageId = channel.send(cfg, chatId, accumulated.trim())
                lastEditAt = now
            } else if (now - lastEditAt >= EDIT_INTERVAL_MS) {
                if (channel.edit(cfg, chatId, id, accumulated.trim())) lastEditAt = now
            }
        }

        suspend fun finish(text: String) {
            if (text.isNotBlank()) buffer = text
            val shown = buffer.trim()
            if (shown.isEmpty()) return
            val id = messageId
            if (id != null && channel.supportsEdit && channel.edit(cfg, chatId, id, shown)) return
            // 没发过任何中间态，或平台不支持编辑：只发这一次
            channel.send(cfg, chatId, shown)
        }
    }

    companion object {
        private const val TAG = "ImBotBridge"
        private const val EDIT_INTERVAL_MS = 1000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
        private const val TELEGRAM_POLL_TIMEOUT_S = 50
        private const val WEIXIN_POLL_TIMEOUT_S = 25

        /** 一个聊天固定映射到的 Aharou 会话 id。 */
        fun sessionIdFor(channelId: String, chatId: String): String = "im-$channelId-$chatId"
    }

    private fun sessionIdFor(chatId: String): String = sessionIdFor(channelId, chatId)
}
