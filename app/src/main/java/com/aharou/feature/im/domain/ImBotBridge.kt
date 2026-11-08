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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
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
    private val agentTurnRunner: AgentTurnRunner,
    private val sessionUseCase: SessionUseCase,
    private val toolPermissionManager: ToolPermissionManager,
    @param:ApplicationContext private val context: Context,
    // 所有者绑定用 Hilt 的 @Singleton 实例；给默认值是为了不动 di/ImBridgeModule 的四个构造点，
    // 显式传入时以传入的为准（DataStore 由文件级委托统一持有，多实例不会各开一份）
    private val owners: ImOwnerStore = ImOwnerStore(context),
    private val policy: ImBridgePolicy = ImBridgePolicy.Default,
    private val fallbackWorkspacePath: suspend () -> String = { "" }
) {
    /** 设置界面的 ViewModel 拿它读写本渠道的配对记录。 */
    val id: String get() = channelId

    private val _state = MutableStateFlow<ImChannelState>(ImChannelState.Stopped)
    val state: StateFlow<ImChannelState> = _state.asStateFlow()

    @Volatile private var bridgeJob: Job? = null
    @Volatile private var activeConfig: ImChannelConfig? = null
    @Volatile private var identity = ""
    @Volatile private var cursor = 0L
    @Volatile private var primed = false

    private var telegramInbox: TelegramInbox? = null
    private val deliveryLock = Mutex()
    private val pendingUpdates = LinkedHashMap<Long, Boolean>()
    private val seenUpdateIds = LinkedHashSet<Long>()

    /** 同一聊天串行处理，避免两条消息并发跑 agent 把会话搅乱。 */
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    /** 已发过审批卡片的请求 id，防重复推送。 */
    private val askedApprovals = ConcurrentHashMap.newKeySet<String>()

    /** 审批 id → 发起这次审批的 chatId：按钮回调必须来自同一个聊天，别的聊天点不到。 */
    private val chatOfApproval = ConcurrentHashMap<String, ApprovalOrigin>()

    /** 无按钮平台（微信/QQ）的待批审批：chatId → 请求，用户回「允许/拒绝」时消化。 */
    private val pendingApprovals = ConcurrentHashMap<String, PendingApproval>()

    private data class PendingApproval(val id: String, val single: Boolean, val sessionId: String)
    private data class ApprovalOrigin(val chatId: String, val pending: PendingApproval)
    private data class SessionOrigin(val chatId: String, val cfg: ImChannelConfig)

    /** 这个聊天已经映射到的会话 id（审批要知道发给谁）。 */
    private val chatOfSession = ConcurrentHashMap<String, SessionOrigin>()

    /** 最近一次有消息进来的聊天：工具调用时拿不到 chatId，靠它定位回哪个聊天。 */
    @Volatile private var lastActiveChat: String? = null

    @Synchronized
    fun start(parent: CoroutineScope) {
        if (bridgeJob?.isActive == true) return
        val previous = bridgeJob
        bridgeJob = parent.launch {
            previous?.join()
            try {
                settings.configFlow.distinctUntilChanged().collectLatest { cfg ->
                    if (policy.cancelApprovalsOnReset) cancelMappedApprovals()
                    channel.disconnect()
                    identity = ""
                    activeConfig = null
                    lastActiveChat = null
                    chatOfSession.clear()
                    chatOfApproval.clear()
                    pendingApprovals.clear()
                    askedApprovals.clear()
                    pendingUpdates.clear()
                    seenUpdateIds.clear()
                    if (!cfg.enabled || !cfg.ready) {
                        _state.value = ImChannelState.Stopped
                        return@collectLatest
                    }
                    try {
                        runConnection(cfg)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        FileLogger.e(TAG, "[$channelId] 连接异常退出", e)
                        _state.value = ImChannelState.Error(channel.redact(e.message.orEmpty(), cfg))
                    } finally {
                        channel.disconnect()
                    }
                }
            } finally {
                channel.disconnect()
                _state.value = ImChannelState.Stopped
            }
        }
    }

    private suspend fun runConnection(cfg: ImChannelConfig) = coroutineScope {
        telegramInbox = null
        activeConfig = cfg
        val inbox = Channel<ImUpdate>(Channel.UNLIMITED)
        val controls = Channel<ImUpdate>(Channel.UNLIMITED)
        try {
            launch {
                toolPermissionManager.pendingRequest.collect { request ->
                    request?.let { askForApproval(it.id, it.sessionId, true, it.title, it.summary) }
                }
            }
            launch {
                toolPermissionManager.pendingBatch.collect { batch ->
                    batch?.let {
                        askForApproval(it.id, it.sessionId, false, it.items.firstOrNull()?.title.orEmpty(),
                            it.items.joinToString("\n") { item -> item.summary }.take(1500))
                    }
                }
            }
            launch { consumeLoop(cfg, inbox) }
            launch { consumeLoop(cfg, controls) }
            pollLoop(cfg, inbox, controls)
        } finally {
            inbox.close()
            controls.close()
            activeConfig = null
        }
    }

    @Synchronized
    fun stop() {
        if (policy.cancelApprovalsOnReset) cancelMappedApprovals()
        bridgeJob?.cancel()
        activeConfig = null
        channel.disconnect()
        _state.value = ImChannelState.Stopped
    }

    fun isRunning(): Boolean = bridgeJob?.isActive == true

    /** 最近活跃的聊天 id；从没收到过消息时为 null。 */
    fun activeChatId(): String? = lastActiveChat

    /**
     * 解绑本渠道的全部所有者：之后第一个说话的聊天会重新成为所有者。
     * 给设置界面留的入口，换人接手时不必清应用数据。
     */
    suspend fun clearOwners() {
        val cfg = activeConfig ?: settings.currentConfig()
        owners.clearOwners(policy.ownerNamespace(channelId, cfg))
        lastActiveChat = null
    }

    /**
     * 所有者门禁 + 认主，消息与回调共用的头一道关。
     *
     * 已有绑定：只认集合内的聊天，其余一律丢弃。
     * 尚无绑定：确认这个聊天确实在跟机器人说话（[directed]）才把它认成所有者；
     * 群聊里不带 @ 的路人发言不认，否则群里任何人说一句话就把整个群变成了所有者。
     *
     * 判据是 chatId 而不是 senderId：群聊里一个 chat 可能有多人说话，但会话与审批都按聊天走，
     * 按聊天判才与 runTurn 的会话映射、审批回写路径对齐。
     */
    private suspend fun acceptChatId(chatId: String, directed: Boolean): Boolean {
        if (chatId.isBlank()) return false
        val cfg = activeConfig ?: return false
        val namespace = policy.ownerNamespace(channelId, cfg)
        val current = owners.owners(namespace)
        if (chatId in current) return true
        if (current.isNotEmpty()) {
            FileLogger.d(TAG, "[$channelId] 丢弃非所有者聊天 $chatId（已绑定 ${current.size} 个）")
            return false
        }
        if (!directed) return true
        if (owners.bindIfEmpty(namespace, chatId)) {
            FileLogger.i(TAG, "[$channelId] 首次使用，已把 chat=$chatId 绑定为所有者")
            return true
        }
        // 并发下别的聊天抢先绑上了：以落盘结果为准再判一次
        return owners.isOwner(namespace, chatId)
    }

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
        val cfg = activeConfig ?: return false
        currentCoroutineContext().ensureActive()
        if (!cfg.enabled || !cfg.ready || settings.currentConfig() != cfg) return false
        return try {
            channel.sendMedia(cfg, chatId, file, kind, caption)?.isNotBlank() == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    fun chatOfSession(sessionId: String): String? = chatOfSession[sessionId]?.takeIf {
        it.cfg == activeConfig && isRunning()
    }?.chatId

    suspend fun sendFileForSession(
        sessionId: String,
        file: File,
        kind: ImAttachment.Kind = ImAttachment.Kind.FILE,
        caption: String = ""
    ): Boolean {
        val origin = chatOfSession[sessionId] ?: return false
        val cfg = activeConfig ?: return false
        if (origin.cfg != cfg || !cfg.enabled || !cfg.ready || !isRunning() ||
            settings.currentConfig() != cfg || !file.isFile) return false
        return try {
            currentCoroutineContext().ensureActive()
            channel.sendMedia(cfg, origin.chatId, file, kind, caption)?.isNotBlank() == true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            false
        }
    }

    private fun cancelMappedApprovals() {
        chatOfSession.keys.forEach { sessionId ->
            toolPermissionManager.pendingForSession(sessionId)?.let {
                toolPermissionManager.tryResolve(it.id, sessionId, PermissionChoice.REJECT)
            }
            toolPermissionManager.pendingBatchForSession(sessionId)?.let {
                toolPermissionManager.tryResolveBatch(it.id, sessionId, PermissionChoice.REJECT)
            }
        }
        chatOfApproval.values.forEach { resolveApproval(it.pending, PermissionChoice.REJECT) }
    }

    private suspend fun pollLoop(cfg: ImChannelConfig, inbox: Channel<ImUpdate>, controls: Channel<ImUpdate>) {
        cursor = settings.lastCursor().toLongOrNull() ?: 0L
        primed = cursor > 0L
        var delayMs = RETRY_MIN_MS
        while (currentCoroutineContext().isActive) {
            try {
                if (identity.isBlank()) {
                    val me = channel.identity(cfg)
                    if (channelId == "telegram") {
                        telegramInbox = withContext(Dispatchers.IO) {
                            TelegramInbox(File(context.filesDir, "aharou/telegram_inbox"), me.id)
                        }
                    }
                    val recovered = telegramInbox?.pending().orEmpty()
                    channel.prepare(cfg, dropHistory = !primed && recovered.isEmpty())
                    recovered.forEach { enqueue(it, inbox, controls, recovered = true) }
                    identity = me.username.ifBlank { me.id.toString() }
                    primed = true
                }
                if (_state.value !is ImChannelState.Connected) {
                    _state.value = ImChannelState.Connected(identity)
                }
                val timeout = if (channelId == "weixin") WEIXIN_POLL_TIMEOUT_S else TELEGRAM_POLL_TIMEOUT_S
                val fetchOffset = telegramInbox?.nextOffset?.coerceAtLeast(cursor) ?: cursor
                val updates = channel.poll(cfg, fetchOffset, timeout)
                currentCoroutineContext().ensureActive()
                deliveryLock.withLock {
                    telegramInbox?.let { durable -> withContext(Dispatchers.IO) { durable.append(updates) } }
                }
                delayMs = RETRY_MIN_MS
                if (updates.isNotEmpty()) {
                    FileLogger.d(TAG, "[$channelId] 收到 ${updates.size} 条更新")
                    updates.sortedBy { it.updateId }.forEach { enqueue(it, inbox, controls) }
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

    private suspend fun enqueue(update: ImUpdate, inbox: Channel<ImUpdate>, controls: Channel<ImUpdate>, recovered: Boolean = false) {
        val fresh = deliveryLock.withLock {
            flushCursor()
            if ((!recovered && channelId == "telegram" && update.updateId < cursor) || update.updateId in seenUpdateIds ||
                pendingUpdates.containsKey(update.updateId)) false
            else { pendingUpdates[update.updateId] = false; true }
        }
        if (fresh) (if (isApprovalControl(update)) controls else inbox).send(update)
    }

    private suspend fun handleUpdate(cfg: ImChannelConfig, update: ImUpdate): Boolean =
        when (update) {
            is ImUpdate.Message -> handleMessage(cfg, update.msg)
            is ImUpdate.Callback -> handleCallback(cfg, update.callback)
        }

    /**
     * 串行消费队列：一条处理成功才推进并落盘游标；失败按次数重试，重试耗尽才放行到下一条。
     *
     * Telegram 先持久入队再拉下一页；完成游标只越过已完成的连续前缀。
     * 执行副作用与本地确认并非事务，崩溃重放仍可能重复执行。
     */
    private suspend fun consumeLoop(cfg: ImChannelConfig, inbox: Channel<ImUpdate>) {
        for (update in inbox) {
            val updateId = update.updateId

            var attempt = 0
            var delivered = false
            while (!delivered && attempt < MAX_DELIVERY_ATTEMPTS) {
                delivered = runCatching { handleUpdate(cfg, update) }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    FileLogger.w(
                        TAG,
                        "[$channelId] 处理消息 $updateId 失败（第 ${attempt + 1} 次）：${channel.redact(e.message.orEmpty(), cfg)}"
                    )
                    false
                }
                if (!delivered && ++attempt < MAX_DELIVERY_ATTEMPTS) delay(DELIVERY_RETRY_MS * attempt)
            }
            if (!delivered) {
                FileLogger.e(TAG, "[$channelId] 消息 $updateId 重试 $MAX_DELIVERY_ATTEMPTS 次仍失败，跳过并推进游标")
            }
            deliveryLock.withLock {
                pendingUpdates[updateId] = true
                seenUpdateIds.add(updateId)
                if (seenUpdateIds.size > SEEN_UPDATE_LIMIT) seenUpdateIds.remove(seenUpdateIds.first())
                flushCursor()
            }
        }
    }

    private suspend fun flushCursor() {
        val completed = pendingUpdates.entries.takeWhile { it.value }.map { it.key }
        val target = completed.maxOrNull()?.plus(1) ?: return
        try {
            settings.saveCursor(maxOf(cursor, target).toString())
            telegramInbox?.let { durable -> withContext(Dispatchers.IO) { durable.remove(completed) } }
            cursor = maxOf(cursor, target)
            completed.forEach { pendingUpdates.remove(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FileLogger.w(TAG, "[$channelId] 保存游标失败", e)
        }
    }

    private fun callbackParts(data: String): List<String>? = data.split(":", limit = 3).takeIf {
        it.size == 3 && it[0] in setOf("a", "b") && it[1] in setOf("y", "n") && it[2].isNotBlank()
    }

    private fun approvalChoice(text: String): PermissionChoice? = when (text.trim().lowercase()) {
        "允许", "同意", "批准", "y", "yes", "ok", "好" -> PermissionChoice.ONCE
        "拒绝", "不允许", "no", "n", "不行" -> PermissionChoice.REJECT
        else -> null
    }

    private fun isApprovalControl(update: ImUpdate): Boolean = when (update) {
        is ImUpdate.Callback -> callbackParts(update.callback.data)?.let { parts ->
            chatOfApproval[parts[2]]?.let { origin ->
                origin.chatId == update.callback.chatId && origin.pending.single == (parts[0] == "a")
            }
        } == true
        is ImUpdate.Message -> pendingApprovals.containsKey(update.msg.chatId) &&
            approvalChoice(stripMention(update.msg.text)) != null &&
            update.msg.attachments.isEmpty() && activeConfig?.let {
                policy.accepts(it, update.msg) && policy.directed(it, update.msg)
            } == true
    }

    private suspend fun handleMessage(cfg: ImChannelConfig, msg: ImInboundMessage): Boolean {
        val result = runCatching {
            // 所有者校验摆在最前面：没有绑定时这里会把当前聊天认成所有者，已有绑定时陌生聊天直接丢弃，
            // 不再往下走到 @ 判定与 runTurn（否则任何能给 bot 发消息的人都能驱动本机的 agent）
            if (!policy.accepts(cfg, msg)) return@runCatching
            val directed = policy.directed(cfg, msg)
            if (!policy.explicitlyAuthorized(cfg, msg) && !acceptChatId(msg.chatId, directed = directed)) return@runCatching
            if (msg.isGroup && !directed) {
                FileLogger.d(TAG, "[$channelId] 群里没有 @机器人，忽略 chat=${msg.chatId}")
                return@runCatching
            }
            val text = stripMention(msg.text)
            // 只发图片/文件、不带文字也是有效消息：补一句占位提示再交给 agent，
            // 否则纯附件消息会被当成空消息丢掉
            val attachmentOnly = text.isBlank() && msg.attachments.isNotEmpty()
            if (text.isBlank() && !attachmentOnly) {
                FileLogger.d(TAG, "[$channelId] 消息正文为空且无附件，忽略")
                return@runCatching
            }
            val prompt = text
            FileLogger.d(
                TAG,
                "[$channelId] 处理消息 chat=${msg.chatId} 长度=${prompt.length} 附件=${msg.attachments.size}"
            )
            // 记下最近活跃聊天，sendFile 这类无 chatId 的调用要靠它
            lastActiveChat = msg.chatId
            // 无按钮平台的审批回复：先当审批处理，不是审批词再走正常对话
            if (!attachmentOnly && msg.attachments.isEmpty() && consumeApprovalReply(cfg, msg.chatId, text)) return@runCatching
            pendingApprovals.remove(msg.chatId)
            runTurn(cfg, msg.chatId, prompt, msg.attachments)
        }
        return result.onFailure { e ->
            if (e is CancellationException) throw e
            FileLogger.w(TAG, "[$channelId] 处理消息失败：${channel.redact(e.message.orEmpty(), cfg)}")
        }.isSuccess
    }

    /**
     * 无按钮平台的审批回复。
     *
     * Telegram 点按钮直接走 callback；微信/QQ 没有内联按钮，只能让用户回一个字。
     * 只在该聊天确实有待批请求、且文本命中肯定/否定词时才消化，否则交回正常对话。
     */
    private suspend fun consumeApprovalReply(cfg: ImChannelConfig, chatId: String, text: String): Boolean {
        val pending = pendingApprovals[chatId] ?: return false
        val choice = approvalChoice(text) ?: return false
        val origin = chatOfApproval[pending.id] ?: return false
        if (origin.chatId != chatId || pending.sessionId != sessionIdFor(chatId)) return false
        val resolved = resolveApproval(pending, choice)
        notifyApproval(cfg, chatId, choice, resolved)
        return true
    }

    private fun resolveApproval(pending: PendingApproval, choice: PermissionChoice): Boolean =
        if (pending.single) toolPermissionManager.tryResolve(pending.id, pending.sessionId, choice)
        else toolPermissionManager.tryResolveBatch(pending.id, pending.sessionId, choice)

    private suspend fun notifyApproval(cfg: ImChannelConfig, chatId: String, choice: PermissionChoice, resolved: Boolean) {
        sendNotice(cfg, chatId, if (!resolved) imText("im_approval_expired")
            else if (choice == PermissionChoice.ONCE) approvalAllowed() else approvalDenied())
    }

    private suspend fun sendNotice(cfg: ImChannelConfig, chatId: String, text: String) {
        try {
            currentCoroutineContext().ensureActive()
            channel.send(cfg, chatId, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FileLogger.w(TAG, "[$channelId] 发送提示失败：${channel.redact(e.message.orEmpty(), cfg)}")
        }
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
        val session = chatOfSession[sessionId] ?: return
        val chatId = session.chatId
        val cfg = session.cfg
        if (!askedApprovals.add(id)) return
        val pending = PendingApproval(id, single, sessionId)
        chatOfApproval[id] = ApprovalOrigin(chatId, pending)
        if (!cfg.enabled || !cfg.ready) return
        val body = buildString {
            if (title.isNotBlank()) append(title)
            if (summary.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(summary)
            }
        }.take(1500).ifBlank { imText("im_telegram_approval_default_title") }
        val prompt = imText("im_telegram_approval_prompt", body)
        try {
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
                pendingApprovals[chatId] = pending
                channel.send(cfg, chatId, prompt + "\n\n" + imText("im_approval_reply_hint"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FileLogger.w(TAG, "[$channelId] 发送审批失败：${channel.redact(e.message.orEmpty(), cfg)}")
        }
    }

    private suspend fun handleCallback(cfg: ImChannelConfig, cb: ImCallbackQuery): Boolean = runCatching {
        // 回调与消息同一条门禁：非所有者聊天里根本不会有审批卡片
        if (!acceptChatId(cb.chatId, directed = true)) return@runCatching
        val parts = callbackParts(cb.data) ?: return@runCatching
        // 只认发起这次审批的聊天：同一渠道可能有多个绑定聊天，A 聊天里的按钮不该能批 B 聊天发起的工具调用。
        // 查不到发起记录（进程重启后内存映射已空）一并丢弃，宁可让审批超时也不接受来源不明的放行。
        val origin = chatOfApproval[parts[2]]
        if (origin == null || origin.chatId != cb.chatId || origin.pending.single != (parts[0] == "a") ||
            origin.pending.sessionId != sessionIdFor(cb.chatId)) {
            FileLogger.d(TAG, "[$channelId] 丢弃来源不符的审批回调 chat=${cb.chatId} 发起=$origin")
            return@runCatching
        }
        val choice = if (parts[1] == "y") PermissionChoice.ONCE else PermissionChoice.REJECT
        val resolved = resolveApproval(origin.pending, choice)
        notifyApproval(cfg, cb.chatId, choice, resolved)
        try {
            channel.acknowledgeCallback(cfg, cb.id)
            if (cb.messageId.isNotBlank()) channel.clearMarkup(cfg, cb.chatId, cb.messageId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FileLogger.w(TAG, "[$channelId] 清理审批按钮失败：${channel.redact(e.message.orEmpty(), cfg)}")
        }
    }.onFailure { e ->
        if (e is CancellationException) throw e
        FileLogger.w(TAG, "[$channelId] 处理回调失败：${channel.redact(e.message.orEmpty(), cfg)}")
    }.isSuccess

    private suspend fun runTurn(
        cfg: ImChannelConfig,
        chatId: String,
        text: String,
        attachments: List<ImAttachment> = emptyList()
    ) {
        val lock = chatLocks.getOrPut(chatId) { Mutex() }
        lock.withLock {
            val sessionId = ensureSession(cfg, chatId) ?: return
            // 记下会话→聊天的映射，审批请求只有 sessionId 没有 chatId
            chatOfSession[sessionId] = SessionOrigin(chatId, cfg)
            streamTurn(cfg, chatId, sessionId, text, attachments)
        }
    }

    private data class ReceivedAttachments(val inputs: List<AgentAttachment>, val failedNames: List<String>)
    private suspend fun materializeAttachments(
        cfg: ImChannelConfig,
        attachments: List<ImAttachment>
    ): ReceivedAttachments {
        val inputs = mutableListOf<AgentAttachment>()
        val failed = mutableListOf<String>()
        val dir = File(context.filesDir, "aharou/im_files").apply { mkdirs() }
        attachments.forEachIndexed { index, att ->
            val file = try {
                channel.downloadAttachment(cfg, att).also { currentCoroutineContext().ensureActive() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                null
            }
            val name = channel.redact(att.fileName, cfg).takeUnless { it.contains("://") }
                ?.substringBefore('?')?.substringAfterLast('/')?.substringAfterLast('\\')
                ?.replace(Regex("[\\p{Cntrl}]"), "_")?.take(160)?.ifBlank { null } ?: "#${index + 1}"
            if (file == null || !file.isFile) {
                failed.add(name)
                return@forEachIndexed
            }
            val target = File(dir, "${System.currentTimeMillis()}-$name")
            val moved = runCatching {
                if (file.absolutePath != target.absolutePath) {
                    file.copyTo(target, overwrite = true)
                    file.delete()
                }
                target
            }.getOrElse { file }
            inputs.add(AgentAttachment(
                fileName = name,
                containerPath = moved.absolutePath,
                localPath = moved.absolutePath,
                mimeType = att.mimeType.ifBlank { "application/octet-stream" },
                sizeBytes = if (att.sizeBytes > 0) att.sizeBytes else moved.length(),
                isImage = att.kind == ImAttachment.Kind.IMAGE ||
                    att.mimeType.startsWith("image/")
            ))
        }
        return ReceivedAttachments(inputs, failed)
    }

    private suspend fun ensureSession(cfg: ImChannelConfig, chatId: String): String? {
        val sessionId = sessionIdFor(chatId)
        if (sessionUseCase.getSessionById(sessionId) != null) return sessionId
        val defaults = policy.sessionDefaults(cfg)
        val workspace = defaults.workspacePath.ifBlank {
            agentTurnRunner.currentWorkspacePath().ifBlank { fallbackWorkspacePath() }
        }
        if (workspace.isBlank()) {
            FileLogger.w(TAG, "[$channelId] 工作区未就绪，跳过会话 $sessionId")
            return null
        }
        val base = agentTurnRunner.createSessionEntity(workspace)
        val entity = base.copy(
            id = sessionId,
            title = "$channelLabel · $chatId",
            providerId = defaults.providerId.ifBlank { base.providerId.orEmpty() }.ifBlank { null },
            model = defaults.model.ifBlank { base.model.orEmpty() }.ifBlank { null }
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
        val received = materializeAttachments(cfg, attachments)
        val failureNotice = received.failedNames.takeIf { it.isNotEmpty() }?.let { names ->
            val data = kotlinx.serialization.json.JsonArray(names.map { kotlinx.serialization.json.JsonPrimitive(it) })
            imText("im_attachment_download_failed", data.toString().replace("<", "\\u003c").replace(">", "\\u003e"))
        }
        if (failureNotice != null) sendNotice(cfg, chatId, failureNotice)
        if (text.isBlank() && received.inputs.isEmpty()) return
        val prompt = text.ifBlank { imText("im_attachment_only_hint") }
        var live: LiveMessage? = null
        var latest = ""
        agentTurnRunner.run(
            AgentTurnRequest(
                sessionId = sessionId,
                text = prompt,
                modelRequest = listOfNotNull(prompt, failureNotice?.let {
                    "<attachment_download_failures type=\"data\">$it</attachment_download_failures>"
                }).joinToString("\n\n"),
                inputAttachments = received.inputs,
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
                    live?.finish(errorText(shown)) ?: sendNotice(cfg, chatId, errorText(shown))
                }
                else -> Unit
            }
        }
    }

    private fun stripMention(text: String): String = text.trim()

    // 文案走 Context：编排层在多平台复用，语言跟随 App 当前设置
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
                "qq", "qq_napcat" -> "im_qq_service_title"
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
            try {
                if (id == null) {
                    messageId = channel.send(cfg, chatId, accumulated.trim())
                    lastEditAt = now
                } else if (now - lastEditAt >= EDIT_INTERVAL_MS) {
                    if (channel.edit(cfg, chatId, id, accumulated.trim())) lastEditAt = now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FileLogger.w(TAG, "[$channelId] 流式回写失败：${channel.redact(e.message.orEmpty(), cfg)}")
            }
        }

        suspend fun finish(text: String) {
            if (text.isNotBlank()) buffer = text
            val shown = buffer.trim()
            if (shown.isEmpty()) return
            val id = messageId
            try {
                if (id != null && channel.supportsEdit && channel.edit(cfg, chatId, id, shown)) return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                FileLogger.w(TAG, "[$channelId] 最终编辑失败：${channel.redact(e.message.orEmpty(), cfg)}")
            }
            sendNotice(cfg, chatId, shown)
        }
    }

    companion object {
        private const val TAG = "ImBotBridge"

        /** 单条消息的处理重试次数与退避基数；重试耗尽就推进游标，不让一条坏消息堵死队列。 */
        private const val MAX_DELIVERY_ATTEMPTS = 3
        private const val DELIVERY_RETRY_MS = 2_000L

        /** 去重集合上限，超过就清空（只影响极端情况下重拉时的重复投递）。 */
        private const val SEEN_UPDATE_LIMIT = 1_000
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
