package com.aharou.feature.im.domain

import com.aharou.core.util.FileLogger
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.URI
import java.util.Collections
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/** 钉钉开放平台返回的业务错误。 */
class DingTalkApiException(message: String) : RuntimeException(message)

/**
 * 钉钉接入配置。
 *
 * 只需要自建应用的 `clientId`（AppKey）+ `clientSecret`（AppSecret）+ 机器人编码，
 * 事件走 Stream 长连接，不用配公网回调地址。凭证由用户自填，App 不内置任何人的身份。
 */
data class DingTalkChannelConfig(
    override val enabled: Boolean = false,
    val clientId: String = "",
    val clientSecret: String = "",
    /** 机器人编码；控制台里通常与应用 AppKey 相同，留空时退回 [clientId]。 */
    val robotCode: String = "",
    override val proxy: String = ""
) : ImChannelConfig {

    override val ready: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    val effectiveRobotCode: String
        get() = robotCode.trim().ifBlank { clientId.trim() }

    companion object {
        /** Stream 网关与机器人发消息接口都在 api.dingtalk.com 下。 */
        const val API_BASE_URL = "https://api.dingtalk.com"

        /** 机器人消息回调的订阅主题。 */
        const val TOPIC_ROBOT = "/v1.0/im/bot/messages/get"
    }
}

/**
 * 钉钉通道（开放平台「企业内部应用 - 机器人」）。
 *
 * 与飞书/QQ 同构：**Stream 长连接**由客户端主动连出去，服务端不需要公网回调地址、
 * 也不用校验回调签名——这正是选 Stream 而不是 HTTP 回调的唯一理由（手机上根本没有公网入口）。
 *
 * 协议分四段，都以官方 Node SDK `open-dingtalk/dingtalk-stream-sdk-nodejs` 为准：
 * 1. `POST /v1.0/oauth2/accessToken` 用 appKey/appSecret 换 accessToken（发消息、拉凭证校验都靠它）；
 * 2. `POST /v1.0/gateway/connections/open` 换 `endpoint` + `ticket`，拼成 `wss://…?ticket=…`；
 * 3. 连上后服务端推 JSON 帧（`type` = SYSTEM / CALLBACK / EVENT），**CALLBACK 必须带 messageId 回 ack**；
 * 4. 发消息走机器人接口：单聊 `oToMessages/batchSend`，群聊 `groupMessages/send`。
 *
 * 只做文本：图片、文件、卡片、编辑消息都不支持，[capabilities] 全为 false，编排层据此降级。
 */
@Singleton
class DingTalkChannelClient @Inject constructor(
    private val baseClient: OkHttpClient
) : ImChannel {

    override val id: String = "dingtalk"

    override val capabilities = ImChannelCapabilities()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** Stream 推下来的待消费消息；[poll] 从这里取。 */
    private val inbox = Channel<ImUpdate>(Channel.UNLIMITED)

    /** 长连接与重连的运行域：整条连接生命周期跟着 [prepare] / [disconnect] 走。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var connectionJob: Job? = null
    @Volatile private var socket: WebSocket? = null

    /** 本地 updateId 计数器：平台不给递增序号，只给每条消息自己的 msgId。 */
    private val updateSeq = AtomicLong(0)

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenKey: String? = null
    @Volatile private var tokenExpireAt = 0L

    @Volatile private var failure: ImChannelDiagnostic? = null

    /** 最近一次失败的诊断，供前台服务与设置页展示（提示文案由界面按 stage 映射）。 */
    val lastFailure: ImChannelDiagnostic? get() = failure

    /**
     * ACK 之后仍可能重推：服务端 60 秒内没收到 ack 就重投同一条，
     * 网络抖动时同一 msgId 会到达两次，靠这张表去重。
     */
    private val recentMessageIds = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) =
            size > DEDUP_CAPACITY
    }

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg.requireDingTalk()
        try {
            // 主动换一次 token：凭证错在这里就暴露成明确的 401/业务码，
            // 而不是等到长连接层只看到一句握手失败。机器人信息接口钉钉没开放，
            // 身份就用机器人编码（设置页与通知栏显示的就是它）。
            accessToken(c)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordFailure(ImStage.CREDENTIAL, e, c)
            throw e
        }
        return ImBotIdentity(id = 0L, username = c.effectiveRobotCode)
    }

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) {
        val c = cfg.requireDingTalk()
        // 丢弃积压：连接重建时清空队列里还没消费的消息
        if (dropHistory) drainInbox()
        stopConnection()
        val firstOpen = CompletableDeferred<Unit>()
        connectionJob = scope.launch { runConnectionLoop(c, firstOpen) }
        // 第一次握手放在这里等结果：失败要让编排层当场看到（它会显示在服务状态里），
        // 之后的中途断线由连接循环自己按指数退避重连，不再打扰上层。
        withTimeoutOrNull(FIRST_CONNECT_WAIT_MS) { firstOpen.await() }
            ?: FileLogger.w(TAG, "首次建连 ${FIRST_CONNECT_WAIT_MS}ms 内未完成，后台继续重连")
    }

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        cfg.requireDingTalk()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var waitLogged = false
        while (System.currentTimeMillis() < deadline) {
            if (!waitLogged) {
                FileLogger.d(TAG, "等待 Stream 回调（${timeoutSeconds}s 超时，socket=${if (socket != null) "在线" else "离线"}）")
                waitLogged = true
            }
            // 分段等待：连接掉了要尽快发现，而不是一觉睡满整个超时
            val slice = minOf(HEALTH_CHECK_MS, deadline - System.currentTimeMillis())
            val first = withTimeoutOrNull(slice.coerceAtLeast(1L)) { inbox.receive() } ?: continue
            val out = mutableListOf(first)
            while (true) {
                out.add(inbox.tryReceive().getOrNull() ?: break)
            }
            FileLogger.d(TAG, "本轮取到 ${out.size} 条消息")
            return out
        }
        return emptyList()
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        val c = cfg.requireDingTalk()
        val target = resolveTarget(chatId)
        try {
            val token = accessToken(c)
            // 超长就分片发：直接截断会静默丢内容
            var lastId = ""
            TelegramBotClient.chunkText(text, MAX_TEXT).forEach { chunk ->
                val id = sendChunk(c, token, target, chunk)
                if (id.isNotBlank()) lastId = id
            }
            return lastId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            recordFailure(ImStage.SEND, e, c)
            throw e
        }
    }

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val secret = (cfg as? DingTalkChannelConfig)?.clientSecret.orEmpty()
        var masked = if (secret.isNotBlank()) text.replace(secret, "<secret>") else text
        accessToken?.takeIf { it.isNotBlank() }?.let { masked = masked.replace(it, "<token>") }
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    override fun disconnect() {
        stopConnection()
        accessToken = null
        tokenKey = null
        tokenExpireAt = 0L
        failure = null
        synchronized(recentMessageIds) { recentMessageIds.clear() }
        drainInbox()
    }

    // ── 连接管理 ──────────────────────────────────────────

    /** 停掉连接与重连循环；清空 socket 引用，让下一轮从零建连。 */
    private fun stopConnection() {
        connectionJob?.cancel()
        connectionJob = null
        val previous = socket
        socket = null
        previous?.cancel()
    }

    /** 断线重连：2s 起翻倍，封顶 60s；连上就回到 2s。 */
    private suspend fun runConnectionLoop(cfg: DingTalkChannelConfig, firstOpen: CompletableDeferred<Unit>) {
        var delayMs = RECONNECT_MIN_MS
        try {
            while (currentCoroutineContext().isActive) {
                try {
                    connectOnce(cfg) {
                        delayMs = RECONNECT_MIN_MS
                        if (!firstOpen.isCompleted) firstOpen.complete(Unit)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    recordFailure(ImStage.CONNECT, e, cfg)
                    if (!firstOpen.isCompleted) firstOpen.completeExceptionally(e)
                }
                if (!currentCoroutineContext().isActive) break
                FileLogger.i(TAG, "${delayMs}ms 后重连长连接")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
            }
        } finally {
            // 循环被取消时不能让 prepare 里的 await 挂死
            if (!firstOpen.isCompleted) {
                firstOpen.completeExceptionally(DingTalkApiException("连接流程已停止"))
            }
        }
    }

    /**
     * 一次完整的连接：换端点 → 建连 → 等它关闭。
     *
     * 返回条件只有两个：连上后连接被关闭，或本次建连失败（抛异常）。失败不在这里重试，
     * 交给 [runConnectionLoop] 统一退避，避免两层各退一次。
     */
    private suspend fun connectOnce(cfg: DingTalkChannelConfig, onOpen: () -> Unit) {
        val url = openGateway(cfg)
        val open = CompletableDeferred<Unit>()
        val closed = CompletableDeferred<Unit>()
        val webSocket = wsClientFor(cfg).newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    FileLogger.i(TAG, "Stream 已连接：HTTP ${response.code} protocol=${response.protocol}")
                    open.complete(Unit)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // 不能把异常漏给 OkHttp 的读线程：非 IOException 会直接杀掉读循环，
                    // 连接变成「看似在线、永远不再收帧」的僵死状态（而且不会回调 onFailure）。
                    runCatching { handleFrame(webSocket, text) }
                        .onFailure { e ->
                            FileLogger.w(TAG, "处理下行帧异常：${e.javaClass.simpleName}: ${e.message}")
                        }
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // 官方 SDK 按文本帧收；这里兜一层二进制帧（同一份 JSON）
                    runCatching { handleFrame(webSocket, String(bytes.toByteArray(), Charsets.UTF_8)) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    val http = response?.code
                    FileLogger.w(
                        TAG,
                        "Stream 断开：${t.javaClass.simpleName}: ${t.message}" + (http?.let { " http=$it" } ?: "")
                    )
                    open.completeExceptionally(t)
                    closed.complete(Unit)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    FileLogger.i(TAG, "Stream 正在关闭：code=$code reason=${reason.take(200)}")
                    // 走完关闭握手，否则 OkHttp 会等 60s 才判死
                    webSocket.close(NORMAL_CLOSE_CODE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    FileLogger.i(TAG, "Stream 已关闭：code=$code reason=${reason.take(200)}")
                    closed.complete(Unit)
                }
            }
        )
        socket = webSocket
        try {
            withTimeout(HANDSHAKE_TIMEOUT_MS) { open.await() }
            onOpen()
            closed.await()
        } catch (e: TimeoutCancellationException) {
            throw DingTalkApiException("Stream 握手超时（${HANDSHAKE_TIMEOUT_MS}ms）")
        } finally {
            if (socket === webSocket) socket = null
            runCatching { webSocket.cancel() }
        }
    }

    /**
     * 换长连接地址。
     *
     * 官方 SDK 在这步只用 body 里的 clientId/clientSecret 鉴权，**不带 access token**，
     * 这里照做；`Accept: application/json` 必须显式加，否则网关默认回 XML，解析不了。
     */
    private suspend fun openGateway(cfg: DingTalkChannelConfig): String {
        val body = buildJsonObject {
            put("clientId", cfg.clientId)
            put("clientSecret", cfg.clientSecret)
            put("ua", USER_AGENT)
            localIp()?.let { put("localIp", it) }
            put(
                "subscriptions",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "CALLBACK")
                            put("topic", DingTalkChannelConfig.TOPIC_ROBOT)
                        }
                    )
                }
            )
        }
        val root = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(DingTalkChannelConfig.API_BASE_URL + PATH_GATEWAY_OPEN)
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody(jsonMedia))
                .build()
            clientFor(cfg).newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw DingTalkApiException(redact("HTTP ${resp.code} ${raw.take(200)}", cfg))
                }
                json.parseToJsonElement(raw) as? JsonObject
                    ?: throw DingTalkApiException(redact("网关响应不是 JSON：${raw.take(200)}", cfg))
            }
        }
        val endpoint = root["endpoint"].asText()
        val ticket = root["ticket"].asText()
        if (endpoint.isBlank() || ticket.isBlank()) {
            throw DingTalkApiException("连接接口没有返回 endpoint/ticket：${root.toString().take(200)}")
        }
        // endpoint 自带 wss:// 前缀；有的环境已经带了查询串，这里只补 ticket。
        // ticket 原样拼（官方 SDK 就是直接拼）：它是签发了的一次性凭据，百分号编码会让网关对不上。
        val base = if (endpoint.startsWith("ws://") || endpoint.startsWith("wss://")) endpoint else "wss://$endpoint"
        val separator = if (base.contains('?')) "&" else "?"
        return "$base${separator}ticket=$ticket"
    }

    // ── 帧处理 ─────────────────────────────────────────────

    /**
     * 下行帧：`{ type, headers, data }`。`data` 是**字符串化的业务 JSON**，不是对象。
     *
     * SYSTEM 帧是连接自身的控制面（ping 要回、disconnect 要回完再关），CALLBACK 才带消息。
     */
    private fun handleFrame(webSocket: WebSocket, raw: String) {
        val frame = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (frame == null) {
            FileLogger.w(TAG, "无法解析的下行帧：${raw.take(200)}")
            return
        }
        val type = frame["type"].asText()
        val headers = frame["headers"] as? JsonObject
        when (type) {
            TYPE_SYSTEM -> handleSystemFrame(webSocket, headers, frame["data"])
            TYPE_CALLBACK -> {
                val messageId = headers?.get("messageId").asText()
                // 先 ack 再解析：服务端只认 messageId 对得上的 ack，60s 没等到就重投
                ackCallback(webSocket, messageId)
                val payload = frame["data"].asFrameData()
                val update = parseMessage(payload)
                if (update != null) {
                    if (!inbox.trySend(update).isSuccess) {
                        FileLogger.w(TAG, "消息队列已关闭，丢弃一条回调")
                    }
                }
            }
            TYPE_EVENT -> FileLogger.d(
                TAG,
                "收到 EVENT 帧（未订阅）：topic=${headers?.get("topic").asText()}"
            )
            else -> FileLogger.d(TAG, "忽略未知帧类型：$type")
        }
    }

    private fun handleSystemFrame(webSocket: WebSocket, headers: JsonObject?, data: JsonElement?) {
        val topic = headers?.get("topic").asText()
        when (topic) {
            "CONNECTED" -> FileLogger.i(TAG, "Stream 握手完成（CONNECTED）")
            "REGISTERED" -> FileLogger.i(TAG, "机器人回调已注册（REGISTERED）")
            "KEEPALIVE" -> FileLogger.d(TAG, "收到 KEEPALIVE")
            // 应用层 ping 不是 WebSocket 的 ping 帧：必须按同一份 headers/data 回一个 JSON，
            // 不回会被判连接僵死
            "ping" -> echoFrame(webSocket, headers, data)
            "disconnect" -> {
                FileLogger.w(TAG, "服务端要求断开（disconnect）")
                echoFrame(webSocket, headers, data)
                webSocket.close(NORMAL_CLOSE_CODE, "server disconnect")
            }
            else -> FileLogger.d(TAG, "未识别的 SYSTEM 帧：topic=$topic")
        }
    }

    /** SYSTEM 帧的回执：原样带回 headers 与 data，只补 code/message（与官方 SDK 的 JSON 形态一致）。 */
    private fun echoFrame(webSocket: WebSocket, headers: JsonObject?, data: JsonElement?) {
        val reply = buildJsonObject {
            put("code", ACK_CODE)
            put("headers", headers ?: buildJsonObject { })
            put("message", "OK")
            // 服务端没给 data 就不带这个字段：官方实现是把 undefined 序列化掉，发 null 是另一种形态
            if (data != null && data !is JsonNull) put("data", data)
        }
        if (!webSocket.send(reply.toString())) {
            FileLogger.w(TAG, "回执发送失败（连接已关闭）")
        }
    }

    /**
     * 回调 ack：`headers.messageId` **必须原样回**——服务端按 messageId 记「这条已确认」，
     * 回错 id 或干脆不回，同一条消息会在 60 秒后被反复重投。
     */
    private fun ackCallback(webSocket: WebSocket, messageId: String) {
        if (messageId.isBlank()) {
            FileLogger.w(TAG, "回调帧没有 messageId，不回 ack（等它重推）")
            return
        }
        val reply = buildJsonObject {
            put("code", ACK_CODE)
            put(
                "headers",
                buildJsonObject {
                    put("contentType", "application/json")
                    put("messageId", messageId)
                }
            )
            put("message", "OK")
            // 业务回执体与官方 SDK 的 socketCallBackResponse 一致：{"response":{"success":true}}
            put("data", buildJsonObject { put("response", buildJsonObject { put("success", true) }) }.toString())
        }
        if (!webSocket.send(reply.toString())) {
            FileLogger.w(TAG, "回 ack 失败：messageId=$messageId")
        }
    }

    /** 机器人回调 data → 内部模型。只认文本消息，其余记日志后跳过。 */
    private fun parseMessage(payload: String): ImUpdate.Message? {
        val msg = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            ?: return dropMessage("data 不是 JSON 对象：${payload.take(200)}")
        val msgType = msg["msgtype"].asText().lowercase()
        if (msgType != "text") {
            return dropMessage("暂不支持的消息类型 msgtype=$msgType")
        }
        val content = (msg["text"] as? JsonObject)?.get("content").asText().trim()
        if (content.isBlank()) return dropMessage("文本为空")
        val messageId = msg["msgId"].asText()
        if (messageId.isNotBlank() && isDuplicate(messageId)) {
            return dropMessage("服务端重推 msgId=$messageId")
        }
        // 企业内部应用里 senderStaffId 才是稳定的员工 id，senderId 在部分场景是加密串
        val senderId = msg["senderStaffId"].asText().ifBlank { msg["senderId"].asText() }
        if (senderId.isBlank()) return dropMessage("缺少发送者 id")
        val isGroup = msg["conversationType"].asText() == CONVERSATION_TYPE_GROUP
        val conversationId = msg["conversationId"].asText()
        if (isGroup && conversationId.isBlank()) return dropMessage("群消息缺少 conversationId")
        val senderName = msg["senderNick"].asText().ifBlank { senderId }
        // chatId 用前缀编码：发消息时必须在「群接口 / 单聊接口」之间二选一，
        // 而入站 id 本身不带类型。前缀同时让它与其它渠道的 chatId 不会互相撞。
        val chatId = if (isGroup) CHAT_GROUP_PREFIX + conversationId else CHAT_USER_PREFIX + senderId
        val chatTitle = if (isGroup) {
            msg["conversationTitle"].asText().ifBlank { conversationId }
        } else {
            senderName
        }
        val mentionsBot = if (isGroup) {
            (msg["isInAtList"] as? JsonPrimitive)?.booleanOrNull == true
        } else {
            // 私聊不存在「有没有 @」的问题，消息能到机器人就是冲着它来的
            true
        }
        FileLogger.d(
            TAG,
            "回调转消息：group=$isGroup chat=$chatId 文本长度=${content.length} mentions=$mentionsBot"
        )
        return ImUpdate.Message(
            ImInboundMessage(
                updateId = updateSeq.incrementAndGet(),
                chatId = chatId,
                senderId = senderId,
                senderName = senderName,
                chatTitle = chatTitle,
                text = content,
                isGroup = isGroup,
                messageId = messageId,
                replyToSenderId = null,
                mentionsBot = mentionsBot
            )
        )
    }

    private fun dropMessage(reason: String): ImUpdate.Message? {
        FileLogger.d(TAG, "丢弃回调：$reason")
        return null
    }

    /** ACK 后仍可能重推：同一 msgId 在 [DEDUP_WINDOW_MS] 内只处理一次。 */
    private fun isDuplicate(messageId: String): Boolean {
        if (messageId.isBlank()) return false
        val now = System.currentTimeMillis()
        synchronized(recentMessageIds) {
            val seenAt = recentMessageIds[messageId]
            if (seenAt != null && now - seenAt < DEDUP_WINDOW_MS) {
                recentMessageIds[messageId] = now
                return true
            }
            recentMessageIds[messageId] = now
            return false
        }
    }

    // ── 发送 ───────────────────────────────────────────────

    private class SendTarget(val path: String, val sessionId: String, val isGroup: Boolean)

    /**
     * 解析 chatId：`group:<openConversationId>` 走群接口，`user:<staffId>` 走单聊接口。
     * 不带前缀的裸 id 按群会话 id 的形态猜（钉钉群会话 id 通常以 `cid` 开头或以 `==` 结尾）。
     */
    private fun resolveTarget(chatId: String): SendTarget {
        val parts = chatId.split(":", limit = 2)
        if (parts.size == 2) {
            when (parts[0]) {
                "group" -> return SendTarget(PATH_GROUP_SEND, parts[1], true)
                "user", "c2c" -> return SendTarget(PATH_ONE_TO_ONE_SEND, parts[1], false)
            }
        }
        val looksLikeGroup = chatId.startsWith("cid") || chatId.endsWith("==")
        return SendTarget(
            if (looksLikeGroup) PATH_GROUP_SEND else PATH_ONE_TO_ONE_SEND,
            chatId,
            looksLikeGroup
        )
    }

    private suspend fun sendChunk(
        cfg: DingTalkChannelConfig,
        token: String,
        target: SendTarget,
        text: String
    ): String {
        val body = buildJsonObject {
            put("robotCode", cfg.effectiveRobotCode)
            put("msgKey", MSG_KEY_TEXT)
            // msgParam 是字符串化的消息体，不是嵌套对象——钉钉就是这么收的
            put("msgParam", buildJsonObject { put("content", text) }.toString())
            if (target.isGroup) {
                put("openConversationId", target.sessionId)
            } else {
                put("userIds", buildJsonArray { add(JsonPrimitive(target.sessionId)) })
            }
        }
        val root = request(cfg, target.path, body, token)
        // 机器人接口不返回消息 id，只给一个用于追踪的 processQueryKey
        return root["processQueryKey"].asText()
    }

    // ── HTTP ───────────────────────────────────────────────

    /** accessToken 有效期 7200 秒；提前 5 分钟换掉，避免临界时刻用旧 token 被拒。 */
    private suspend fun accessToken(cfg: DingTalkChannelConfig): String {
        val key = "${cfg.clientId}|${cfg.clientSecret}|${cfg.proxy}"
        val now = System.currentTimeMillis()
        accessToken?.let { if (tokenKey == key && now < tokenExpireAt) return it }
        val root = request(
            cfg,
            PATH_ACCESS_TOKEN,
            buildJsonObject {
                put("appKey", cfg.clientId)
                put("appSecret", cfg.clientSecret)
            },
            null
        )
        val value = root["accessToken"].asText()
        if (value.isBlank()) {
            throw DingTalkApiException(redact("获取 accessToken 失败：${root.toString().take(200)}", cfg))
        }
        val expireIn = (root["expireIn"] as? JsonPrimitive)?.longOrNull
            ?: (root["expiresIn"] as? JsonPrimitive)?.longOrNull
            ?: DEFAULT_TOKEN_TTL_SECONDS
        accessToken = value
        tokenKey = key
        tokenExpireAt = now + (expireIn - 300L).coerceAtLeast(60L) * 1000L
        return value
    }

    private suspend fun request(
        cfg: DingTalkChannelConfig,
        path: String,
        body: JsonObject,
        token: String?
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(DingTalkChannelConfig.API_BASE_URL + path)
            .header("Accept", "application/json")
            .post(body.toString().toRequestBody(jsonMedia))
        if (token != null) builder.header(ACCESS_TOKEN_HEADER, token)
        clientFor(cfg).newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            if (!resp.isSuccessful) {
                throw DingTalkApiException(redact("HTTP ${resp.code} ${raw.take(200)}", cfg))
            }
            root ?: throw DingTalkApiException(redact("响应不是 JSON：${raw.take(200)}", cfg))
            // 新网关接口的错是 {code, message}，旧版接口是 {errcode, errmsg}，两种都当失败
            val code = root["code"].asText()
            val errcode = (root["errcode"] as? JsonPrimitive)?.longOrNull
            if ((code.isNotBlank() && code != "0") || (errcode != null && errcode != 0L)) {
                val detail = root["message"].asText()
                    .ifBlank { root["errmsg"].asText() }
                    .ifBlank { "code=${code.ifBlank { errcode.toString() }}" }
                throw DingTalkApiException(redact(detail, cfg))
            }
            root
        }
    }

    private fun clientFor(cfg: DingTalkChannelConfig): OkHttpClient {
        val builder = baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        parseProxy(cfg.proxy)?.let { builder.proxy(it) }
        return builder.build()
    }

    /**
     * 长连接专用 client：WebSocket 不能继承 60s 读超时（空闲就会被本地掐断），
     * 心跳交给 OkHttp 的 ping 帧——服务端 8 秒内不回 pong 就判死并触发 onFailure，
     * 与官方 Node SDK（`socket.ping()` + `isAlive` 判定）是同一套语义。
     */
    private fun wsClientFor(cfg: DingTalkChannelConfig): OkHttpClient =
        clientFor(cfg).newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(HEARTBEAT_MS, TimeUnit.MILLISECONDS)
            .build()

    private fun parseProxy(raw: String): Proxy? {
        if (raw.isBlank()) return null
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val isSocks = uri.scheme?.lowercase()?.startsWith("socks") == true
        val port = if (uri.port > 0) uri.port else if (isSocks) 1080 else 8080
        return Proxy(if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    /** 本机内网地址；网关只把它当排障线索，拿不到就不传。 */
    private fun localIp(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces()).asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
            ?.hostAddress
    }.getOrNull()

    private fun recordFailure(stage: ImStage, error: Throwable, cfg: ImChannelConfig) {
        val d = ImChannelDiagnostic.of(id, stage, error) { redact(it, cfg) }
        failure = d
        FileLogger.w(TAG, "阶段 ${stage.name} 失败（参考号 ${d.referenceId}）：${d.reason}")
    }

    private fun drainInbox() {
        while (inbox.tryReceive().isSuccess) {
            // 清空即可
        }
    }

    private fun ImChannelConfig.requireDingTalk(): DingTalkChannelConfig =
        this as? DingTalkChannelConfig
            ?: error("钉钉通道收到了非钉钉配置：${this::class.simpleName}")

    /** `JsonElement?` 取字符串：非 primitive（对象/数组）与 null 一律当空串。 */
    private fun JsonElement?.asText(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 帧里的 `data` 有时是字符串、有时是对象，统一取成字符串。 */
    private fun JsonElement?.asFrameData(): String = when (this) {
        null, JsonNull -> ""
        is JsonPrimitive -> contentOrNull.orEmpty()
        else -> toString()
    }

    companion object {
        private const val TAG = "DingTalkChannelClient"

        private const val PATH_ACCESS_TOKEN = "/v1.0/oauth2/accessToken"
        private const val PATH_GATEWAY_OPEN = "/v1.0/gateway/connections/open"
        private const val PATH_ONE_TO_ONE_SEND = "/v1.0/robot/oToMessages/batchSend"
        private const val PATH_GROUP_SEND = "/v1.0/robot/groupMessages/send"

        private const val ACCESS_TOKEN_HEADER = "x-acs-dingtalk-access-token"
        private const val MSG_KEY_TEXT = "sampleText"
        private const val ACK_CODE = 200
        private const val NORMAL_CLOSE_CODE = 1000
        private const val USER_AGENT = "aharou-dingtalk/1.0"

        private const val TYPE_SYSTEM = "SYSTEM"
        private const val TYPE_CALLBACK = "CALLBACK"
        private const val TYPE_EVENT = "EVENT"

        /** 群聊的 conversationType；单聊是 "1"。 */
        private const val CONVERSATION_TYPE_GROUP = "2"

        private const val CHAT_GROUP_PREFIX = "group:"
        private const val CHAT_USER_PREFIX = "user:"

        /**
         * 单条文本上限。钉钉没公开明确字数上限，取 2000 保守值：
         * 宁可多分一条，也不要被平台按长度拒收。
         */
        const val MAX_TEXT = 2000

        /** 官方 SDK 的心跳间隔；服务端不回 pong 即判死并重连。 */
        private const val HEARTBEAT_MS = 8_000L

        private const val HANDSHAKE_TIMEOUT_MS = 15_000L
        private const val FIRST_CONNECT_WAIT_MS = 20_000L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L

        /** poll 里检查连接是否还活着的间隔。 */
        private const val HEALTH_CHECK_MS = 5_000L

        private const val DEFAULT_TOKEN_TTL_SECONDS = 7_200L
        private const val DEDUP_CAPACITY = 200
        private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
    }
}
