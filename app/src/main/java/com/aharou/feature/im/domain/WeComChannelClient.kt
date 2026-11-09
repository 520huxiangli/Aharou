package com.aharou.feature.im.domain

import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** 企业微信长连接返回的业务错误（errcode 非 0、等不到回执等）。 */
class WeComApiException(message: String) : RuntimeException(message)

/**
 * 企业微信智能机器人（API 模式 → 长连接）配置。
 *
 * 凭证只有 BotID + Secret 两项，在管理后台「智能机器人 → API 模式 → 长连接」里取。
 * 这里的 Secret 是**长连接专用密钥**，与「设置接收消息回调地址」模式的 Token/EncodingAESKey 不是一回事，
 * 混用会连不上。
 */
data class WeComChannelConfig(
    override val enabled: Boolean = false,
    val botId: String = "",
    val secret: String = "",
    /** 可选代理，如 `http://127.0.0.1:7890`；空串表示走全局设置。 */
    override val proxy: String = ""
) : ImChannelConfig {

    override val ready: Boolean
        get() = botId.isNotBlank() && secret.isNotBlank()
}

/**
 * 企业微信智能机器人通道（WebSocket 长连接）。
 *
 * 与 Telegram/微信的**长轮询**不同：本渠道和飞书、QQ 一样是服务端主动推，
 * 连接由客户端主动外连（`wss://openws.work.weixin.qq.com`），所以手机上没有公网地址也能用。
 * [poll] 只负责从收到的队列里取，连接维护（认证、心跳、断线重连）都在本类内部完成。
 *
 * 协议要点（来自开源实现 xmanrui/dsh-im 的 `src/channels/wecom/` 与官方《智能机器人长连接》文档）：
 * - 建连后发 `aibot_subscribe`，body 里带 `bot_id` + `secret`；回执无 cmd，靠 req_id 前缀对上，`errcode=0` 才算成功；
 * - 心跳是应用层 JSON `{"cmd":"ping","headers":{"req_id":"ping_..."}}`，官方建议 30 秒一次；
 * - 消息由 `aibot_msg_callback` 推下来，body 里 `chattype` 为 `single`/`group`，单聊没有 `chatid`、要用 `from.userid`；
 * - 回复走同一条连接：要么用回调的 req_id 发 `aibot_respond_msg`，要么用 `aibot_send_msg` + `chatid` 主动推送。
 *   本类统一走后者——[ImChannel.send] 只拿到 chatId，拿不到那条回调的 req_id。
 *   官方限制：用户先给机器人发过消息，才能往该会话主动推送；单会话 30 条/分钟、1000 条/小时。
 *
 * 当前只做纯文本：图片/文件/视频要按消息里的 `aeskey` 做 AES-256-CBC 解密、发送要三步分片上传，
 * 那套链路稳定后再补，先不给自己留半成品。
 */
@Singleton
class WeComChannelClient @Inject constructor(
    private val baseClient: OkHttpClient
) : ImChannel {

    override val id: String = "wecom"

    override val capabilities = ImChannelCapabilities()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 长连接推下来的待消费消息；[poll] 从这里取（与飞书同一套「推转拉」）。 */
    private val inbox = Channel<ImUpdate>(Channel.UNLIMITED)

    @Volatile private var socketGeneration = Any()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketKey: String? = null
    @Volatile private var authed = false

    /**
     * 被服务端以「新连接接管」为由踢下线。
     *
     * 同一个机器人只允许一条长连接，新连接订阅成功就会踢掉旧的；这时再自动重连只会两台设备互踢，
     * 所以本机停在这里，由用户关掉再开启渠道来重连。
     */
    @Volatile private var kicked = false

    @Volatile private var reconnectAt = 0L
    @Volatile private var reconnectDelayMs = RECONNECT_MIN_MS
    @Volatile private var missedPong = 0

    private val connectMutex = Mutex()

    /** 正在等订阅回执的那个 Deferred；连接中途断掉时用它立刻把等待叫醒，不用干等 20 秒超时。 */
    @Volatile private var authWaiter: CompletableDeferred<Unit>? = null

    /** 心跳协程的运行域：一条连接一个心跳 Job，连接换了就取消重建。 */
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pingJob: Job? = null

    /** 已发出、等回执的请求。回执帧没有 cmd，只能靠 req_id 对上，所以得自己维护这张表。 */
    private val pendingAcks = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    /** chatId → chat_type（1 单聊 / 2 群聊）。主动推送必须带上，否则服务端会拿 userid 当群聊 chatid 解析。 */
    private val chatTypes = ConcurrentHashMap<String, Int>()

    /** 服务端可能重推同一条回调，按 msgid 去重。 */
    private val seenMsgIds = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > DEDUP_CAPACITY
    }

    /** 会话游标要单调递增（编排层按它判重与落盘），所以只增不减，并在同一毫秒内自增。 */
    private val updateClock = AtomicLong(0)

    @Volatile private var cachedClient: OkHttpClient? = null
    @Volatile private var cachedProxyKey: String? = null

    override fun disconnect() {
        socketGeneration = Any()
        val previous = socket
        socket = null
        socketKey = null
        authed = false
        kicked = false
        previous?.cancel()
        pingJob?.cancel()
        pingJob = null
        reconnectAt = 0L
        reconnectDelayMs = RECONNECT_MIN_MS
        missedPong = 0
        failPending("长连接已关闭")
        authWaiter?.let { if (!it.isCompleted) it.completeExceptionally(IOException("长连接已关闭")) }
        authWaiter = null
        chatTypes.clear()
        synchronized(seenMsgIds) { seenMsgIds.clear() }
        while (inbox.tryReceive().isSuccess) {
            // 清空即可
        }
    }

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg.requireWeCom()
        // 长连接协议只有订阅/收发/心跳，没有「查机器人资料」的接口，身份只能报 BotID
        if (!c.ready) throw WeComApiException("企业微信 BotID 或 Secret 未填写")
        return ImBotIdentity(id = 0L, username = c.botId)
    }

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) {
        val c = cfg.requireWeCom()
        if (dropHistory) {
            while (inbox.tryReceive().isSuccess) {
                // 丢弃积压：第一次连上时不要把服务端没消费完的历史灌给 agent
            }
        }
        connect(c)
    }

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        val c = cfg.requireWeCom()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (kicked) {
                // 抛出去让编排层把状态显示成错误，而不是静默假装还连着
                throw WeComApiException("另一个连接已接管该机器人，本机已停止重连；关闭再开启渠道可重试")
            }
            if (socket == null) {
                val waitMs = reconnectAt - System.currentTimeMillis()
                if (waitMs > 0) {
                    delay(minOf(waitMs, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)))
                    continue
                }
                FileLogger.i(TAG, "长连接不在线，重新建连")
                connect(c)
                continue
            }
            // 分段等待：掉线后最多 HEALTH_CHECK_MS 就能发现并重连，不会一直干等到本轮超时
            val slice = minOf(HEALTH_CHECK_MS, deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            val first = withTimeoutOrNull(slice) { inbox.receive() } ?: continue
            val out = mutableListOf(first)
            while (true) {
                out.add(inbox.tryReceive().getOrNull() ?: break)
            }
            FileLogger.d(TAG, "本轮取到 ${out.size} 条企业微信消息")
            return out
        }
        FileLogger.d(TAG, "本轮 ${timeoutSeconds}s 未收到企业微信消息（连接=${if (socket != null) "在线" else "离线"}）")
        return emptyList()
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        cfg.requireWeCom()
        // markdown 单条上限 20480 字节，按 3500 字切（汉字约 3 字节）留足余量
        val chunks = TelegramBotClient.chunkText(text, MAX_TEXT)
        var lastId = ""
        for (chunk in chunks) {
            val reqId = nextReqId(CMD_SEND_MSG)
            val body = buildJsonObject {
                put("chatid", chatId)
                put("chat_type", chatTypes[chatId] ?: CHAT_TYPE_SINGLE)
                put("msgtype", "markdown")
                put("markdown", buildJsonObject { put("content", chunk) })
            }
            val ack = request(reqId, CMD_SEND_MSG, body)
            // 回执里可能带 msgid；拿不到就返回空串，编排层不依赖它
            val id = (ack["body"] as? JsonObject)?.let { replyBody ->
                replyBody["msgid"]?.jsonPrimitive?.contentOrNull
                    ?: replyBody["message_id"]?.jsonPrimitive?.contentOrNull
            }.orEmpty()
            if (id.isNotBlank()) lastId = id
        }
        return lastId
    }

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val secret = (cfg as? WeComChannelConfig)?.secret.orEmpty()
        val masked = if (secret.isNotBlank()) text.replace(secret, "<secret>") else text
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    // ── 连接维护 ──────────────────────────────────────────

    /** 建立长连接并等到订阅回执。同一份凭证只连一次，凭证变了才重连。 */
    private suspend fun connect(cfg: WeComChannelConfig) {
        connectMutex.withLock {
            if (!cfg.ready) throw WeComApiException("企业微信 BotID 或 Secret 未填写")
            val key = "${cfg.botId}|${cfg.secret}|${cfg.proxy}"
            if (socket != null && socketKey == key) return
            val generation = Any().also { socketGeneration = it }
            socket?.cancel()
            socket = null
            socketKey = null
            authed = false
            kicked = false
            pingJob?.cancel()
            pingJob = null
            missedPong = 0
            failPending("长连接重建")

            val authSignal = CompletableDeferred<Unit>()
            authWaiter = authSignal
            val ws = wsClientFor(cfg).newWebSocket(
                Request.Builder().url(WS_URL).build(),
                Listener(generation, cfg.botId, cfg.secret, authSignal)
            )
            socket = ws
            socketKey = key
            // 日志里只留 BotID：Secret 一旦进日志就等于泄漏
            FileLogger.i(TAG, "正在连接企业微信长连接（botId=${cfg.botId}）")
            try {
                withTimeoutOrNull(CONNECT_TIMEOUT_MS) { authSignal.await() }
                    ?: throw WeComApiException("订阅企业微信长连接超时（${CONNECT_TIMEOUT_MS / 1000}s）")
                FileLogger.i(TAG, "企业微信长连接已认证（botId=${cfg.botId}）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (socket === ws) {
                    socket = null
                    socketKey = null
                    authed = false
                    ws.cancel()
                }
                throw e
            } finally {
                if (authWaiter === authSignal) authWaiter = null
            }
        }
    }

    private fun handleDrop(ws: WebSocket, reason: String) {
        if (socket !== ws) return
        FileLogger.w(TAG, "企业微信长连接断开：$reason")
        clearSocket(ws, scheduleReconnect = true)
        ws.cancel()
    }

    private fun clearSocket(ws: WebSocket, scheduleReconnect: Boolean) {
        if (socket !== ws) return
        socket = null
        socketKey = null
        authed = false
        pingJob?.cancel()
        pingJob = null
        failPending("长连接已断开，消息结果未知")
        authWaiter?.let { if (!it.isCompleted) it.completeExceptionally(IOException("长连接已断开")) }
        if (scheduleReconnect && !kicked) {
            val waitMs = reconnectDelayMs
            reconnectAt = System.currentTimeMillis() + waitMs
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
            FileLogger.i(TAG, "${waitMs}ms 后重连企业微信长连接")
        }
    }

    private fun startPingLoop(ws: WebSocket) {
        pingJob?.cancel()
        pingJob = wsScope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                if (socket !== ws || !authed) return@launch
                // 与官方 SDK 一致：先看上一轮有没有回执，连续两次没回就当连接已死
                if (missedPong >= MAX_MISSED_PONG) {
                    FileLogger.w(TAG, "连续 $missedPong 次心跳没有回执，判定连接已死")
                    ws.cancel()
                    return@launch
                }
                missedPong++
                val frame = buildJsonObject {
                    put("cmd", CMD_HEARTBEAT)
                    put("headers", buildJsonObject { put("req_id", nextReqId(CMD_HEARTBEAT)) })
                }
                if (!ws.send(frame.toString())) {
                    FileLogger.w(TAG, "心跳发送失败，连接已关闭")
                    return@launch
                }
            }
        }
    }

    // ── 帧处理 ────────────────────────────────────────────

    private fun handleFrame(frame: JsonObject, authSignal: CompletableDeferred<Unit>) {
        val cmd = frame["cmd"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val reqId = (frame["headers"] as? JsonObject)
            ?.get("req_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        when {
            cmd == CMD_MSG_CALLBACK -> parseInbound(frame)?.let { enqueue(it) }
            cmd == CMD_EVENT_CALLBACK -> handleEvent(frame)
            // 订阅回执、心跳回执、发送回执都没有 cmd，只能按 req_id 前缀分流
            cmd.isBlank() -> handleAck(frame, reqId, authSignal)
            else -> FileLogger.w(TAG, "未识别的帧 cmd=$cmd")
        }
    }

    private fun handleAck(frame: JsonObject, reqId: String, authSignal: CompletableDeferred<Unit>) {
        when {
            reqId.startsWith(CMD_SUBSCRIBE) -> onAuthAck(frame, authSignal)
            reqId.startsWith(CMD_HEARTBEAT) -> {
                if ((frame["errcode"]?.jsonPrimitive?.intOrNull ?: 0) == 0) {
                    missedPong = 0
                } else {
                    FileLogger.w(
                        TAG,
                        "心跳回执异常：${frame["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty().take(120)}"
                    )
                }
            }
            else -> {
                val waiter = pendingAcks[reqId]
                if (waiter != null) {
                    waiter.complete(frame)
                } else {
                    FileLogger.w(TAG, "收到没有对应请求的回执 req_id=$reqId")
                }
            }
        }
    }

    private fun onAuthAck(frame: JsonObject, authSignal: CompletableDeferred<Unit>) {
        val errcode = frame["errcode"]?.jsonPrimitive?.intOrNull ?: 0
        val errmsg = frame["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (errcode != 0) {
            FileLogger.w(TAG, "订阅认证失败 errcode=$errcode ${errmsg.take(120)}")
            if (!authSignal.isCompleted) {
                authSignal.completeExceptionally(
                    WeComApiException("订阅认证失败 $errcode：${errmsg.take(120)}")
                )
            }
            return
        }
        authed = true
        missedPong = 0
        reconnectAt = 0L
        reconnectDelayMs = RECONNECT_MIN_MS
        if (!authSignal.isCompleted) authSignal.complete(Unit)
    }

    private fun handleEvent(frame: JsonObject) {
        val body = frame["body"] as? JsonObject ?: return
        val eventtype = ((body["event"] as? JsonObject)?.get("eventtype"))
            ?.jsonPrimitive?.contentOrNull.orEmpty()
        if (eventtype == EVENT_DISCONNECTED) {
            // 同一机器人只允许一条长连接，新连接订阅成功就会踢掉旧的。
            // 这里不重连：两台设备会互相踢，日志里只剩无限重连，真需求应该走主备而不是双连。
            FileLogger.w(TAG, "服务端通知新连接已接管，本机停止重连")
            kicked = true
            clearSocket(socket ?: return, scheduleReconnect = false)
            return
        }
        // 进入会话、卡片点击、消息反馈这类事件目前不驱动 agent，只留一笔日志
        FileLogger.d(TAG, "忽略事件：${eventtype.ifBlank { "unknown" }}")
    }

    private fun parseInbound(frame: JsonObject): ImUpdate.Message? {
        val body = frame["body"] as? JsonObject ?: return null
        val msgid = body["msgid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (msgid.isBlank()) return null
        val msgtype = body["msgtype"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isGroup = body["chattype"]?.jsonPrimitive?.contentOrNull == "group"
        val senderId = (body["from"] as? JsonObject)
            ?.get("userid")?.jsonPrimitive?.contentOrNull.orEmpty()
        // 单聊没有 chatid，官方约定单聊的会话 id 就是发送者 userid
        val chatId = if (isGroup) {
            body["chatid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        } else {
            senderId
        }
        if (senderId.isBlank() || chatId.isBlank()) return null

        val text = contentText(body)
        if (text.isBlank()) {
            // 图文混排里只有图片、或纯图片消息时取不到文本；纯文本版先不收，日志留痕方便排查
            FileLogger.w(TAG, "暂不支持（或无文本）的消息类型：$msgtype（msgid=$msgid）")
            return null
        }
        // 群聊回调会把触发的 @机器人 原样留在正文开头，那是路由标记不是用户的话
        val pushed = if (isGroup) text.replace(GROUP_MENTION_PREFIX, "").trim() else text.trim()
        if (pushed.isBlank()) return null

        synchronized(seenMsgIds) {
            if (seenMsgIds.containsKey(msgid)) {
                FileLogger.d(TAG, "重复推送，跳过：msgid=$msgid")
                return null
            }
            seenMsgIds[msgid] = true
        }
        chatTypes[chatId] = if (isGroup) CHAT_TYPE_GROUP else CHAT_TYPE_SINGLE
        // 引用正文：平台已经在回调里带上了，不必再多打一次接口；取不到就留空
        val quoted = (body["quote"] as? JsonObject)?.let { contentText(it).trim() }

        return ImUpdate.Message(
            ImInboundMessage(
                updateId = nextUpdateId(),
                chatId = chatId,
                senderId = senderId,
                // 长连接协议只下发 userid，不带姓名
                senderName = senderId,
                chatTitle = chatId,
                text = pushed,
                isGroup = isGroup,
                messageId = msgid,
                replyToSenderId = null,
                // 群聊只有在 @机器人 时服务端才推回调，收到即视为提到了机器人
                mentionsBot = isGroup,
                replyToText = quoted?.takeIf { it.isNotBlank() }
            )
        )
    }

    /** 正文文本：消息本身和引用结构用的是同一套字段，抽出来给两处共用。 */
    private fun contentText(obj: JsonObject): String =
        when (obj["msgtype"]?.jsonPrimitive?.contentOrNull.orEmpty()) {
            MSG_TEXT -> (obj["text"] as? JsonObject)
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            MSG_VOICE -> (obj["voice"] as? JsonObject)
                ?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
            MSG_MIXED -> mixedText(obj)
            else -> ""
        }

    /** 图文混排的内容在 `mixed.msg_item[]` 里，不是直接铺在 body 上。 */
    private fun mixedText(obj: JsonObject): String {
        val items = (obj["mixed"] as? JsonObject)?.get("msg_item") as? JsonArray ?: return ""
        return items.mapNotNull { item ->
            val itemObj = item as? JsonObject ?: return@mapNotNull null
            if (itemObj["msgtype"]?.jsonPrimitive?.contentOrNull == MSG_TEXT) {
                (itemObj["text"] as? JsonObject)
                    ?.get("content")?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString("\n")
    }

    private fun enqueue(update: ImUpdate.Message) {
        if (inbox.trySend(update).isFailure) {
            FileLogger.w(TAG, "消息队列已关闭，丢弃一条企业微信消息")
        }
    }

    // ── 发送 ──────────────────────────────────────────────

    /** 发一帧并等回执；回执 errcode 非 0 时抛出去，由编排层决定要不要重发。 */
    private suspend fun request(reqId: String, cmd: String, body: JsonObject): JsonObject {
        val ws = socket ?: throw WeComApiException("企业微信长连接未建立，消息未发出")
        if (!authed) throw WeComApiException("企业微信长连接尚未认证完成，消息未发出")
        val ack = CompletableDeferred<JsonObject>()
        pendingAcks[reqId] = ack
        try {
            val frame = buildJsonObject {
                put("cmd", cmd)
                put("headers", buildJsonObject { put("req_id", reqId) })
                put("body", body)
            }
            if (!ws.send(frame.toString())) throw WeComApiException("长连接已断开，消息未发出")
            // 回执超时用 SocketTimeoutException：平台可能已经收下了，编排层会归成「结果未知」不再重发
            val resp = withTimeoutOrNull(ACK_TIMEOUT_MS) { ack.await() }
                ?: throw SocketTimeoutException("等待企业微信回执超时（${ACK_TIMEOUT_MS / 1000}s）")
            val errcode = resp["errcode"]?.jsonPrimitive?.intOrNull ?: 0
            if (errcode != 0) {
                val errmsg = resp["errmsg"]?.jsonPrimitive?.contentOrNull.orEmpty()
                throw WeComApiException("企业微信返回 $errcode：${errmsg.take(120)}")
            }
            return resp
        } finally {
            pendingAcks.remove(reqId)
        }
    }

    private fun failPending(reason: String) {
        if (pendingAcks.isEmpty()) return
        val snapshot = pendingAcks.entries.toList()
        pendingAcks.clear()
        snapshot.forEach { (_, waiter) ->
            // 帧已经发出去了才断的线，结果未知；用 IOException 让编排层不要去重发（否则对方会收到两条）
            if (!waiter.isCompleted) waiter.completeExceptionally(IOException(reason))
        }
    }

    /**
     * 按官方 SDK 的做法先去掉控制字符再解析：JSON.parse 前它就是这么做的，
     * 少了这一步，个别带控制字符的帧会整帧解析失败。
     */
    private fun sanitize(raw: String): String =
        if (raw.none { it.isControlNoise() }) raw else raw.filterNot { it.isControlNoise() }

    private fun Char.isControlNoise(): Boolean = code in 0..8 || code in 11..13 || code in 14..31

    private fun nextReqId(prefix: String): String =
        "${prefix}_${System.currentTimeMillis()}_${java.util.UUID.randomUUID().toString().take(8)}"

    private fun nextUpdateId(): Long {
        while (true) {
            val prev = updateClock.get()
            val next = maxOf(prev + 1, System.currentTimeMillis())
            if (updateClock.compareAndSet(prev, next)) return next
        }
    }

    // ── 网络层 ────────────────────────────────────────────

    /**
     * WebSocket 专用 client：不能继承默认的读超时，否则链路安静一会儿就被本地掐断，
     * 而且看不出任何异常（保活靠应用层 ping，不用 OkHttp 的 ping 帧）。
     * 按代理配置缓存，避免每次重连都新建一个 client 带着自己的 dispatcher。
     */
    private fun wsClientFor(cfg: WeComChannelConfig): OkHttpClient {
        val proxyKey = cfg.proxy.trim()
        cachedClient?.let { if (cachedProxyKey == proxyKey) return it }
        val builder = baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
        parseProxy(proxyKey)?.let { builder.proxy(it) }
        val built = builder.build()
        cachedClient = built
        cachedProxyKey = proxyKey
        return built
    }

    private fun parseProxy(raw: String): Proxy? {
        if (raw.isBlank()) return null
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val isSocks = uri.scheme?.lowercase()?.startsWith("socks") == true
        val port = if (uri.port > 0) uri.port else if (isSocks) 1080 else 8080
        return Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    private fun ImChannelConfig.requireWeCom(): WeComChannelConfig =
        this as? WeComChannelConfig
            ?: error("企业微信通道收到了非企业微信配置：${this::class.simpleName}")

    /**
     * 一条连接一个监听器。generation 用来识别「旧连接的回调」——重连后旧 socket 的
     * onFailure/onMessage 仍可能进来，不拦掉会把状态写乱。
     */
    private inner class Listener(
        private val generation: Any,
        private val botId: String,
        private val secret: String,
        private val authSignal: CompletableDeferred<Unit>
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socketGeneration !== generation) {
                webSocket.cancel()
                return
            }
            // 订阅帧里带着 Secret，绝不落日志（只记 BotID）
            val frame = buildJsonObject {
                put("cmd", CMD_SUBSCRIBE)
                put("headers", buildJsonObject { put("req_id", nextReqId(CMD_SUBSCRIBE)) })
                put("body", buildJsonObject {
                    put("bot_id", botId)
                    put("secret", secret)
                })
            }
            if (!webSocket.send(frame.toString())) {
                if (!authSignal.isCompleted) {
                    authSignal.completeExceptionally(WeComApiException("订阅帧发送失败"))
                }
                return
            }
            FileLogger.i(TAG, "WebSocket 已连接，已发送订阅请求（botId=$botId）")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onText(text)
        }

        /** 服务端不保证文本帧，二进制帧按 UTF-8 解，不然后面永远收不到消息。 */
        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            onText(bytes.utf8())
        }

        private fun onText(raw: String) {
            if (socketGeneration !== generation) return
            val text = sanitize(raw)
            val frame = runCatching {
                json.parseToJsonElement(text) as? JsonObject
            }.getOrNull()
            if (frame == null) {
                FileLogger.w(TAG, "收到无法解析的帧：${text.take(120)}")
                return
            }
            handleFrame(frame, authSignal)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            handleDrop(
                webSocket,
                "${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}" +
                    (response?.let { "（HTTP ${it.code}）" } ?: "")
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            handleDrop(webSocket, "closed code=$code ${reason.take(120)}")
        }
    }

    companion object {
        private const val TAG = "WeComChannelClient"

        /** 官方文档给出的固定地址；私有部署企业以管理端显示的为准，暂不做自定义。 */
        private const val WS_URL = "wss://openws.work.weixin.qq.com"

        private const val CMD_SUBSCRIBE = "aibot_subscribe"
        private const val CMD_HEARTBEAT = "ping"
        private const val CMD_SEND_MSG = "aibot_send_msg"
        private const val CMD_MSG_CALLBACK = "aibot_msg_callback"
        private const val CMD_EVENT_CALLBACK = "aibot_event_callback"
        private const val EVENT_DISCONNECTED = "disconnected_event"

        private const val MSG_TEXT = "text"
        private const val MSG_VOICE = "voice"
        private const val MSG_MIXED = "mixed"

        private const val CHAT_TYPE_SINGLE = 1
        private const val CHAT_TYPE_GROUP = 2

        /** markdown 单条上限 20480 字节，按字符切要留足中文的 3 字节余量。 */
        private const val MAX_TEXT = 3_500

        /** 官方建议 30 秒一次心跳；官方 SDK 也是这个值。 */
        private const val HEARTBEAT_MS = 30_000L

        /** 官方 SDK 的阈值：连续 2 次心跳没回执就判定连接已死。 */
        private const val MAX_MISSED_PONG = 2

        /** 官方 SDK 的回执超时是 5s，弱网下太紧，放宽到 15s（超时后不重发，交给编排层）。 */
        private const val ACK_TIMEOUT_MS = 15_000L

        /** dsh-im 的 connectTimeoutMs 是 20s，订阅超时按这个走。 */
        private const val CONNECT_TIMEOUT_MS = 20_000L

        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L

        /** poll 里检查连接是否还活着的间隔。 */
        private const val HEALTH_CHECK_MS = 5_000L

        private const val DEDUP_CAPACITY = 256

        /** 群聊正文开头的 `@机器人` 前缀（后面跟一个空白或直接结束）。 */
        private val GROUP_MENTION_PREFIX = Regex("^\\s*@\\S+(?:\\s+|$)")
    }
}
