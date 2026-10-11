package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.permission.PermissionChoice
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolPermissionManager
import com.aharou.feature.agent.domain.workflow.AgentEvent
import com.aharou.feature.im.data.repository.TelegramConfig
import com.aharou.feature.im.data.repository.TelegramSettingsRepository
import com.aharou.feature.im.domain.model.ImCallbackQuery
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import com.aharou.feature.im.domain.model.PairedChat
import com.aharou.feature.im.domain.model.TelegramState
import com.aharou.feature.im.domain.pairing.ImPairingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Telegram 机器人接入的编排中心：长轮询取更新 → 白名单/配对判定 → 映射到 Aharou 会话并跑一轮
 * agent → 把回复流式刷回聊天 → 审批卡片接线。
 *
 * 消息**不另起 agent 循环**：走 [AgentTurnRunner.run]，与聊天界面/语音通话同一入口，memory、工具、
 * 权限等全部一致。会话 id 固定为 `im-telegram-<chatId>`，一个聊天 ↔ 一个会话。
 */
@Singleton
class TelegramBotBridge @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val client: TelegramBotClient,
    private val settings: TelegramSettingsRepository,
    private val pairing: ImPairingStore,
    private val agentTurnRunner: AgentTurnRunner,
    private val sessionUseCase: SessionUseCase,
    private val toolPermissionManager: ToolPermissionManager
) {
    private val _state = MutableStateFlow<TelegramState>(TelegramState.Stopped)
    val state: StateFlow<TelegramState> = _state.asStateFlow()

    private val _pairedChats = MutableStateFlow<List<PairedChat>>(emptyList())
    val pairedChats: StateFlow<List<PairedChat>> = _pairedChats.asStateFlow()

    private var bridgeJob: Job? = null
    private var bridgeScope: CoroutineScope? = null

    /** 会话 id → 聊天 id，审批卡片据此知道该发到哪个聊天。 */
    private val routes = ConcurrentHashMap<String, String>()

    /** 已发过卡片的审批 id，避免同一请求重复弹卡。 */
    private val askedApprovals = ConcurrentHashMap.newKeySet<String>()

    /** 每个聊天的运行串行锁：同一聊天的消息按序跑，不并发两个 agent 轮次。 */
    private val chatLocks = ConcurrentHashMap<String, Mutex>()

    @Volatile private var botId = 0L
    @Volatile private var botUsername = ""
    private var offset = 0L

    /** 启动长轮询与审批观察。幂等：已在运行则忽略。 */
    fun start(parent: CoroutineScope) {
        if (bridgeJob?.isActive == true) return
        val job = SupervisorJob(parent.coroutineContext[Job])
        val scope = CoroutineScope(parent.coroutineContext + job)
        bridgeJob = job
        bridgeScope = scope
        _state.value = TelegramState.Connecting
        scope.launch { pollLoop() }
        scope.launch {
            toolPermissionManager.pendingRequest.collect { request ->
                request?.let {
                    askForApproval(it.id, it.sessionId, single = true, title = it.title, summary = it.summary)
                }
            }
        }
        scope.launch {
            toolPermissionManager.pendingBatch.collect { batch ->
                batch?.let {
                    val title = it.items.firstOrNull()?.title.orEmpty()
                    val summary = it.items.joinToString("\n") { item -> item.summary }.take(1500)
                    askForApproval(it.id, it.sessionId, single = false, title = title, summary = summary)
                }
            }
        }
        scope.launch { refreshPaired() }
    }

    /** 停止并清空路由；下次 start 重新建连。 */
    fun stop() {
        bridgeJob?.cancel()
        bridgeJob = null
        bridgeScope = null
        routes.clear()
        askedApprovals.clear()
        _state.value = TelegramState.Stopped
    }

    fun isRunning(): Boolean = bridgeJob?.isActive == true

    /** 已配对名单变化后刷新对外状态（设置界面 approve/deny 后调用）。 */
    suspend fun refreshPaired() {
        _pairedChats.value = runCatching { pairing.approved() }.getOrDefault(emptyList())
    }

    // ── 轮询 ────────────────────────────────────────────────

    private suspend fun pollLoop() {
        offset = runCatching { settings.lastUpdateId() }.getOrDefault(0L)
        var primed = offset > 0L
        var delayMs = RETRY_MIN_MS
        while (currentCoroutineContext().isActive) {
            val cfg = settings.current()
            if (!cfg.enabled || !cfg.ready) {
                _state.value = TelegramState.Stopped
                return
            }
            try {
                if (botId == 0L) {
                    val me = client.getMe(cfg)
                    botId = me.id
                    botUsername = me.username
                    // 首次运行清掉历史积压：机器人刚配好时不该回答昨天堆下的消息。
                    client.deleteWebhook(cfg, dropPending = !primed)
                    primed = true
                }
                if (_state.value !is TelegramState.Connected) {
                    _state.value = TelegramState.Connected(botUsername)
                }
                val updates = client.getUpdates(cfg, offset, TelegramBotClient.POLL_TIMEOUT_S)
                delayMs = RETRY_MIN_MS
                if (updates.isNotEmpty()) {
                    updates.forEach { update ->
                        offset = maxOf(offset, update.updateId + 1)
                        bridgeScope?.launch { handleUpdate(cfg, update) }
                    }
                    settings.setLastUpdateId(offset)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = client.redact(e.message ?: e.javaClass.simpleName, cfg)
                FileLogger.w(TAG, "长轮询失败：$message")
                _state.value = TelegramState.Error(message)
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RETRY_MAX_MS)
            }
        }
    }

    private suspend fun handleUpdate(cfg: TelegramConfig, update: ImUpdate) {
        try {
            when (update) {
                is ImUpdate.Callback -> handleCallback(cfg, update.callback)
                is ImUpdate.Message -> handleMessage(cfg, update.msg)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "处理更新失败：${client.redact(e.message.orEmpty(), cfg)}")
        }
    }

    private suspend fun handleMessage(cfg: TelegramConfig, msg: ImInboundMessage) {
        if (msg.text.isBlank()) return
        if (!pairing.isApproved(msg.senderId)) {
            // 群里未配对者静默忽略，不发码（否则机器人会把群刷爆）；私聊回一枚配对码，消息不进 agent。
            if (msg.isGroup) return
            val code = pairing.request(msg.senderId, msg.senderName, msg.chatId)
            refreshPaired()
            runCatching {
                client.sendMessage(cfg, msg.chatId, context.getString(R.string.im_telegram_pairing_prompt, code))
            }
            return
        }
        // 群里只有 @ 机器人（或回复机器人消息）才答，私聊一律答。
        if (msg.isGroup && !mentioned(msg)) return
        val text = stripMention(msg.text)
        if (text.isBlank()) return
        runCatching { pairing.touch(msg.senderId, msg.senderName, msg.chatId) }
        runTurn(cfg, msg.chatId, msg.chatTitle, text)
    }

    private fun mentioned(msg: ImInboundMessage): Boolean {
        if (botId != 0L && msg.replyToSenderId == botId.toString()) return true
        if (botUsername.isBlank()) return false
        return msg.text.contains("@$botUsername", ignoreCase = true)
    }

    private fun stripMention(text: String): String =
        (if (botUsername.isBlank()) text else text.replace("@$botUsername", "", ignoreCase = true)).trim()

    // ── 一轮 agent ──────────────────────────────────────────

    private suspend fun runTurn(cfg: TelegramConfig, chatId: String, chatTitle: String, text: String) {
        val lock = chatLocks.computeIfAbsent(chatId) { Mutex() }
        lock.withLock {
            val sessionId = ensureSession(chatId, chatTitle) ?: return
            routes[sessionId] = chatId
            streamTurn(cfg, chatId, sessionId, text)
        }
    }

    /** 取（必要时建）本聊天的固定会话。工作区未就绪则不跑，避免工具写到空路径。 */
    private suspend fun ensureSession(chatId: String, chatTitle: String): String? {
        val sessionId = sessionIdFor(chatId)
        if (sessionUseCase.getSessionById(sessionId) != null) return sessionId
        val workspace = agentTurnRunner.currentWorkspacePath()
        if (workspace.isBlank()) {
            FileLogger.w(TAG, "工作区未就绪，跳过 Telegram 会话 $sessionId")
            return null
        }
        val entity = agentTurnRunner.createSessionEntity(workspace).copy(
            id = sessionId,
            title = "Telegram · ${chatTitle.ifBlank { chatId }}"
        )
        sessionUseCase.upsertSession(entity)
        return sessionId
    }

    private suspend fun streamTurn(cfg: TelegramConfig, chatId: String, sessionId: String, text: String) {
        var live: LiveMessage? = null
        agentTurnRunner.run(
            AgentTurnRequest(sessionId = sessionId, text = text, skipTitleUpdate = true)
        ).collect { event ->
            when (event) {
                is AgentEvent.AssistantDelta -> if (event.accumulated.isNotBlank()) {
                    (live ?: LiveMessage(cfg, chatId).also { live = it }).onDelta(event.accumulated)
                }
                is AgentEvent.AssistantText -> {
                    (live ?: LiveMessage(cfg, chatId).also { live = it }).finish(event.content)
                    live = null
                }
                is AgentEvent.ToolCallStarted -> {
                    (live ?: LiveMessage(cfg, chatId).also { live = it })
                        .onStatus(context.getString(R.string.im_telegram_tool_running, event.toolName))
                }
                is AgentEvent.Failed -> {
                    // 已流出的部分文本留在原地（与聊天界面一致），另发一条错误说明。
                    live = null
                    runCatching {
                        client.sendMessage(cfg, chatId, context.getString(R.string.im_telegram_error, event.error.take(300)))
                    }
                }
                else -> Unit
            }
        }
        live?.let { it.finish(it.currentText()) }
    }

    // ── 审批 ────────────────────────────────────────────────

    private suspend fun askForApproval(
        id: String,
        sessionId: String,
        single: Boolean,
        title: String,
        summary: String
    ) {
        if (id.isBlank() || sessionId.isBlank()) return
        val chatId = routes[sessionId] ?: return
        if (!askedApprovals.add(id)) return
        val cfg = settings.current()
        if (!cfg.ready) return
        val body = buildString {
            if (title.isNotBlank()) append(title)
            if (summary.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(summary)
            }
        }.take(1500).ifBlank { context.getString(R.string.im_telegram_approval_default_title) }
        val text = context.getString(R.string.im_telegram_approval_prompt, body)
        runCatching { client.sendMessage(cfg, chatId, text, approvalKeyboard(single, id)) }
    }

    /** 只给「本次允许 / 拒绝」两个选项，永久授权不从这里下放。 */
    private fun approvalKeyboard(single: Boolean, id: String): JsonObject {
        val prefix = if (single) "a" else "b"
        return buildJsonObject {
            put("inline_keyboard", buildJsonArray {
                add(buildJsonArray {
                    add(buildJsonObject {
                        put("text", context.getString(R.string.im_telegram_approval_allow))
                        put("callback_data", "$prefix:y:$id")
                    })
                    add(buildJsonObject {
                        put("text", context.getString(R.string.im_telegram_approval_deny))
                        put("callback_data", "$prefix:n:$id")
                    })
                })
            })
        }
    }

    private suspend fun handleCallback(cfg: TelegramConfig, cb: ImCallbackQuery) {
        client.answerCallbackQuery(cfg, cb.id)
        if (cb.messageId.isNotBlank()) client.clearReplyMarkup(cfg, cb.chatId, cb.messageId)
        // 卡片只发给已配对聊天，但仍以点击者身份复核一次：审批不能被未配对者触发。
        if (!pairing.isApproved(cb.senderId)) return
        val parts = cb.data.split(":", limit = 3)
        if (parts.size < 3 || parts[2].isBlank()) return
        val choice = if (parts[1] == "y") PermissionChoice.ONCE else PermissionChoice.REJECT
        when (parts[0]) {
            "a" -> toolPermissionManager.resolve(parts[2], choice)
            "b" -> toolPermissionManager.resolveBatch(parts[2], choice)
            else -> return
        }
        val textRes = if (choice == PermissionChoice.ONCE) {
            R.string.im_telegram_approval_allowed
        } else {
            R.string.im_telegram_approval_denied
        }
        runCatching { client.sendMessage(cfg, cb.chatId, context.getString(textRes)) }
    }

    /** 一条正在流式刷新的回复：首次发送拿到 message_id，之后按 [EDIT_INTERVAL_MS] 节流原地编辑。 */
    private inner class LiveMessage(private val cfg: TelegramConfig, private val chatId: String) {
        private var messageId: String? = null
        private var buffer = ""
        private var lastEditAt = 0L

        fun currentText(): String = buffer

        suspend fun onDelta(text: String) {
            buffer = text
            push(force = false)
        }

        /** 工具运行时先占位显示状态行；已有正文时不覆盖。 */
        suspend fun onStatus(text: String) {
            if (buffer.isNotBlank()) return
            buffer = text
            push(force = false)
        }

        suspend fun finish(text: String) {
            if (text.isNotBlank()) buffer = text
            if (buffer.isBlank()) return
            push(force = true)
        }

        private suspend fun push(force: Boolean) {
            val shown = buffer.trim()
            if (shown.isEmpty()) return
            val chunks = TelegramBotClient.chunkText(shown)
            if (chunks.isEmpty()) return
            val now = System.currentTimeMillis()
            val id = messageId
            if (id == null) {
                messageId = client.sendMessage(cfg, chatId, shown)
                lastEditAt = now
            } else if (force || now - lastEditAt >= EDIT_INTERVAL_MS) {
                // 流式期间只刷首片；收尾时把余下分片补发出去。
                client.editMessageText(cfg, chatId, id, chunks.first())
                lastEditAt = now
                if (force) chunks.drop(1).forEach { client.sendMessage(cfg, chatId, it) }
            }
        }
    }

    companion object {
        private const val TAG = "TelegramBotBridge"
        private const val EDIT_INTERVAL_MS = 1000L
        private const val RETRY_MIN_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L

        /** 一个 Telegram 聊天固定映射到的 Aharou 会话 id。 */
        fun sessionIdFor(chatId: String): String = "im-telegram-$chatId"
    }
}
