package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.data.repository.FeishuConfig
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** 飞书开放平台返回的业务错误。 */
class FeishuApiException(message: String) : RuntimeException(message)

/**
 * 飞书通道。
 *
 * 与 Telegram/微信的**长轮询**不同：飞书走 **WebSocket 长连接**——先调连接接口拿到 ws 地址，
 * 之后事件由服务端主动推下来。[poll] 除了从收到的队列里取一批，还负责在长连接掉线时重建它
 * （服务端要求按 ClientConfig.PingInterval 定时发 CONTROL ping，否则连接会被回收）。
 *
 * 同样不需要公网地址、不需要自建服务器：飞书的长连接模式就是给客户端主动连出去的。
 *
 * 需要 `appId` + `appSecret` 换 `tenant_access_token` 才能发消息。
 */
@Singleton
class FeishuChannelClient @Inject constructor(
    private val baseClient: OkHttpClient,
    @param:ApplicationContext private val context: Context
) : ImChannel {

    override val id: String = "feishu"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** WebSocket 推下来的待消费消息；[poll] 从这里取。 */
    private val inbox = Channel<ImUpdate>(Channel.UNLIMITED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketKey: String? = null
    @Volatile private var seq = 0L
    @Volatile private var tenantToken: String? = null
    @Volatile private var tokenKey: String? = null
    @Volatile private var tokenExpireAt = 0L

    /** 心跳协程的运行域：每个连接一个心跳 Job，连接换了就取消重建。 */
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var pingJob: Job? = null

    /** 心跳间隔由服务端（endpoint 的 ClientConfig / pong 里的 ClientConfig）下发。 */
    @Volatile private var pingIntervalMs = DEFAULT_PING_INTERVAL_MS

    /** 断线重连退避：onFailure/onClosed 时写入下次可重连的时间点。 */
    @Volatile private var reconnectAt = 0L
    @Volatile private var reconnectDelayMs = RECONNECT_MIN_MS

    /** 分片缓存：飞书单条事件可能被拆成多帧（headers 里的 message_id / sum / seq）。 */
    private val fragments = ConcurrentHashMap<String, Fragment>()

    /** 最近处理过的 message_id：ACK 失败重推时去重。 */
    private val recentMessageIds = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) =
            size > DEDUP_CAPACITY
    }

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg.requireFeishu()
        // 拿不到机器人信息不该挡住长连接：真正的凭证错误会在 connect() 的 endpoint 请求上暴露
        val name = runCatching {
            val token = tenantAccessToken(c)
            val root = apiGet(c, token, "/open-apis/bot/v3/info")
            (root["bot"] as? JsonObject)
                ?.get("app_name")?.jsonPrimitive?.contentOrNull.orEmpty()
        }.onFailure { e ->
            FileLogger.w(TAG, "获取机器人信息失败，先用 appId 作为身份：${redact(e.message.orEmpty(), c)}")
        }.getOrDefault("")
        return ImBotIdentity(id = 0L, username = name.ifBlank { c.appId })
    }

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) {
        val c = cfg.requireFeishu()
        // 丢弃积压：连接重建时清空队列里还没消费的事件
        if (dropHistory) {
            while (inbox.tryReceive().isSuccess) {
                // 清空即可
            }
        }
        connect(c)
    }

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        val c = cfg.requireFeishu()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var waitLogged = false
        while (System.currentTimeMillis() < deadline) {
            // 长连接掉了（onFailure/onClosed 会把 socket 置空）：这里负责重连，
            // 否则 bridge 只在 identity 为空时调一次 prepare，断了就永远收不到事件。
            if (socket == null) {
                val waitMs = reconnectAt - System.currentTimeMillis()
                if (waitMs > 0) {
                    delay(minOf(waitMs, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)))
                    continue
                }
                FileLogger.i(TAG, "长连接不在线，重新建连")
                connect(c)
            }
            if (!waitLogged) {
                FileLogger.d(TAG, "等待事件推送（${timeoutSeconds}s 超时，socket=在线）")
                waitLogged = true
            }
            // 分段等待，掉线后最多 HEALTH_CHECK_MS 就能发现并重连
            val slice = minOf(HEALTH_CHECK_MS, deadline - System.currentTimeMillis())
            val first = withTimeoutOrNull(slice.coerceAtLeast(1L)) { inbox.receive() } ?: continue
            val out = mutableListOf(first)
            while (true) {
                val next = inbox.tryReceive().getOrNull() ?: break
                out.add(next)
            }
            FileLogger.d(TAG, "本轮取到 ${out.size} 条事件")
            return out
        }
        FileLogger.d(TAG, "本轮 ${timeoutSeconds}s 未收到飞书事件（socket=${if (socket != null) "在线" else "离线"}）")
        return emptyList()
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        val c = cfg.requireFeishu()
        val token = tenantAccessToken(c)
        // 超长就分片发：直接截断会静默丢内容
        val chunks = TelegramBotClient.chunkText(text, MAX_TEXT)
        var lastId = ""
        chunks.forEach { chunk ->
            val id = sendTextWithToken(c, token, chatId, chunk)
            if (id.isNotBlank()) lastId = id
        }
        return lastId
    }

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val secret = (cfg as? FeishuConfig)?.appSecret.orEmpty()
        val masked = if (secret.isNotBlank()) text.replace(secret, "<secret>") else text
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    // ── WebSocket ──────────────────────────────────────────

    /** 建立长连接。同一份凭证只连一次，凭证变了才重连。 */
    private suspend fun connect(cfg: FeishuConfig) {
        val key = "${cfg.appId}|${cfg.baseUrl}|${cfg.proxy}"
        if (socket != null && socketKey == key) return
        socket?.close(1000, null)
        socket = null
        pingJob?.cancel()
        pingJob = null
        fragments.clear()

        val token = tenantAccessToken(cfg)
        // 端点没有 /open-apis 前缀（官方 SDK 用的是 /callback/ws/endpoint）
        val config = apiPost(cfg, token, "/callback/ws/endpoint?locale=zh", buildJsonObject {
            put("AppID", cfg.appId)
            put("AppSecret", cfg.appSecret)
        })
        // 官方响应把地址包在 data 里：{ code, msg, data: { URL, ClientConfig: { PingInterval } } }
        val payload = config["data"] as? JsonObject ?: config
        val url = payload["URL"]?.jsonPrimitive?.contentOrNull
            ?: payload["url"]?.jsonPrimitive?.contentOrNull
            ?: throw FeishuApiException("连接接口没有返回 ws 地址：${config.toString().take(200)}")
        // 服务端靠 URL 里的 service_id 路由帧，必须带回每一帧里
        val serviceId = FeishuWsFrame.serviceIdFrom(url)
        (payload["ClientConfig"] as? JsonObject)
            ?.get("PingInterval")?.jsonPrimitive?.longOrNull
            ?.let { applyPingInterval(it) }
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        FileLogger.i(
            TAG,
            "准备建立长连接：host=${host.ifBlank { "?" }} service_id=$serviceId pingInterval=${pingIntervalMs}ms"
        )
        if (serviceId == 0) {
            FileLogger.w(TAG, "ws 地址里没有 service_id，服务端可能无法路由帧")
        }

        val client = wsClientFor(cfg)
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                FileLogger.i(
                    TAG,
                    "长连接已建立：host=${host.ifBlank { "?" }} HTTP ${response.code} " +
                        "protocol=${response.protocol} pingInterval=${pingIntervalMs}ms"
                )
                reconnectAt = 0L
                reconnectDelayMs = RECONNECT_MIN_MS
                // 握手后立刻发 CONTROL ping，服务端才会回 pong（pong 里带 PingInterval）
                webSocket.sendPing(serviceId, "首次")
                startPingLoop(webSocket, serviceId)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 飞书长连接不走文本帧，收到就记一笔
                FileLogger.i(TAG, "收到文本帧（${text.length} 字符），已忽略")
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 不能把异常漏给 OkHttp 的读线程：非 IOException 会直接杀掉读循环，
                // 连接变成“看似在线、永远不再收帧”的僵死状态（而且不会回调 onFailure）。
                runCatching { handleBinaryFrame(webSocket, bytes, serviceId) }
                    .onFailure { e ->
                        FileLogger.w(TAG, "处理二进制帧异常：${e.javaClass.simpleName}: ${e.message}")
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val http = response?.code
                val handshake = response?.headers?.get("Handshake-Status")
                val handshakeMsg = response?.headers?.get("Handshake-Msg")
                FileLogger.w(
                    TAG,
                    "飞书长连接断开：${t.javaClass.simpleName}: ${t.message}" +
                        (http?.let { " http=$it" } ?: "") +
                        (handshake?.let { " handshake=$it" } ?: "") +
                        (handshakeMsg?.let { " handshakeMsg=$it" } ?: "")
                )
                clearSocket(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                FileLogger.w(TAG, "飞书长连接已关闭：code=$code reason=${reason.take(200)}")
                clearSocket(webSocket)
            }
        })
        socketKey = key
    }

    private fun handleBinaryFrame(webSocket: WebSocket, bytes: ByteString, serviceId: Int) {
        val frame = runCatching { FeishuWsFrame.decode(bytes.toByteArray()) }
            .onFailure { e ->
                FileLogger.w(TAG, "帧解码失败（${bytes.size} 字节）：${e.javaClass.simpleName}: ${e.message}")
            }
            .getOrNull() ?: return
        FileLogger.i(
            TAG,
            "收到帧：${bytes.size}B method=${frame.method} type=${frame.headers["type"].orEmpty()} " +
                "seq=${frame.seqId} headers=${frame.headers.keys}"
        )
        if (frame.method == FeishuWsFrame.METHOD_DATA && frame.payload == null) {
            // 日志层会把长 hex/base64 串脱敏，只能把整帧落盘，靠 run-as 拉出来离线拆
            runCatching {
                File(context.filesDir, "feishu-frame.bin").writeBytes(bytes.toByteArray())
            }.onFailure { FileLogger.w(TAG, "保存飞书帧失败", it) }
            FileLogger.w(
                TAG,
                "事件帧 payload 为空，sum=${frame.headers["sum"]} seq=${frame.headers["seq"]} " +
                    "解码经过=${frame.visited} 字段序列=${FeishuWsFrame.describe(bytes.toByteArray())}"
            )
        }
        when (frame.method) {
            FeishuWsFrame.METHOD_CONTROL -> handleControlFrame(webSocket, frame, serviceId)
            FeishuWsFrame.METHOD_DATA -> handleDataFrame(webSocket, frame)
            else -> FileLogger.w(
                TAG,
                "忽略未知帧：size=${bytes.size} method=${frame.method} type=${frame.headers["type"]}"
            )
        }
    }

    /** 连接掉了：置空 socket、停心跳，并按退避安排重连（由 [poll] 驱动）。 */
    private fun clearSocket(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        pingJob?.cancel()
        pingJob = null
        val delayMs = reconnectDelayMs
        reconnectAt = System.currentTimeMillis() + delayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        FileLogger.i(TAG, "${delayMs}ms 后重新建立长连接")
    }

    /** 控制帧：pong 解析服务端下发的心跳间隔；**ping 必须回 pong**，否则服务端会判连接已死。 */
    private fun handleControlFrame(webSocket: WebSocket, frame: FeishuWsFrame.Frame, serviceId: Int) {
        when (frame.headers["type"]) {
            "pong" -> {
                val raw = frame.payload?.let { String(it, Charsets.UTF_8) }
                FileLogger.i(TAG, "收到 pong：${raw?.take(200).orEmpty()}")
                raw?.let { parseClientConfig(it) }
            }
            "ping" -> {
                FileLogger.i(TAG, "收到服务端 ping（seq=${frame.seqId}），回 pong")
                webSocket.sendPong(frame, serviceId)
            }
            else -> FileLogger.i(TAG, "未识别的控制帧：type=${frame.headers["type"]} seq=${frame.seqId}")
        }
    }

    /** pong 原样带回 ping 的 seq_id / log_id，service 用本地 service_id，payload 为空。 */
    private fun WebSocket.sendPong(received: FeishuWsFrame.Frame, serviceId: Int) {
        val pong = FeishuWsFrame.Frame(
            seqId = received.seqId,
            logId = received.logId,
            service = received.service.takeIf { it != 0 } ?: serviceId,
            method = FeishuWsFrame.METHOD_CONTROL,
            headers = mapOf("type" to "pong"),
        )
        val ok = runCatching { send(ByteString.of(*FeishuWsFrame.encode(pong))) }.getOrDefault(false)
        if (!ok) FileLogger.w(TAG, "回 pong 失败：seq=${received.seqId}")
    }

    private fun parseClientConfig(raw: String) {
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
        val conf = (root["ClientConfig"] as? JsonObject) ?: root
        conf["PingInterval"]?.jsonPrimitive?.longOrNull?.let { applyPingInterval(it) }
    }

    private fun applyPingInterval(seconds: Long) {
        val ms = (seconds * 1000L).coerceAtLeast(MIN_PING_INTERVAL_MS)
        if (ms == pingIntervalMs) return
        pingIntervalMs = ms
        FileLogger.i(TAG, "心跳间隔更新为 ${ms}ms（服务端下发 ${seconds}s）")
    }

    private fun startPingLoop(webSocket: WebSocket, serviceId: Int) {
        pingJob?.cancel()
        pingJob = wsScope.launch {
            while (isActive) {
                delay(pingIntervalMs)
                if (socket !== webSocket) return@launch
                if (!webSocket.sendPing(serviceId, "定时")) return@launch
            }
        }
    }

    private fun WebSocket.sendPing(serviceId: Int, reason: String): Boolean {
        // ping 帧的 seq_id / log_id 官方与竞品都固定为 0
        val frame = FeishuWsFrame.Frame(
            seqId = 0L,
            logId = 0L,
            service = serviceId,
            method = FeishuWsFrame.METHOD_CONTROL,
            headers = mapOf("type" to "ping"),
        )
        val ok = runCatching { send(ByteString.of(*FeishuWsFrame.encode(frame))) }.getOrDefault(false)
        if (ok) {
            FileLogger.i(TAG, "发送心跳（$reason）service=$serviceId")
        } else {
            FileLogger.w(TAG, "心跳发送失败，连接已关闭")
        }
        return ok
    }

    private fun handleDataFrame(webSocket: WebSocket, frame: FeishuWsFrame.Frame) {
        val startedAt = System.currentTimeMillis()
        val type = frame.headers["type"].orEmpty()
        val messageId = frame.headers["message_id"].orEmpty()
        if (type != "event") {
            FileLogger.i(TAG, "收到非事件数据帧：type=$type message_id=$messageId")
            return
        }
        // 与官方/竞品一致：没有 message_id 的帧不回 ACK（等它重推）
        if (messageId.isBlank()) {
            FileLogger.w(TAG, "事件帧没有 message_id、先不回 ACK：${frame.headers.keys}")
            return
        }
        val sum = frame.headers["sum"]?.toIntOrNull() ?: 1
        val index = frame.headers["seq"]?.toIntOrNull() ?: 0
        var payload = frame.payload
        if (sum > 1) {
            payload = combineFragments(messageId, sum, index, payload ?: return)
            if (payload == null) {
                FileLogger.d(TAG, "事件分片未收齐：message_id=$messageId ${index + 1}/$sum")
                return
            }
        }
        if (payload == null) {
            FileLogger.w(TAG, "事件帧没有 payload：message_id=$messageId")
            return
        }
        // 先 ACK 再解析：服务端要求 3 秒内确认，否则重推
        webSocket.sendAck(frame, System.currentTimeMillis() - startedAt)
        FileLogger.i(TAG, "收到事件帧：${payload.size} 字节 message_id=$messageId")
        val raw = String(payload, Charsets.UTF_8)
        runCatching { parseEvent(raw) }
            .onFailure { e ->
                FileLogger.w(TAG, "解析事件异常：${e.javaClass.simpleName}: ${e.message} payload=${raw.take(200)}")
            }
            .getOrNull()
            ?.let { inbox.trySend(it) }
    }

    /**
     * 回 ACK：**原样带回**收到帧的 seq_id / log_id / service / method / headers，
     * headers 追加 `biz_rt`（处理耗时 ms），payload 是 `{"code":200}`。
     * 格式对照 BotHub 的飞书实现与官方 Go SDK：自造 seq / 只写 type=ack / 回原 payload 都不被认。
     */
    private fun WebSocket.sendAck(received: FeishuWsFrame.Frame, elapsedMs: Long) {
        val headers = LinkedHashMap(received.headers)
        headers[HEADER_BIZ_RT] = elapsedMs.coerceAtLeast(0L).toString()
        val ack = FeishuWsFrame.Frame(
            seqId = received.seqId,
            logId = received.logId,
            service = received.service,
            method = received.method,
            headers = headers,
            payload = ACK_PAYLOAD.toByteArray(Charsets.UTF_8),
        )
        val ok = runCatching { send(ByteString.of(*FeishuWsFrame.encode(ack))) }.getOrDefault(false)
        if (!ok) {
            FileLogger.w(TAG, "回 ACK 失败：seq=${received.seqId} message_id=${received.headers["message_id"]}")
        }
    }

    /** 飞书事件帧 → 内部模型。只取消息事件，其余（卡片回调等）记日志后跳过。 */
    private fun parseEvent(raw: String): ImUpdate.Message? {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
            ?: return dropEvent("payload 不是 JSON 对象：${raw.take(200)}")
        val eventType = (root["header"] as? JsonObject)
            ?.get("event_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        val event = root["event"] as? JsonObject
            ?: return dropEvent("没有 event 字段（event_type=$eventType）")
        val message = event["message"] as? JsonObject
            ?: return dropEvent("没有 event.message（event_type=$eventType）")
        val senderObj = event["sender"] as? JsonObject
        val senderType = senderObj?.get("sender_type")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (senderType == "app" || senderType == "bot") {
            return dropEvent("机器人自己发的消息（sender_type=$senderType）")
        }
        val sender = senderObj?.get("sender_id") as? JsonObject
        val chatId = message["chat_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderId = sender?.get("open_id")?.jsonPrimitive?.contentOrNull.orEmpty()
        if (chatId.isBlank() || senderId.isBlank()) {
            return dropEvent("缺少 chat_id / sender.open_id（event_type=$eventType）")
        }
        val messageId = message["message_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (isDuplicate(messageId)) return dropEvent("重复推送 message_id=$messageId")
        val content = message["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val messageType = message["message_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // content 是 JSON 字符串：文本是 {"text":"..."}，图片是 {"image_key":"..."}，文件是 {"file_key":"..."}
        val contentObj = runCatching {
            json.parseToJsonElement(content) as? JsonObject
        }.getOrNull()
        // 群里 @机器人 会被替换成 @_user_N 占位符（部分场景是 <at ...></at>），交给 agent 前清掉
        val text = contentObj?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
            .replace(MENTION_PLACEHOLDER, "")
            .replace(AT_TAG_PATTERN, " ")
            .trim()
        val attachments = when (messageType) {
            "image" -> listOfNotNull(
                contentObj?.get("image_key")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let {
                        ImAttachment(
                            ImAttachment.Kind.IMAGE,
                            // 下载时两样都要，编在一起，省得再给模型加字段
                            fileId = "$messageId::$it",
                            mimeType = "image/png"
                        )
                    }
            )
            "file", "audio", "media" -> listOfNotNull(
                contentObj?.get("file_key")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?.let { key ->
                        ImAttachment(
                            kind = if (messageType == "audio") ImAttachment.Kind.AUDIO
                            else ImAttachment.Kind.FILE,
                            fileId = "$messageId::$key",
                            fileName = contentObj["file_name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        )
                    }
            )
            else -> emptyList()
        }
        // 纯图片/文件也是有效消息，不能只看文本
        if (text.isBlank() && attachments.isEmpty()) {
            return dropEvent("不支持的消息类型或不含内容（message_type=$messageType event_type=$eventType）")
        }
        FileLogger.d(
            TAG,
            "事件转消息：event_type=$eventType message_type=$messageType chat_id=$chatId " +
                "文本长度=${text.length} 附件=${attachments.size}"
        )
        return ImUpdate.Message(
            ImInboundMessage(
                updateId = ++seq,
                chatId = chatId,
                senderId = senderId,
                senderName = senderId,
                chatTitle = chatId,
                text = text,
                isGroup = (message["chat_type"]?.jsonPrimitive?.contentOrNull ?: "p2p") != "p2p",
                messageId = messageId,
                replyToSenderId = null,
                // 飞书的 @ 在 message.mentions 里，非空即表示这条点了机器人
                mentionsBot = (message["mentions"] as? JsonArray)?.isNotEmpty() == true,
                attachments = attachments
            )
        )
    }

    private fun dropEvent(reason: String): ImUpdate.Message? {
        FileLogger.d(TAG, "丢弃事件：$reason")
        return null
    }

    /** 分片重组：message_id 相同、seq 覆盖 0..sum-1 后按序拼接。 */
    private fun combineFragments(
        messageId: String,
        sum: Int,
        index: Int,
        data: ByteArray,
    ): ByteArray? {
        if (messageId.isBlank() || index < 0 || index >= sum) return null
        val now = System.currentTimeMillis()
        fragments.entries.removeAll { now - it.value.updatedAt > FRAGMENT_TTL_MS }
        val existing = fragments[messageId]
        if (existing == null || existing.sum != sum) {
            fragments[messageId] = Fragment(sum).also { it.parts[index] = data }
            return null
        }
        existing.parts[index] = data
        existing.updatedAt = now
        if (existing.parts.any { it == null }) return null
        fragments.remove(messageId)
        val out = ByteArrayOutputStream()
        existing.parts.forEach { out.write(it!!) }
        return out.toByteArray()
    }

    /** ACK 重推去重：同一 message_id 在 [DEDUP_WINDOW_MS] 内只处理一次。 */
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

    private class Fragment(val sum: Int) {
        val parts = arrayOfNulls<ByteArray>(sum)
        @Volatile var updatedAt = System.currentTimeMillis()
    }

    /**
     * 下载飞书附件。
     *
     * 飞书不给直链，要用 message_id + file_key 换资源：
     * `GET /open-apis/im/v1/messages/{message_id}/resources/{file_key}?type=image|file`。
     * 两者在解析时编成 `fileId = "<message_id>::<file_key>"`。
     */
    override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? {
        val c = cfg.requireFeishu()
        val parts = attachment.fileId.split("::", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        val (messageId, fileKey) = parts
        val type = if (attachment.kind == ImAttachment.Kind.IMAGE) "image" else "file"
        val token = runCatching { tenantAccessToken(c) }.getOrNull() ?: return null
        val path = "/open-apis/im/v1/messages/$messageId/resources/$fileKey?type=$type"
        val request = Request.Builder()
            .url(urlFor(c, path))
            .header("Authorization", "Bearer $token")
            .get()
            .build()
        val target = File(
            context.cacheDir,
            "feishu_${System.currentTimeMillis()}_${attachment.fileName.ifBlank { "file" }}"
        )
        return withContext(Dispatchers.IO) {
            runCatching {
                clientFor(c).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                    target
                }
            }.getOrNull()
        }
    }

    /**
     * 发媒体。飞书要两步：先把文件传成 image_key / file_key，再用它发一条消息。
     *
     * 图片走 `/open-apis/im/v1/images`（image_type=message），
     * 其余走 `/open-apis/im/v1/files`（file_type=stream），两者都是 multipart。
     */
    override suspend fun sendMedia(
        cfg: ImChannelConfig,
        chatId: String,
        file: File,
        kind: ImAttachment.Kind,
        caption: String
    ): String? {
        val c = cfg.requireFeishu()
        val token = runCatching { tenantAccessToken(c) }.getOrNull() ?: return null
        val isImage = kind == ImAttachment.Kind.IMAGE
        val mime = (if (isImage) "image/png" else "application/octet-stream").toMediaTypeOrNull()
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(if (isImage) "image" else "file", file.name, file.asRequestBody(mime))
            .apply {
                if (isImage) addFormDataPart("image_type", "message")
                else {
                    addFormDataPart("file_type", "stream")
                    addFormDataPart("file_name", file.name)
                }
            }
            .build()
        val uploadPath = if (isImage) "/open-apis/im/v1/images" else "/open-apis/im/v1/files"
        val uploadKey = if (isImage) "image_key" else "file_key"
        val uploadRoot = runCatching {
            val request = Request.Builder()
                .url(urlFor(c, uploadPath))
                .header("Authorization", "Bearer $token")
                .post(form)
                .build()
            clientFor(c).newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                json.parseToJsonElement(raw) as? JsonObject
            }
        }.getOrNull() ?: return null
        val key = (uploadRoot["data"] as? JsonObject)
            ?.get(uploadKey)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return null
        val msgType = if (isImage) "image" else "file"
        val content = buildJsonObject { put(uploadKey, key) }.toString()
        val body = buildJsonObject {
            put("receive_id", chatId)
            put("msg_type", msgType)
            put("content", content)
        }
        val root = runCatching {
            apiPost(c, token, "/open-apis/im/v1/messages?receive_id_type=chat_id", body)
        }.getOrNull() ?: return null
        return (root["data"] as? JsonObject)
            ?.get("message_id")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    // ── HTTP ───────────────────────────────────────────────

    private suspend fun tenantAccessToken(cfg: FeishuConfig): String {
        val key = "${cfg.appId}|${cfg.appSecret}"
        val now = System.currentTimeMillis()
        tenantToken?.let { if (tokenKey == key && now < tokenExpireAt) return it }
        val body = buildJsonObject {
            put("app_id", cfg.appId)
            put("app_secret", cfg.appSecret)
        }
        val root = apiPost(cfg, null, "/open-apis/auth/v3/tenant_access_token/internal", body)
        val token = root["tenant_access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) {
            val msg = root["msg"]?.jsonPrimitive?.contentOrNull ?: "获取 tenant_access_token 失败"
            throw FeishuApiException(redact(msg, cfg))
        }
        val expire = root["expire"]?.jsonPrimitive?.longOrNull ?: 7200L
        tenantToken = token
        tokenKey = key
        // 提前 5 分钟过期，避免临界时刻用旧 token 被拒
        tokenExpireAt = now + (expire - 300L).coerceAtLeast(60L) * 1000L
        return token
    }

    private suspend fun sendTextWithToken(cfg: FeishuConfig, token: String, chatId: String, text: String): String {
        val content = buildJsonObject { put("text", text) }.toString()
        val body = buildJsonObject {
            put("receive_id", chatId)
            put("msg_type", "text")
            put("content", content)
        }
        val root = apiPost(cfg, token, "/open-apis/im/v1/messages?receive_id_type=chat_id", body)
        return (root["data"] as? JsonObject)?.get("message_id")?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    private suspend fun apiGet(cfg: FeishuConfig, token: String, path: String): JsonObject =
        request(cfg, token, path, null, "GET")

    private suspend fun apiPost(cfg: FeishuConfig, token: String?, path: String, body: JsonObject): JsonObject =
        request(cfg, token, path, body, "POST")

    private suspend fun request(
        cfg: FeishuConfig,
        token: String?,
        path: String,
        body: JsonObject?,
        method: String
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(urlFor(cfg, path))
        if (token != null) builder.header("Authorization", "Bearer $token")
        if (method == "POST") {
            builder.post((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonMedia))
        } else {
            builder.get()
        }
        val response = clientFor(cfg).newCall(builder.build()).execute()
        response.use { resp ->
            val raw = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
                ?: throw FeishuApiException(redact("HTTP ${resp.code}", cfg))
            val code = root["code"]?.jsonPrimitive?.longOrNull ?: 0L
            if (code != 0L) {
                val msg = root["msg"]?.jsonPrimitive?.contentOrNull ?: "code=$code"
                throw FeishuApiException(redact(msg, cfg))
            }
            root
        }
    }

    private fun urlFor(cfg: FeishuConfig, path: String): String =
        cfg.baseUrl.trim().trimEnd('/').ifBlank { FeishuConfig.DEFAULT_BASE_URL } + path

    private fun clientFor(cfg: FeishuConfig): OkHttpClient {
        val builder = baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        parseProxy(cfg.proxy)?.let { builder.proxy(it) }
        return builder.build()
    }

    /**
     * 长连接专用 client：WebSocket 不能继承 60s 读超时（空闲就会被判超时断开），
     * 也不能用 OkHttp 层的 pingInterval（飞书靠应用层 ping/pong 保活）。
     */
    private fun wsClientFor(cfg: FeishuConfig): OkHttpClient =
        clientFor(cfg).newBuilder()
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(0, TimeUnit.SECONDS)
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

    private fun ImChannelConfig.requireFeishu(): FeishuConfig =
        this as? FeishuConfig ?: error("飞书通道收到了非飞书配置：${this::class.simpleName}")

    companion object {
        private const val TAG = "FeishuChannelClient"

        /** 飞书单条文本上限较大，保守取 4000 字符。 */
        const val MAX_TEXT = 4000

        /** 服务端没下发心跳间隔时的兜底（官方 SDK 默认 2 分钟）。 */
        private const val DEFAULT_PING_INTERVAL_MS = 120_000L

        /** 服务端下发的间隔过小时兜底，避免心跳风暴。 */
        private const val MIN_PING_INTERVAL_MS = 10_000L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L

        /** poll 里检查连接是否还活着的间隔。 */
        private const val HEALTH_CHECK_MS = 5_000L
        private const val FRAGMENT_TTL_MS = 5_000L
        private const val DEDUP_CAPACITY = 200
        private const val DEDUP_WINDOW_MS = 10 * 60 * 1000L
        private const val HEADER_BIZ_RT = "biz_rt"

        /** ACK 的响应体：只带业务码（BotHub 与官方 SDK 都用这个结构）。 */
        private const val ACK_PAYLOAD = """{"code":200}"""
        private val MENTION_PLACEHOLDER = Regex("@_user_\\d+")
        private val AT_TAG_PATTERN = Regex("<at\\b[^>]*>[\\s\\S]*?</at>", RegexOption.IGNORE_CASE)
    }
}
