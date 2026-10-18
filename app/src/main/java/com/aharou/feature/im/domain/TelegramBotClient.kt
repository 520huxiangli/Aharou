package com.aharou.feature.im.domain

import com.aharou.feature.im.data.repository.TelegramConfig
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImCallbackQuery
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import java.io.File
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Telegram Bot API 返回的业务错误（ok=false），message 为服务端 description 原文。 */
class TelegramApiException(message: String) : RuntimeException(message)

/**
 * Telegram Bot API 客户端：纯 HTTPS，不依赖任何 SDK。
 *
 * 走 [getUpdates] 长轮询（服务端 timeout 50s），因此**不需要公网地址、不需要自建服务器**。
 * 境内 api.telegram.org 不可直连时，由 [TelegramConfig.baseUrl] 指向自建反代，或用
 * [TelegramConfig.proxy] 挂代理（留空则复用全局代理设置 —— 全局 [OkHttpClient] 已挂 AppProxy）。
 *
 * 复用全局 OkHttpClient（`di/AgentModule.kt:provideOkHttpClient`）派生：readTimeout 必须大于
 * 长轮询持有时间，否则每 50s 就被读超时打断。
 */
@Singleton
class TelegramBotClient @Inject constructor(
    private val baseClient: OkHttpClient
) : ImChannel {

    override val id: String = "telegram"

    override val supportsButtons: Boolean get() = true
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var cachedClient: OkHttpClient? = null

    @Volatile private var cachedProxyKey: String? = null

    /** getMe。token 错误时抛 [TelegramApiException]。 */
    suspend fun getMe(cfg: TelegramConfig): ImBotIdentity {
        val result = call(cfg, "getMe", null).jsonObject
        return ImBotIdentity(
            id = result["id"]?.jsonPrimitive?.longOrNull ?: 0L,
            username = result["username"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    /**
     * 关闭可能存在的 webhook。同一 token 被 webhook 与 getUpdates 同时消费会报
     * `Conflict: terminated by other getUpdates request`。首次运行顺带清掉历史积压。
     */
    suspend fun deleteWebhook(cfg: TelegramConfig, dropPending: Boolean) {
        call(cfg, "deleteWebhook", buildJsonObject { put("drop_pending_updates", dropPending) })
    }

    /** 长轮询拉取更新。offset 语义同 Bot API：返回 update_id ≥ offset 的更新。 */
    suspend fun getUpdates(cfg: TelegramConfig, offset: Long, timeoutSeconds: Int): List<ImUpdate> {
        val params = buildJsonObject {
            put("offset", offset)
            put("timeout", timeoutSeconds)
            put("allowed_updates", buildJsonArray {
                add(JsonPrimitive("message"))
                add(JsonPrimitive("callback_query"))
            })
        }
        val result = call(cfg, "getUpdates", params)
        val array = (result as? JsonArray) ?: return emptyList()
        return array.mapNotNull { element -> (element as? JsonObject)?.let(::parseUpdate) }
    }

    /** 发送消息（自动按 4096 上限分片），返回最后一条的 message_id。 */
    suspend fun sendMessage(
        cfg: TelegramConfig,
        chatId: String,
        text: String,
        replyMarkup: JsonObject? = null
    ): String {
        val chunks = chunkText(text)
        if (chunks.isEmpty()) return ""
        var lastId = ""
        chunks.forEachIndexed { index, chunk ->
            val params = buildJsonObject {
                put("chat_id", chatId)
                put("text", chunk)
                if (replyMarkup != null && index == chunks.lastIndex) put("reply_markup", replyMarkup)
            }
            lastId = call(cfg, "sendMessage", params).jsonObject["message_id"]?.jsonPrimitive?.longOrNull?.toString().orEmpty()
        }
        return lastId
    }

    /** 原地编辑一条消息的文本；内容未变时 Telegram 会报 not modified，这里当作成功吞掉。 */
    suspend fun editMessageText(cfg: TelegramConfig, chatId: String, messageId: String, text: String) {
        val shown = text.trim().take(MAX_MESSAGE_LENGTH - 1)
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", shown.ifEmpty { "…" })
        }
        runCatching { call(cfg, "editMessageText", params) }
            .onFailure { if (it.message?.contains("not modified") != true) throw it }
    }

    /** 给按钮消转圈。 */
    suspend fun answerCallbackQuery(cfg: TelegramConfig, callbackId: String) {
        runCatching { call(cfg, "answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId) }) }
    }

    /** 清空某条消息的内联键盘（审批已决策后去掉按钮）。 */
    suspend fun clearReplyMarkup(cfg: TelegramConfig, chatId: String, messageId: String) {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("reply_markup", buildJsonObject { put("inline_keyboard", buildJsonArray { }) })
        }
        runCatching { call(cfg, "editMessageReplyMarkup", params) }
    }

    /** 错误信息脱敏：OkHttp/服务端可能把 URL（含 token）带进消息。 */
    override fun redact(text: String, cfg: ImChannelConfig): String {
        val token = (cfg as? TelegramConfig)?.botToken.orEmpty()
        val masked = if (token.isNotBlank()) text.replace(token, "<token>") else text
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    // ── 内部 ────────────────────────────────────────────────

    private suspend fun call(cfg: TelegramConfig, method: String, params: JsonObject?): JsonElement =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(urlFor(cfg, method))
                .post((params ?: JsonObject(emptyMap())).toString().toRequestBody(jsonMedia))
                .build()
            val (code, body) = execute(clientFor(cfg), request)
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw TelegramApiException("HTTP $code")
            if (root["ok"]?.jsonPrimitive?.booleanOrNull != true) {
                val description = root["description"]?.jsonPrimitive?.contentOrNull
                throw TelegramApiException(redact(description ?: "HTTP $code", cfg))
            }
            root["result"] ?: JsonNull
        }

    private fun urlFor(cfg: TelegramConfig, method: String): String {
        val base = cfg.baseUrl.trim().trimEnd('/').ifBlank { TelegramConfig.DEFAULT_BASE_URL }
        return "$base/bot${cfg.botToken}/$method"
    }

    /** 派生一个长轮询专用 client（readTimeout > 50s，代理变更时重建）。 */
    private fun clientFor(cfg: TelegramConfig): OkHttpClient {
        val proxyKey = cfg.proxy.trim()
        cachedClient?.let { if (cachedProxyKey == proxyKey) return it }
        val builder = baseClient.newBuilder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(POLL_TIMEOUT_S + 20L, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        parseProxy(proxyKey)?.let { builder.proxy(it) }
        val built = builder.build()
        cachedClient = built
        cachedProxyKey = proxyKey
        return built
    }

    /** 解析 `http://host:port` / `socks5://host:port` / `host:port`；无法解析返回 null（直连）。 */
    private fun parseProxy(raw: String): Proxy? {
        if (raw.isBlank()) return null
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else when (uri.scheme?.lowercase()) {
            "socks5", "socks", "socks5h" -> 1080
            else -> 8080
        }
        val type = if (uri.scheme?.lowercase()?.startsWith("socks") == true) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress(host, port))
    }

    /** 在 IO 线程执行并支持协程取消（取消时中止底层 Call，避免长轮询挂住服务停止）。 */
    /**
     * 发请求并拿原始响应。
     *
     * `suspendCancellableCoroutine` 不切线程，`call.execute()` 是阻塞调用，
     * 不显式放到 IO 线程会抛 NetworkOnMainThreadException。
     */
    private suspend fun execute(client: OkHttpClient, request: Request): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { cont ->
                val call = client.newCall(request)
                cont.invokeOnCancellation { runCatching { call.cancel() } }
                try {
                    call.execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (cont.isActive) cont.resume(response.code to body)
                    }
                } catch (t: Throwable) {
                    if (cont.isActive) cont.resumeWithException(t) else cont.cancel()
                }
            }
        }

    /**
     * 附件下载：Telegram 要两步——getFile 换 file_path，再从 file/bot<token>/<path> 取流。
     *
     * 落到系统临时目录（这个客户端拿不到 Context），由编排层搬进应用私有目录。
     */
    override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? {
        val c = cfg.asTelegram()
        val fileId = attachment.fileId.takeIf { it.isNotBlank() } ?: return null
        val meta = runCatching {
            call(c, "getFile", buildJsonObject { put("file_id", fileId) })
        }.getOrNull() ?: return null
        val path = ((meta as? JsonObject)?.get("result") as? JsonObject)
            ?.get("file_path")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return null
        val url = c.baseUrl.trimEnd('/') + "/file/bot${c.botToken}/" + path
        val target = File.createTempFile("tg_", "_" + (attachment.fileName.ifBlank { path.substringAfterLast('/') }))
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                clientFor(c).newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                    target
                }
            }.getOrNull()
        }
    }

    /** 发媒体：Telegram 用 multipart 上传，图片走 sendPhoto、其余走 sendDocument。 */
    override suspend fun sendMedia(
        cfg: ImChannelConfig,
        chatId: String,
        file: File,
        kind: ImAttachment.Kind,
        caption: String
    ): String? {
        val c = cfg.asTelegram()
        val isImage = kind == ImAttachment.Kind.IMAGE
        val method = if (isImage) "sendPhoto" else "sendDocument"
        val fieldName = if (isImage) "photo" else "document"
        val mime = when (kind) {
            ImAttachment.Kind.IMAGE -> "image/jpeg"
            ImAttachment.Kind.AUDIO -> "audio/mpeg"
            ImAttachment.Kind.VIDEO -> "video/mp4"
            ImAttachment.Kind.FILE -> "application/octet-stream"
        }.toMediaTypeOrNull()
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart(fieldName, file.name, file.asRequestBody(mime))
            .apply { if (caption.isNotBlank()) addFormDataPart("caption", caption.take(1000)) }
            .build()
        val url = c.baseUrl.trimEnd('/') + "/bot${c.botToken}/" + method
        val request = Request.Builder().url(url).post(form).build()
        val (code, raw) = execute(clientFor(c), request)
        if (code !in 200..299) return null
        return runCatching {
            val root = json.parseToJsonElement(raw) as? JsonObject
            (root?.get("result") as? JsonObject)?.get("message_id")
                ?.jsonPrimitive?.longOrNull?.toString()
        }.getOrNull()
    }

    private fun parseUpdate(obj: JsonObject): ImUpdate? {
        val updateId = obj["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        (obj["callback_query"] as? JsonObject)?.let { return ImUpdate.Callback(parseCallback(updateId, it)) }
        val message = obj["message"] as? JsonObject ?: return null
        return parseMessage(updateId, message)?.let(ImUpdate::Message)
    }

    private fun parseMessage(updateId: Long, message: JsonObject): ImInboundMessage? {
        val from = message["from"] as? JsonObject ?: return null
        if (from["is_bot"]?.jsonPrimitive?.booleanOrNull == true) return null
        val chat = message["chat"] as? JsonObject ?: return null
        val chatId = chat["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val senderId = from["id"]?.jsonPrimitive?.longOrNull?.toString() ?: return null
        val type = chat["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val isGroup = type == "group" || type == "supergroup"
        val text = message["text"]?.jsonPrimitive?.contentOrNull
            ?: message["caption"]?.jsonPrimitive?.contentOrNull
            ?: ""
        // photo 给一组尺寸，取最大那张（数组末位）；document 就是文件本身
        val attachments = buildList {
            (message["photo"] as? JsonArray)?.lastOrNull()?.let { it as? JsonObject }?.let { pic ->
                pic["file_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { fileId ->
                    add(
                        ImAttachment(
                            kind = ImAttachment.Kind.IMAGE,
                            fileId = fileId,
                            mimeType = "image/jpeg",
                            sizeBytes = pic["file_size"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    )
                }
            }
            (message["document"] as? JsonObject)?.let { doc ->
                doc["file_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { fileId ->
                    add(
                        ImAttachment(
                            kind = ImAttachment.Kind.FILE,
                            fileId = fileId,
                            fileName = doc["file_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            mimeType = doc["mime_type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            sizeBytes = doc["file_size"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    )
                }
            }
            (message["voice"] as? JsonObject)?.let { voice ->
                voice["file_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { fileId ->
                    add(
                        ImAttachment(
                            kind = ImAttachment.Kind.AUDIO,
                            fileId = fileId,
                            mimeType = voice["mime_type"]?.jsonPrimitive?.contentOrNull ?: "audio/ogg",
                            sizeBytes = voice["file_size"]?.jsonPrimitive?.longOrNull ?: 0L
                        )
                    )
                }
            }
        }
        // 纯图片/文件也是有效消息，不能只看文本
        if (text.isBlank() && attachments.isEmpty()) return null
        val name = listOfNotNull(
            from["first_name"]?.jsonPrimitive?.contentOrNull,
            from["last_name"]?.jsonPrimitive?.contentOrNull
        ).joinToString(" ").ifBlank { from["username"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val replyTo = (message["reply_to_message"] as? JsonObject)
            ?.get("from")?.let { it as? JsonObject }
            ?.get("id")?.jsonPrimitive?.longOrNull?.toString()
        return ImInboundMessage(
            updateId = updateId,
            chatId = chatId,
            senderId = senderId,
            senderName = name,
            chatTitle = chat["title"]?.jsonPrimitive?.contentOrNull ?: name,
            text = text,
            isGroup = isGroup,
            messageId = message["message_id"]?.jsonPrimitive?.longOrNull?.toString().orEmpty(),
            replyToSenderId = replyTo,
            attachments = attachments
        )
    }

    private fun parseCallback(updateId: Long, cb: JsonObject): ImCallbackQuery {
        val message = cb["message"] as? JsonObject
        val chatId = (message?.get("chat") as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull?.toString().orEmpty()
        return ImCallbackQuery(
            updateId = updateId,
            id = cb["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            chatId = chatId,
            senderId = (cb["from"] as? JsonObject)?.get("id")?.jsonPrimitive?.longOrNull?.toString().orEmpty(),
            messageId = message?.get("message_id")?.jsonPrimitive?.longOrNull?.toString().orEmpty(),
            data = cb["data"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    // ── ImChannel 实现：把渠道无关的调用映射到上面的具体方法 ────────────────

    /** Telegram 支持 editMessageText，流式过程中可以就地刷新。 */
    override val supportsEdit: Boolean get() = true

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity = getMe(cfg.asTelegram())

    override suspend fun prepare(cfg: ImChannelConfig, dropHistory: Boolean) =
        deleteWebhook(cfg.asTelegram(), dropPending = dropHistory)

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> =
        getUpdates(cfg.asTelegram(), cursor, timeoutSeconds)

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String =
        sendMessage(cfg.asTelegram(), chatId, text)

    override suspend fun edit(cfg: ImChannelConfig, chatId: String, messageId: String, text: String): Boolean {
        editMessageText(cfg.asTelegram(), chatId, messageId, text)
        return true
    }

    override suspend fun sendWithButtons(
        cfg: ImChannelConfig,
        chatId: String,
        text: String,
        buttons: List<List<Pair<String, String>>>
    ): String? = sendMessage(cfg.asTelegram(), chatId, text, inlineKeyboard(buttons))

    override suspend fun acknowledgeCallback(cfg: ImChannelConfig, callbackId: String) =
        answerCallbackQuery(cfg.asTelegram(), callbackId)

    override suspend fun clearMarkup(cfg: ImChannelConfig, chatId: String, messageId: String) =
        clearReplyMarkup(cfg.asTelegram(), chatId, messageId)

    private fun ImChannelConfig.asTelegram(): TelegramConfig =
        this as? TelegramConfig ?: error("Telegram 通道收到了非 Telegram 配置：${this::class.simpleName}")

    /** (文本, 回调数据) 二维表 → Telegram 的 inline_keyboard。 */
    private fun inlineKeyboard(buttons: List<List<Pair<String, String>>>): JsonObject = buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            buttons.forEach { row ->
                add(buildJsonArray {
                    row.forEach { (label, data) ->
                        add(buildJsonObject {
                            put("text", label)
                            put("callback_data", data)
                        })
                    }
                })
            }
        })
    }

    companion object {
        /** getUpdates 长轮询服务端持有时间。 */
        const val POLL_TIMEOUT_S = 50
        /** Telegram 单条消息上限 4096，留出头部分片余量。 */
        const val MAX_MESSAGE_LENGTH = 4000

        /** 按换行优先把长文本切成若干片，供 sendMessage 分条发送。 */
        fun chunkText(text: String, limit: Int = MAX_MESSAGE_LENGTH): List<String> {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return emptyList()
            val out = mutableListOf<String>()
            var rest = trimmed
            while (rest.length > limit) {
                var cut = rest.lastIndexOf('\n', limit - 1)
                if (cut < limit / 2) cut = limit
                out.add(rest.substring(0, cut))
                rest = rest.substring(cut).trimStart('\n')
            }
            if (rest.isNotEmpty()) out.add(rest)
            return out
        }
    }
}
