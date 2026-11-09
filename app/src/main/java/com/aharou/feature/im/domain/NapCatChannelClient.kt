package com.aharou.feature.im.domain

import android.content.Context
import com.aharou.feature.im.data.repository.NapCatConfig
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.resumeWithException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class NapCatChannelClient @Inject constructor(
    private val baseClient: OkHttpClient,
    @param:ApplicationContext private val context: Context
) : ImChannel {
    override val id: String = "qq_napcat"

    override val capabilities = ImChannelCapabilities(sendMedia = true, downloadAttachment = true)
    private val json = Json { ignoreUnknownKeys = true }
    private val incoming = Channel<String>(Channel.UNLIMITED)
    private val sequence = AtomicLong(System.currentTimeMillis())
    private val sendLock = Mutex()
    private val sentMessages = LinkedHashSet<String>()
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation: Any = Any()
    @Volatile private var failure: String? = null
    @Volatile private var connecting = false

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val c = cfg as NapCatConfig
        return listOf(c.token, c.webUiToken).filter(String::isNotBlank).fold(text) { t, secret -> t.replace(secret, "***") }
    }

    override fun disconnect() {
        generation = Any()
        socket?.cancel()
        socket = null
        connecting = false
        failure = null
        while (incoming.tryReceive().isSuccess) { }
    }

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg as NapCatConfig
        val data = api(c, "get_login_info")
        check(data.string("user_id") == c.accountId) { "NapCat account does not match configured account" }
        return ImBotIdentity(c.accountId.toLong(), data.string("nickname").ifBlank { c.accountId })
    }

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) { connect(cfg as NapCatConfig) }

    private fun connect(c: NapCatConfig) {
        if (socket != null || connecting) return
        val url = c.websocketUrl.trimEnd('/')
        require(URI(url).scheme in setOf("ws", "wss")) { "Invalid OneBot WebSocket URL" }
        val request = Request.Builder().url(url).header("Authorization", "Bearer ${c.token}").build()
        val transport = client(c, url)
        val epoch = Any()
        generation = epoch
        connecting = true
        failure = null
        val ws = transport.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (generation !== epoch) { webSocket.cancel(); return }
                connecting = false
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation === epoch) incoming.trySend(text)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (generation !== epoch) return
                failure = redact("OneBot WebSocket disconnected (${t.javaClass.simpleName})${response?.code?.let { " HTTP $it" }.orEmpty()}: ${t.message.orEmpty()}", c)
                connecting = false
                socket = null
            }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (generation !== epoch) return
                failure = "OneBot WebSocket closing ($code)"
                connecting = false
                socket = null
                webSocket.close(code, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (generation !== epoch) return
                failure = "OneBot WebSocket closed ($code)"
                connecting = false
                socket = null
            }
        })
        if (generation === epoch && failure == null) socket = ws else ws.cancel()
    }

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        val c = cfg as NapCatConfig
        failure?.let { failure = null; throw IllegalStateException(it) }
        if (socket == null) connect(c)
        val end = System.currentTimeMillis() + timeoutSeconds * 1000L
        while (System.currentTimeMillis() < end) {
            failure?.let { failure = null; throw IllegalStateException(it) }
            val raw = withTimeoutOrNull(1000L) { incoming.receive() } ?: continue
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            val msg = parse(c, root) ?: continue
            return listOf(ImUpdate.Message(msg))
        }
        return emptyList()
    }

    private suspend fun parse(c: NapCatConfig, event: JsonObject): ImInboundMessage? {
        val post = event.string("post_type")
        if (post !in setOf("message", "message_sent", "notice") || event.string("self_id") != c.accountId) return null
        val notice = event.string("notice_type")
        if (post == "notice" && notice !in setOf("group_upload", "offline_file")) return null
        val senderId = event.string("user_id")
        if (senderId.isBlank()) return null
        val messageId = event.string("message_id")
        if (senderId == c.accountId) {
            if (!c.respondSelf || post == "notice" || messageId.isBlank()) return null
            val ours = sendLock.withLock { synchronized(sentMessages) { "${c.accountId}:$messageId" in sentMessages } }
            if (ours) return null
        }
        val groupId = event.string("group_id")
        val group = groupId.isNotBlank() && groupId != "0"
        val destination = if (!group && senderId == c.accountId) {
            event.string("target_id").takeIf { it.isNotBlank() && it != "0" } ?: return null
        } else senderId
        val chatId = "account:${c.accountId}:${if (group) "group:$groupId" else "private:$destination"}"
        val segments = (event["message"] as? JsonArray).orEmpty()
        val text = StringBuilder()
        val attachments = mutableListOf<ImAttachment>()
        var mentioned = false
        var replyAuthor: String? = null
        var replyText: String? = null
        for (segment in segments) {
            val s = segment as? JsonObject ?: continue
            val data = s["data"] as? JsonObject ?: continue
            when (s.string("type")) {
                "text" -> text.append(data.string("text"))
                "at" -> if (data.string("qq") == c.accountId) mentioned = true
                "reply" -> {
                    val reply = try { api(c, "get_msg", buildJsonObject { put("message_id", data.string("id")) }) }
                    catch (e: CancellationException) { throw e } catch (_: Throwable) { null }
                    replyAuthor = (reply?.get("sender") as? JsonObject)?.string("user_id")?.takeIf(String::isNotBlank)
                    // 优先用引用段自带的文本（部分 OneBot 实现把原文塞在 reply.data.text），
                    // 没有就用同一次 get_msg 拿回来的正文——两条路都不额外发请求
                    replyText = data.string("text").takeIf(String::isNotBlank) ?: reply?.let { quotedText(it) }
                }
                "image" -> attachments.add(ImAttachment(ImAttachment.Kind.IMAGE,
                    fileId = data.string("file"), url = data.string("url").ifBlank { null },
                    fileName = "image-${messageId.ifBlank { sequence.get().toString() }}.jpg", mimeType = "image/jpeg"))
                "file" -> attachments.add(fileAttachment(data, if (group) groupId else ""))
            }
        }
        if (post == "notice") (event["file"] as? JsonObject)?.let { attachments.add(fileAttachment(it, if (group) groupId else "")) }
        if (text.isBlank() && attachments.isEmpty()) return null
        val sender = event["sender"] as? JsonObject
        return ImInboundMessage(sequence.incrementAndGet(), chatId, senderId,
            sender?.let { it.string("card").ifBlank { it.string("nickname") } }.orEmpty(),
            if (group) groupId else senderId, text.toString(), group, messageId, replyAuthor, mentioned, attachments,
            replyToText = replyText)
    }

    /** 从 get_msg 的返回值里抖出被引用消息的正文（只要 text 段，别的一段没有可读文本）。 */
    private fun quotedText(message: JsonObject): String? = (message["message"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.filter { it.string("type") == "text" }
        ?.mapNotNull { (it["data"] as? JsonObject)?.string("text")?.takeIf(String::isNotBlank) }
        ?.joinToString("")
        ?.trim()
        ?.takeIf(String::isNotBlank)

    private fun fileAttachment(data: JsonObject, group: String): ImAttachment {
        val id = data.string("file_id").ifBlank { data.string("id").ifBlank { data.string("file") } }
        return ImAttachment(ImAttachment.Kind.FILE, fileId = "$group|$id",
            url = data.string("url").ifBlank { null }, fileName = data.string("name").ifBlank { data.string("file_name").ifBlank { "attachment" } },
            sizeBytes = data["size"]?.jsonPrimitive?.longOrNull ?: data["file_size"]?.jsonPrimitive?.longOrNull ?: 0)
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        val c = cfg as NapCatConfig
        var last = ""
        for (chunk in TelegramBotClient.chunkText(text, 4000)) {
            last = sendSegments(c, chatId, buildJsonArray { add(buildJsonObject {
                put("type", "text"); putJsonObject("data") { put("text", chunk) }
            }) })
        }
        return last
    }

    private suspend fun sendSegments(c: NapCatConfig, chatId: String, segments: JsonArray): String = sendLock.withLock {
        val (kind, target) = target(c, chatId)
        val result = api(c, "send_${kind}_msg", buildJsonObject {
            put(if (kind == "group") "group_id" else "user_id", target)
            put("message", segments)
        })
        val id = result.string("message_id")
        check(id.isNotBlank()) { "OneBot did not return message_id" }
        rememberSent(c.accountId, id)
        id
    }

    override suspend fun sendMedia(cfg: ImChannelConfig, chatId: String, file: File, kind: ImAttachment.Kind, caption: String): String {
        val c = cfg as NapCatConfig
        val transferId = UUID.randomUUID().toString()
        val transfer = File(context.filesDir, "aharou-global/napcat/transfers/$transferId/${file.name}")
        try {
            withContext(Dispatchers.IO) {
                check(transfer.parentFile?.mkdirs() == true) { "Cannot create NapCat transfer directory" }
                file.inputStream().use { input -> transfer.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } }
            }
            val source = URI("file", "", "/opt/aharou-napcat/transfers/$transferId/${file.name}", null).toASCIIString()
            val result = sendSegments(c, chatId, buildJsonArray {
                add(buildJsonObject {
                    put("type", if (kind == ImAttachment.Kind.IMAGE) "image" else "file")
                    putJsonObject("data") { put("file", source); put("name", file.name) }
                })
            })
            if (caption.isNotBlank()) send(c, chatId, caption)
            return result
        } finally {
            transfer.delete()
            transfer.parentFile?.delete()
        }
    }

    override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? {
        val c = cfg as NapCatConfig
        var url = attachment.url
        if (url.isNullOrBlank()) {
            val data = if (attachment.kind == ImAttachment.Kind.IMAGE) api(c, "get_image", buildJsonObject { put("file", attachment.fileId) })
            else {
                val group = attachment.fileId.substringBefore('|')
                val fileId = attachment.fileId.substringAfter('|')
                api(c, if (group.isBlank()) "get_private_file_url" else "get_group_file_url", buildJsonObject {
                    if (group.isNotBlank()) put("group_id", group)
                    put("file_id", fileId)
                })
            }
            url = data.string("url").ifBlank { data.string("file") }
        }
        val source = url ?: return null
        require(URI(source).scheme in setOf("http", "https")) { "OneBot attachment has no downloadable URL" }
        val file = File.createTempFile("napcat-", ".download", File(context.cacheDir, "napcat").apply { mkdirs() })
        return try {
            withContext(Dispatchers.IO) {
                request(client(c, source).newCall(Request.Builder().url(source).build())) { response ->
                    check(response.isSuccessful) { "Attachment HTTP ${response.code}" }
                    val body = response.body ?: error("Empty attachment body")
                    body.byteStream().use { input -> file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                    } }
                }
                currentCoroutineContext().ensureActive()
                file
            }
        } catch (e: Throwable) { file.delete(); throw e }
    }

    private fun rememberSent(accountId: String, id: String) = synchronized(sentMessages) {
        sentMessages.add("$accountId:$id")
        if (sentMessages.size > 4096) sentMessages.remove(sentMessages.first())
    }

    private fun target(c: NapCatConfig, chatId: String): Pair<String, String> {
        val parts = chatId.split(':')
        require(parts.size == 4 && parts[0] == "account" && parts[1] == c.accountId && parts[2] in setOf("private", "group") &&
            parts[3].isNotBlank() && parts[3].all(Char::isDigit)) { "Invalid account-scoped QQ chat" }
        return parts[2] to parts[3]
    }

    private fun client(c: NapCatConfig, url: String = c.baseUrl): OkHttpClient {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        val local = host == "localhost" || host == "::1" || host == "[::1]" || host.startsWith("127.")
        val builder = baseClient.newBuilder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            // 本机 QQ 运行环境不响应空载荷 Ping，避免被 OkHttp 心跳误断开；远程连接保持原行为。
            .callTimeout(90, TimeUnit.SECONDS).pingInterval(if (local) 0 else 20, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        if (local) builder.proxy(Proxy.NO_PROXY)
        else if (c.proxy.isNotBlank()) {
            val p = URI(c.proxy)
            require(p.scheme in setOf("http", "socks", "socks5") && !p.host.isNullOrBlank() && p.port > 0) { "Invalid proxy" }
            builder.proxy(Proxy(if (p.scheme == "http") Proxy.Type.HTTP else Proxy.Type.SOCKS, InetSocketAddress(p.host, p.port)))
        }
        return builder.build()
    }

    private suspend fun api(c: NapCatConfig, action: String, params: JsonObject = buildJsonObject {}): JsonObject {
        val data = apiElement(c, action, params)
        return data as? JsonObject ?: buildJsonObject {}
    }

    /** 同 [api]，但允许返回 JSON 数组（群列表、成员列表这类）。 */
    private suspend fun apiElement(c: NapCatConfig, action: String, params: JsonObject = buildJsonObject {}): JsonElement {
        require(URI(c.baseUrl).scheme in setOf("http", "https")) { "Invalid OneBot base URL" }
        val httpRequest = Request.Builder().url("${c.baseUrl.trimEnd('/')}/$action")
            .header("Authorization", "Bearer ${c.token}")
            .post(params.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return request(client(c).newCall(httpRequest)) { response ->
            check(response.isSuccessful) { "OneBot HTTP ${response.code}" }
            val root = json.parseToJsonElement(response.body?.string() ?: error("Empty OneBot response")).jsonObject
            check(root["retcode"]?.jsonPrimitive?.intOrNull == 0 && root.string("status") == "ok") {
                "OneBot action $action failed (${root["retcode"]?.jsonPrimitive?.intOrNull ?: -1})"
            }
            root["data"] ?: JsonNull
        }
    }

    /** 只读查询：群列表 / 群信息 / 群成员 / 好友。字段裁剪由调用方负责。 */
    suspend fun query(cfg: NapCatConfig, action: String, params: JsonObject = buildJsonObject {}): JsonElement {
        require(action in setOf("get_group_list", "get_group_info", "get_group_member_list", "get_friend_list")) {
            "Unsupported OneBot read action"
        }
        return apiElement(cfg, action, params)
    }

    private suspend fun <T> request(call: Call, decode: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    try {
                        val result = decode(response)
                        if (continuation.isActive) continuation.resumeWith(Result.success(result))
                    } catch (e: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
        })
    }

    private fun JsonObject.string(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
