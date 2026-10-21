package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.data.repository.QqConfig
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** QQ 开放平台返回的业务错误。 */
class QqApiException(message: String, val code: Long = 0L) : RuntimeException(message)

/**
 * QQ 机器人通道（QQ 开放平台 / `q.qq.com`）。
 *
 * 与飞书同构：HTTP 拿 access_token，再走 **WebSocket Gateway** 收事件——同样是客户端主动连出去，
 * 不需要公网地址。区别在于 QQ 的鉴权与心跳是网关协议的一部分（op 码），
 * 必须在连接后立刻 Identify，否则会被 4014 踢掉。
 *
 * 只处理文本消息（C2C 私聊与群里 @ 机器人两种事件），其余事件类型直接忽略。
 */
@Singleton
class QqChannelClient @Inject constructor(
    private val baseClient: OkHttpClient,
    @param:ApplicationContext private val context: Context
) : ImChannel {

    override val id: String = "qq"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    private val inbox = Channel<ImUpdate>(Channel.UNLIMITED)

    @Volatile private var socket: WebSocket? = null
    @Volatile private var socketKey: String? = null
    @Volatile private var seq = 0L

    /** 本地 updateId 计数器：不能借用网关的 seq，否则心跳带上去的事件序号会被本地递增值污染。 */
    @Volatile private var localUpdateId = 0L
    @Volatile private var accessToken: String? = null
    @Volatile private var tokenKey: String? = null
    @Volatile private var tokenExpireAt = 0L

    /** 心跳协程：一个连接一个，收到 hello 后按服务端下发的间隔重建。 */
    private val wsScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var heartbeatJob: Job? = null

    /** 断线重连退避：onFailure/onClosed 写入下次可重连的时间点，由 [poll] 执行。 */
    @Volatile private var reconnectAt = 0L
    @Volatile private var reconnectDelayMs = RECONNECT_MIN_MS

    /**
     * 被动回复凭据：QQ 的主动消息有严格频次限制，回带入站 msg_id 才算「回话」。
     * 同一条入站消息最多回 5 次、5 分钟内有效，所以带 TTL 与次数上限。
     */
    @Volatile private var lastInbound: InboundRef? = null

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg.requireQq()
        // /users/@me 是官方接口，但只在 api.bot.qq.com 下可用；走旧域名会回「不支持的调用」。
        val token = accessToken(c)
        val root = runCatching { apiGet(c, token, "/users/@me") }.getOrNull()
        val name = root?.get("username")?.jsonPrimitive?.contentOrNull
            ?: root?.get("nick")?.jsonPrimitive?.contentOrNull
            ?: c.appId
        return ImBotIdentity(id = 0L, username = name)
    }

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) {
        val c = cfg.requireQq()
        if (dropHistory) {
            while (inbox.tryReceive().isSuccess) {
                // 清空积压
            }
        }
        connect(c)
    }

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        val c = cfg.requireQq()
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            // bridge 只在 identity 为空时调一次 prepare：长连接断了必须由这里重建，
            // 否则 socket 已经是 null，事件永远不会再到。
            if (socket == null) {
                val waitMs = reconnectAt - System.currentTimeMillis()
                if (waitMs > 0) {
                    delay(minOf(waitMs, (deadline - System.currentTimeMillis()).coerceAtLeast(1L)))
                    continue
                }
                FileLogger.i(TAG, "网关不在线，重新建连")
                connect(c)
            }
            // 分段等待：掉线后最多 HEALTH_CHECK_MS 就能发现并重连
            val slice = minOf(HEALTH_CHECK_MS, deadline - System.currentTimeMillis())
            val first = withTimeoutOrNull(slice.coerceAtLeast(1L)) { inbox.receive() } ?: continue
            val out = mutableListOf(first)
            while (true) {
                val next = inbox.tryReceive().getOrNull() ?: break
                out.add(next)
            }
            return out
        }
        return emptyList()
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        val c = cfg.requireQq()
        val token = accessToken(c)
        // chatId 编码了会话类型：c2c:<openid> / group:<group_openid>
        val (kind, target) = splitChatId(chatId)
        val path = when (kind) {
            "group" -> "/v2/groups/$target/messages"
            else -> "/v2/users/$target/messages"
        }
        // 超长就分片发：直接截断会静默丢内容
        val chunks = TelegramBotClient.chunkText(text, MAX_TEXT)
        var lastId = ""
        chunks.forEach { chunk ->
            val id = sendChunk(c, token, path, chatId, chunk)
            if (id.isNotBlank()) lastId = id
        }
        return lastId
    }

    /**
     * 优先被动回复（回带入站 msg_id），拿不到凭据或平台拒绝时退回主动消息。
     * 主动消息受平台频次限制，这通常就是「机器人不回话」的原因。
     */
    private suspend fun sendChunk(
        cfg: QqConfig,
        token: String,
        path: String,
        chatId: String,
        chunk: String,
    ): String {
        val ref = lastInbound?.takeIf { it.chatId == chatId && !it.expired() && it.remaining() > 0 }
        if (ref != null) {
            val msgSeq = ref.nextSeq()
            val body = buildJsonObject {
                put("content", chunk)
                put("msg_type", 0)
                put("msg_id", ref.msgId)
                put("msg_seq", msgSeq)
            }
            val id = runCatching { apiPost(cfg, token, path, body) }
                .getOrNull()?.get("id")?.jsonPrimitive?.contentOrNull.orEmpty()
            if (id.isNotBlank()) {
                FileLogger.i(TAG, "被动回复成功：chat=$chatId msg_seq=$msgSeq")
                return id
            }
            FileLogger.w(TAG, "被动回复被拒（msg_id 过期或已用完），改发主动消息：chat=$chatId")
        }
        val body = buildJsonObject {
            put("content", chunk)
            put("msg_type", 0)
            put("msg_id", "") // 主动消息：受平台频次限制
        }
        return apiPost(cfg, token, path, body)["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
    }

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val secret = (cfg as? QqConfig)?.appSecret.orEmpty()
        val masked = if (secret.isNotBlank()) text.replace(secret, "<secret>") else text
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    // ── WebSocket ──────────────────────────────────────────

    private suspend fun connect(cfg: QqConfig) {
        val key = "${cfg.appId}|${cfg.baseUrl}|${cfg.proxy}"
        if (socket != null && socketKey == key) return
        socket?.close(1000, null)
        socket = null
        stopHeartbeat()

        val token = accessToken(cfg)
        val root = apiGet(cfg, token, "/gateway")
        val url = root["url"]?.jsonPrimitive?.contentOrNull
            ?: throw QqApiException("网关接口没有返回 ws 地址")
        val host = runCatching { URI(url).host }.getOrNull().orEmpty()
        FileLogger.i(TAG, "准备连接网关：host=${host.ifBlank { "?" }}")

        val client = wsClientFor(cfg)
        socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                FileLogger.i(TAG, "网关已连接：HTTP ${response.code} protocol=${response.protocol}")
                // Identify：op=2；intents 取公域消息 + 群/C2C 事件所需的那几位
                val identify = buildJsonObject {
                    put("op", 2)
                    put("d", buildJsonObject {
                        put("token", "QQBot $token")
                        put("intents", INTENTS)
                        put("shard", kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive(0))
                            add(kotlinx.serialization.json.JsonPrimitive(1))
                        })
                    })
                }
                webSocket.send(identify.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // 异常不能漏给 OkHttp 的读线程：会直接杀掉读循环，连接变成「看似在线、不再收帧」
                runCatching { handleFrame(webSocket, text) }
                    .onFailure { e ->
                        FileLogger.w(TAG, "处理网关帧异常：${e.javaClass.simpleName}: ${e.message}")
                    }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val http = response?.code
                FileLogger.w(
                    TAG,
                    "网关连接失败：${t.javaClass.simpleName}: ${t.message}" +
                        (http?.let { " http=$it" } ?: "")
                )
                clearSocket(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                FileLogger.w(TAG, "网关连接已关闭：code=$code reason=${reason.take(200)}")
                clearSocket(webSocket)
            }
        })
        socketKey = key
    }

    /** 连接掉了：置空 socket、停心跳，并按退避安排重连（由 [poll] 驱动）。 */
    private fun clearSocket(webSocket: WebSocket) {
        if (socket !== webSocket) return
        socket = null
        stopHeartbeat()
        val delayMs = reconnectDelayMs
        reconnectAt = System.currentTimeMillis() + delayMs
        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(RECONNECT_MAX_MS)
        FileLogger.i(TAG, "${delayMs}ms 后重新建立网关连接")
    }

    /** 网关帧：op=0 事件、op=1 要求心跳、op=7 要求重连、op=9 会话失效、op=10 hello、op=11 心跳应答。 */
    private fun handleFrame(ws: WebSocket, raw: String) {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (root == null) {
            FileLogger.w(TAG, "网关帧不是 JSON 对象：${raw.take(200)}")
            return
        }
        when (root["op"]?.jsonPrimitive?.intOrNull) {
            10 -> {
                // hello 里的 heartbeat_interval 是毫秒，丢了它网关会在几十秒后静默丢弃连接
                val interval = root["d"]?.jsonObject
                    ?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull
                    ?.takeIf { it > 0 }
                    ?: DEFAULT_HEARTBEAT_MS
                startHeartbeat(ws, interval)
            }
            11 -> Unit
            1 -> sendHeartbeat(ws, "服务端要求")
            0 -> {
                val s = root["s"]?.jsonPrimitive?.longOrNull
                if (s != null) seq = s
                val type = root["t"]?.jsonPrimitive?.contentOrNull.orEmpty()
                FileLogger.i(TAG, "收到事件：t=$type seq=${s ?: seq}")
                parseEvent(root)?.let { inbox.trySend(it) }
            }
            7 -> {
                FileLogger.w(TAG, "网关要求重连（op=7），关闭当前连接")
                ws.close(1000, "reconnect")
                clearSocket(ws)
            }
            9 -> {
                FileLogger.w(TAG, "会话失效（op=9），关闭当前连接并重新 Identify")
                ws.close(1000, "invalid session")
                clearSocket(ws)
            }
            else -> FileLogger.d(TAG, "忽略网关帧 op=${root["op"]?.jsonPrimitive?.intOrNull}")
        }
    }

    /**
     * 按服务端下发的间隔定时发 op=1；心跳断了网关会回收连接，
     * 而客户端这边看不出任何异常（没日志、socket 也不置空），所以每次失败都要打 w。
     */
    private fun startHeartbeat(ws: WebSocket, intervalMs: Long) {
        stopHeartbeat()
        val interval = intervalMs.coerceAtLeast(MIN_HEARTBEAT_MS)
        // 只有收到 hello 才算真正握手成功（Identify 失败的服务端会先建立 WS 再踢）；
        // 在这里清退避，避免坏凭证变成每 2s 一次的建连风暴
        reconnectAt = 0L
        reconnectDelayMs = RECONNECT_MIN_MS
        FileLogger.i(TAG, "握手完成，心跳间隔 ${interval}ms")
        heartbeatJob = wsScope.launch {
            while (isActive) {
                delay(interval)
                if (socket !== ws) return@launch
                if (!sendHeartbeat(ws, "定时")) return@launch
            }
        }
    }

    private fun sendHeartbeat(ws: WebSocket, reason: String): Boolean {
        val payload = buildJsonObject { put("op", 1); put("d", seq) }.toString()
        val ok = runCatching { ws.send(payload) }.getOrDefault(false)
        if (ok) {
            FileLogger.i(TAG, "发送心跳（$reason）seq=$seq")
        } else {
            FileLogger.w(TAG, "心跳发送失败（$reason），连接已断开")
            stopHeartbeat()
        }
        return ok
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    /** 事件帧 → 内部模型。只认 C2C 私聊与群里 @ 机器人两种文本事件。 */
    private fun parseEvent(root: JsonObject): ImUpdate.Message? {
        val type = root["t"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val d = root["d"] as? JsonObject ?: return dropEvent("缺少 d 字段（t=$type）")
        val author = d["author"] as? JsonObject
        // 优先 openid：QQ 的 author.id 在部分场景下每次会话都不同，拿它当白名单键，
        // 表现就是「每次都要重新配对」。user_openid（C2C）/ member_openid（群）才是稳定的。
        val senderId = author?.get("user_openid")?.jsonPrimitive?.contentOrNull
            ?: author?.get("member_openid")?.jsonPrimitive?.contentOrNull
            ?: author?.get("id")?.jsonPrimitive?.contentOrNull
            ?: return dropEvent("缺少 author.openid（t=$type）")
        val text = d["content"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        // 附件：QQ 给的是直链 + 元信息（content_type / filename / size）
        val rawAttachments = (d["attachments"] as? kotlinx.serialization.json.JsonArray)?.size ?: 0
        val attachments = (d["attachments"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { att ->
                val url = att["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val contentType = att["content_type"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val kind = when {
                    contentType.startsWith("image/") -> ImAttachment.Kind.IMAGE
                    contentType.startsWith("audio/") -> ImAttachment.Kind.AUDIO
                    contentType.startsWith("video/") -> ImAttachment.Kind.VIDEO
                    else -> ImAttachment.Kind.FILE
                }
                ImAttachment(
                    kind = kind,
                    url = url,
                    fileName = att["filename"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    mimeType = contentType,
                    sizeBytes = att["size"]?.jsonPrimitive?.longOrNull ?: 0L
                )
            }
            .orEmpty()
        // 有附件字段却取不到直链时，这里能直接看出来（而不是只看到「空消息」）
        if (rawAttachments > 0) {
            FileLogger.i(TAG, "事件附件 $rawAttachments 个，可直接下载 ${attachments.size} 个（t=$type）")
        }
        // 纯图片/文件也是有效消息
        if (text.isBlank() && attachments.isEmpty()) {
            return dropEvent("非文本且无附件（t=$type）")
        }
        // 群消息带 group_openid，私聊带 user_openid；用它拼出可回发的 chatId
        val groupId = d["group_openid"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val userId = d["user_openid"]?.jsonPrimitive?.contentOrNull
            ?: author?.get("user_openid")?.jsonPrimitive?.contentOrNull
            ?: senderId
        val chatId = if (type.contains("GROUP") || groupId.isNotBlank()) "group:$groupId" else "c2c:$userId"
        // 记住这条入站消息：后续回话可以带 msg_id 走被动回复，不吃主动消息配额
        val inboundId = d["id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (inboundId.isNotBlank()) lastInbound = InboundRef(chatId, inboundId)
        return ImUpdate.Message(
            ImInboundMessage(
                updateId = ++localUpdateId,
                chatId = chatId,
                senderId = senderId,
                senderName = senderId,
                chatTitle = chatId,
                text = text,
                isGroup = chatId.startsWith("group:"),
                messageId = inboundId,
                replyToSenderId = null,
                // 群消息只会以 GROUP_AT_MESSAGE_CREATE 推给我们，能收到就是 @ 了
                mentionsBot = type.contains("GROUP"),
                attachments = attachments
            )
        )
    }

    private fun dropEvent(reason: String): ImUpdate.Message? {
        FileLogger.i(TAG, "丢弃事件：$reason")
        return null
    }

    /** 附件下载：QQ 给的是直链，直接 GET 就行。 */
    override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? {
        val c = cfg.requireQq()
        val url = attachment.url?.takeIf { it.isNotBlank() } ?: return null
        val target = File(
            context.cacheDir,
            "qq_${System.currentTimeMillis()}_${attachment.fileName.ifBlank { "file" }}"
        )
        val request = Request.Builder().url(url).get().build()
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

    private fun splitChatId(chatId: String): Pair<String, String> {
        val idx = chatId.indexOf(':')
        return if (idx <= 0) "c2c" to chatId else chatId.substring(0, idx) to chatId.substring(idx + 1)
    }

    /** 一条可被动回复的入站消息：msg_id 5 分钟内有效，同一条最多回 5 次。 */
    private class InboundRef(val chatId: String, val msgId: String) {
        private val startedAt = System.currentTimeMillis()
        private val used = AtomicInteger(0)

        fun expired(): Boolean = System.currentTimeMillis() - startedAt > PASSIVE_TTL_MS
        fun remaining(): Int = MAX_PASSIVE_REPLIES - used.get()
        fun nextSeq(): Int = used.incrementAndGet()
    }

    /**
     * 发媒体。
     *
     * QQ 的上传接口只支持 url / file_data(≤5MB) / 分片 upload_id，没有 multipart 文件字段，
     * 因此统一走官方分片上传：upload_prepare → 分片 PUT → upload_part_finish → files 合并，
     * 拿到 file_info 后再以 msg_type=7 发消息。
     */
    override suspend fun sendMedia(
        cfg: ImChannelConfig,
        chatId: String,
        file: File,
        kind: ImAttachment.Kind,
        caption: String
    ): String? {
        val c = cfg.requireQq()
        val token = runCatching { accessToken(c) }.getOrNull() ?: return null
        val (kindStr, target) = splitChatId(chatId)
        val scope = if (kindStr == "group") "groups" else "users"
        val fileType = when (kind) {
            ImAttachment.Kind.IMAGE -> 1
            ImAttachment.Kind.VIDEO -> 2
            ImAttachment.Kind.AUDIO -> 3
            ImAttachment.Kind.FILE -> 4
        }
        if (caption.isNotBlank()) {
            // 富媒体消息带正文时部分客户端不展示说明文字，单独发一条更稳
            runCatching { send(cfg, chatId, caption) }
        }
        return withContext(Dispatchers.IO) {
            runCatching {
                val fileInfo = uploadMedia(c, token, scope, target, file, fileType)
                val ref = lastInbound?.takeIf { it.chatId == chatId && !it.expired() && it.remaining() > 0 }
                val body = buildJsonObject {
                    if (ref != null) {
                        put("msg_id", ref.msgId)
                        put("msg_seq", ref.nextSeq())
                    }
                    put("msg_type", 7)
                    put("media", buildJsonObject { put("file_info", fileInfo) })
                }
                apiPost(c, token, "/v2/$scope/$target/messages", body)
                    .get("id")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }.onFailure { e ->
                if (e is kotlinx.coroutines.CancellationException) throw e
                FileLogger.w(TAG, "发送媒体失败：${redact(e.message.orEmpty(), c)}")
            }.getOrNull()
        }
    }

    /**
     * 分片上传，返回 file_info；小文件同样走这条（官方没有整文件 multipart 上传）。
     *
     * 平台对图片编码挑剔，同一张 jpg 可能返回 850019；此时先转 PNG 重试，
     * 仍不行就降级成普通文件——宁可发文件卡片，也不能静默发不出去。
     */
    private suspend fun uploadMedia(
        cfg: QqConfig,
        token: String,
        scope: String,
        target: String,
        file: File,
        fileType: Int
    ): String {
        return try {
            uploadMediaOnce(cfg, token, scope, target, file, fileType)
        } catch (e: QqApiException) {
            if (fileType != 1 || e.code != CODE_FORMAT_UNSUPPORTED) throw e
            val png = transcodeToPng(file)
            if (png != null) {
                val info = runCatching { uploadMediaOnce(cfg, token, scope, target, png, 1) }.getOrNull()
                png.delete()
                if (info != null) return info
            }
            FileLogger.w(TAG, "图片格式被平台拒绝（${e.message}），降级为文件发送：${file.name}")
            uploadMediaOnce(cfg, token, scope, target, file, 4)
        }
    }

    private suspend fun uploadMediaOnce(
        cfg: QqConfig,
        token: String,
        scope: String,
        target: String,
        file: File,
        fileType: Int
    ): String {
        val prepared = apiPost(
            cfg,
            token,
            "/v2/$scope/$target/upload_prepare",
            buildJsonObject {
                put("file_type", fileType)
                put("file_size", file.length().toString())
                put("file_name", file.name)
                put("md5", digestHex(file, "MD5"))
                put("sha1", digestHex(file, "SHA-1"))
                put("md5_10m", digestHex(file, "MD5", MD5_10M))
            }
        )
        val uploadId = prepared["upload_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (uploadId.isBlank()) throw QqApiException("预上传未返回 upload_id")
        val parts = prepared["parts"] as? kotlinx.serialization.json.JsonArray
            ?: throw QqApiException("预上传未返回分片列表")
        val defaultBlock = prepared["block_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        var offset = 0L
        parts.mapNotNull { it as? JsonObject }
            .sortedBy { it["index"]?.jsonPrimitive?.intOrNull ?: Int.MAX_VALUE }
            .forEach { part ->
                val index = part["index"]?.jsonPrimitive?.intOrNull ?: return@forEach
                val url = part["presigned_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (url.isBlank()) throw QqApiException("分片 $index 缺少预签名地址")
                val blockSize = part["block_size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: defaultBlock
                    ?: (file.length() - offset)
                val length = minOf(blockSize, file.length() - offset).toInt()
                if (length <= 0) return@forEach
                val chunkMd5 = putChunk(file, offset, length, url)
                apiPost(
                    cfg,
                    token,
                    "/v2/$scope/$target/upload_part_finish",
                    buildJsonObject {
                        put("upload_id", uploadId)
                        put("part_index", index)
                        put("block_size", blockSize.toString())
                        put("md5", chunkMd5)
                    }
                )
                offset += length
            }
        val merged = apiPost(
            cfg,
            token,
            "/v2/$scope/$target/files",
            buildJsonObject {
                put("file_type", fileType)
                put("file_name", file.name)
                put("upload_id", uploadId)
                put("srv_send_msg", false)
            }
        )
        return merged["file_info"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw QqApiException("上传合并未返回 file_info")
    }

    /** 把图片重新编码成 PNG：部分 jpg 的编码/EXIF 不被 QQ 接受，重编码后能正常当图片发。 */
    private fun transcodeToPng(file: File): File? = runCatching {
        val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val target = File(context.cacheDir, "qq_upload_${System.currentTimeMillis()}.png")
        target.outputStream().use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        target
    }.getOrNull()

    /** 把 [length] 字节从 [offset] 处 PUT 到预签名地址，返回该分片 MD5。 */
    private fun putChunk(file: File, offset: Long, length: Int, url: String): String {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        val request = Request.Builder()
            .url(url)
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        baseClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw QqApiException("分片上传失败：HTTP ${resp.code}")
        }
        return java.security.MessageDigest.getInstance("MD5").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** 计算文件摘要；[limit] 用于 md5_10m（前 [MD5_10M] 字节）。 */
    private fun digestHex(file: File, algorithm: String, limit: Long = Long.MAX_VALUE): String {
        val digest = java.security.MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            var remaining = limit
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read <= 0) break
                digest.update(buffer, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ── HTTP ───────────────────────────────────────────────

    private suspend fun accessToken(cfg: QqConfig): String {
        val key = "${cfg.appId}|${cfg.appSecret}"
        val now = System.currentTimeMillis()
        accessToken?.let { if (tokenKey == key && now < tokenExpireAt) return it }
        val root = request(
            cfg,
            null,
            "/app/getAppAccessToken",
            buildJsonObject {
                put("appId", cfg.appId)
                put("clientSecret", cfg.appSecret)
            },
            "POST"
        )
        val token = root["access_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (token.isBlank()) throw QqApiException("获取 access_token 失败")
        val expire = root["expires_in"]?.jsonPrimitive?.longOrNull ?: 7200L
        accessToken = token
        tokenKey = key
        tokenExpireAt = now + (expire - 300L).coerceAtLeast(60L) * 1000L
        return token
    }

    private suspend fun apiGet(cfg: QqConfig, token: String, path: String): JsonObject =
        request(cfg, token, path, null, "GET")

    private suspend fun apiPost(cfg: QqConfig, token: String?, path: String, body: JsonObject): JsonObject =
        request(cfg, token, path, body, "POST")

    private suspend fun request(
        cfg: QqConfig,
        token: String?,
        path: String,
        body: JsonObject?,
        method: String
    ): JsonObject = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(urlFor(cfg, path))
        if (token != null) builder.header("Authorization", "QQBot $token")
        if (method == "POST") {
            builder.post((body ?: JsonObject(emptyMap())).toString().toRequestBody(jsonMedia))
        } else {
            builder.get()
        }
        clientFor(cfg).newCall(builder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: throw QqApiException(redact("HTTP ${resp.code}", cfg))
            val code = root["code"]?.jsonPrimitive?.longOrNull ?: 0L
            if (code != 0L) {
                val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: "code=$code"
                throw QqApiException(redact(msg, cfg), code)
            }
            root
        }
    }

    private fun urlFor(cfg: QqConfig, path: String): String =
        cfg.baseUrl.trim().trimEnd('/').ifBlank { QqConfig.DEFAULT_BASE_URL } + path

    private fun clientFor(cfg: QqConfig): OkHttpClient {
        val builder = baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        parseProxy(cfg.proxy)?.let { builder.proxy(it) }
        return builder.build()
    }

    /**
     * 长连接专用 client：WebSocket 不能继承 60s 读超时，否则网关安静一分钟就被本地掐断，
     * 而且看不出任何异常（QQ 靠应用层 op=1 心跳，不用 OkHttp 的 ping 帧）。
     */
    private fun wsClientFor(cfg: QqConfig): OkHttpClient =
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

    private fun ImChannelConfig.requireQq(): QqConfig =
        this as? QqConfig ?: error("QQ 通道收到了非 QQ 配置：${this::class.simpleName}")

    companion object {
        private const val TAG = "QqChannelClient"

        const val MAX_TEXT = 2000

        /** 网关没下发 heartbeat_interval 时的兜底。 */
        private const val DEFAULT_HEARTBEAT_MS = 30_000L

        /** 间隔过小时兜底，避免心跳风暴。 */
        private const val MIN_HEARTBEAT_MS = 5_000L
        private const val RECONNECT_MIN_MS = 2_000L
        private const val RECONNECT_MAX_MS = 60_000L

        /** poll 里检查连接是否还活着的间隔。 */
        private const val HEALTH_CHECK_MS = 5_000L

        /** 被动回复凭据有效期与次数上限（平台限制）。 */
        private const val PASSIVE_TTL_MS = 5 * 60 * 1000L
        private const val MAX_PASSIVE_REPLIES = 5

        /** 富媒体预上传要求单独提供前 10002432 字节的 MD5。 */
        private const val MD5_10M = 10_002_432L

        /** 平台认为图片格式不支持时的错误码，可降级为普通文件。 */
        private const val CODE_FORMAT_UNSUPPORTED = 850019L

        /**
         * 订阅的 intents 位：只需要 GROUP_AND_C2C_EVENT（1<<25）。
         *
         * 官方文档：C2C_MESSAGE_CREATE、GROUP_AT_MESSAGE_CREATE、GROUP_MESSAGE_CREATE
         * 三者都归在这一位里，不需要再订频道（1<<30）或 Guilds（1<<0）。
         */
        const val INTENTS = 1 shl 25
    }
}
