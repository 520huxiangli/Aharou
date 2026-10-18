package com.aharou.feature.im.domain

import android.content.Context
import android.net.Uri
import com.aharou.core.util.FileLogger
import com.aharou.feature.im.data.repository.WeixinConfig
import com.aharou.feature.im.domain.model.ImAttachment
import com.aharou.feature.im.domain.model.ImBotIdentity
import com.aharou.feature.im.domain.model.ImInboundMessage
import com.aharou.feature.im.domain.model.ImUpdate
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
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
import kotlinx.serialization.json.JsonObject
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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** iLink 返回的业务错误；message 为服务端原文（已尽量脱敏）。 */
class WeixinApiException(message: String) : RuntimeException(message)

/**
 * 微信 iLink（ClawBot）通道。
 *
 * 走 `getUpdates` **长轮询**，与 Telegram 同一套思路：不需要公网地址、不需要自建服务器，
 * 手机 App 自己就能当服务端，因此复用 Telegram 那份连接与分片策略。
 *
 * 与 Telegram 的两处实质差异，都在这一层消化掉：
 * 1. **回复必须回带 `context_token`**——接口里的 [send] 只有 chatId，所以这里在收到消息时
 *    记下 `chatId → contextToken` 的映射，发送时自动补上；
 * 2. **不支持编辑已发消息**，流式刷新只能退化成「结束时补发最终文本」（[edit] 恒返回 false）；
 *    也没有内联按钮，工具审批退化成纯文本（[approvalMarkup] 返回 null）。
 *
 * 端点路径集中在 [Paths]：iLink 的具体路由没有公开文档，与官方不符时只改这里。
 */
@Singleton
class WeixinIlinkClient @Inject constructor(
    private val baseClient: OkHttpClient,
    @param:ApplicationContext private val context: Context
) : ImChannel {

    override val id: String = "weixin"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    /** chatId（session_id 或 from_user_id）→ 最近一次的 context_token。 */
    private val contextTokens = ConcurrentHashMap<String, String>()

    @Volatile private var cachedClient: OkHttpClient? = null
    @Volatile private var cachedProxyKey: String? = null

    override suspend fun identity(cfg: ImChannelConfig): ImBotIdentity {
        val c = cfg.requireWeixin()
        val params = buildJsonObject { put("base_info", baseInfo()) }
        val obj = call(c, Paths.GET_CONFIG, params).jsonObject
        val id = obj["ilink_user_id"]?.jsonPrimitive?.contentOrNull ?: c.ilinkUserId
        return ImBotIdentity(id = 0L, username = id)
    }

    /** 服务端下发的游标（get_updates_buf）。首次为空串，之后每次用上一次响应里的值。 */
    @Volatile private var updateBuf: String = ""

    override suspend fun poll(cfg: ImChannelConfig, cursor: Long, timeoutSeconds: Int): List<ImUpdate> {
        val c = cfg.requireWeixin()
        val params = buildJsonObject {
            put("base_info", baseInfo())
            put("get_updates_buf", updateBuf)
        }
        val result = call(c, Paths.UPDATES, params)
        // 先更新游标：服务端 hold 住后返回的新游标必须带回下一次请求，否则每轮都从头拉
        (result as? JsonObject)?.get("get_updates_buf")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }?.let { updateBuf = it }
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> result["msgs"] as? JsonArray
                ?: result["messages"] as? JsonArray
                ?: JsonArray(emptyList())
            else -> JsonArray(emptyList())
        }
        var seq = cursor
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            parseMessage(++seq, obj)?.let(ImUpdate::Message)
        }
    }

    override suspend fun send(cfg: ImChannelConfig, chatId: String, text: String): String {
        val c = cfg.requireWeixin()
        val contextToken = contextTokens[chatId].orEmpty()
        val chunks = TelegramBotClient.chunkText(text, MAX_MESSAGE_LENGTH)
        var lastId = ""
        chunks.forEachIndexed { index, chunk ->
            val msg = buildJsonObject {
                // 下面这五个字段服务端靠它们做路由与去重，缺一个消息就被静默丢弃
                // （HTTP 200、响应体空 {}、微信侧啥也收不到）。官方 README 没写，是逆向出来的。
                put("from_user_id", "")
                put("to_user_id", chatId)
                put("client_id", "aharou-${System.currentTimeMillis()}-$index")
                put("message_type", 2)  // BOT：1=用户，2=机器人
                put("message_state", 2) // FINISH：0=新建，1=生成中，2=完成
                if (contextToken.isNotBlank()) put("context_token", contextToken)
                put("item_list", buildJsonArray {
                    add(buildJsonObject {
                        put("type", 1) // TEXT：必须是数字，写成 "text" 也会被丢
                        put("text_item", buildJsonObject { put("text", chunk) })
                    })
                })
            }
            val params = buildJsonObject {
                put("base_info", baseInfo())
                put("msg", msg)
            }
            val id = call(c, Paths.SEND_MESSAGE, params)
                .jsonObject["client_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (id.isNotBlank()) lastId = id
        }
        return lastId
    }

    override fun redact(text: String, cfg: ImChannelConfig): String {
        val token = (cfg as? WeixinConfig)?.botToken.orEmpty()
        val masked = if (token.isNotBlank()) text.replace(token, "<token>") else text
        return masked.lineSequence().firstOrNull().orEmpty().take(300)
    }

    /** 输入中状态；拿不到 typing_ticket 时静默跳过（服务端本来也会忽略）。 */
    suspend fun sendTyping(cfg: WeixinConfig, ilinkUserId: String, typingTicket: String, typing: Boolean) {
        val params = buildJsonObject {
            put("base_info", baseInfo())
            put("ilink_user_id", ilinkUserId)
            put("typing_ticket", typingTicket)
            put("status", if (typing) "typing" else "cancel")
        }
        runCatching { call(cfg, Paths.SEND_TYPING, params) }
    }

    /** 拉取会话配置（含 typing_ticket 之类）；失败返回 null。 */
    suspend fun getConfig(cfg: WeixinConfig, ilinkUserId: String, contextToken: String): JsonObject? =
        runCatching {
            val params = buildJsonObject {
                put("base_info", baseInfo())
                put("ilink_user_id", ilinkUserId)
                put("context_token", contextToken)
            }
            call(cfg, Paths.GET_CONFIG, params).jsonObject
        }.getOrNull()

    // ── 内部 ────────────────────────────────────────────────

    /**
     * 鉴权头里的客户端标识：base64(随机 uint32 的十进制字符串)。
     * 另需 iLink-App-Id: bot（固定值，来自官方包的 ilink_appid）。
     */
    private fun randomUin(): String =
        java.util.Base64.getEncoder().encodeToString(
            kotlin.random.Random.nextInt().toString().toByteArray()
        )

    private fun ImChannelConfig.requireWeixin(): WeixinConfig =
        this as? WeixinConfig ?: error("微信通道收到了非微信配置：${this::class.simpleName}")

    /** BotHub 里默认值就是 "0.3.0"，不是协议版本号 1（那个只出现在请求头里）。 */
    /**
     * 通用请求体里的 base_info。
     *
     * `channel_version` 必须跟官方客户端一致（官方协议文档是 2.4.8）；填小了服务端会不认，
     * `bot_agent` 也要带上。JSON 键用蛇形（BotHub 的 toString 展示的是 Kotlin 属性名）。
     */
    private fun baseInfo(): JsonObject = buildJsonObject {
        put("channel_version", CHANNEL_VERSION)
        put("bot_agent", BOT_AGENT)
    }

    private suspend fun call(cfg: WeixinConfig, path: String, params: JsonObject?): JsonElement =
        withContext(Dispatchers.IO) {
            // 四个头缺一不可：BotHub 的 Bot3e.l() 里就是这么构造的
            val request = Request.Builder()
                .url(urlFor(cfg, path))
                .header("iLink-App-Id", "bot")
                .header("iLink-App-ClientVersion", "1")
                .header("AuthorizationType", "ilink_bot_token")
                .header("X-WECHAT-UIN", randomUin())
                .header("Authorization", "Bearer ${cfg.botToken}")
                .post((params ?: JsonObject(emptyMap())).toString().toRequestBody(jsonMedia))
                .build()
            val (code, body) = execute(clientFor(cfg), request)
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()
                ?: throw WeixinApiException(redact("HTTP $code", cfg))
            val obj = root as? JsonObject
            if (obj != null) {
                // iLink 错误体形状不确定，两种常见形态都认
                val errcode = obj["errcode"]?.jsonPrimitive?.longOrNull ?: 0L
                val success = obj["success"]?.jsonPrimitive?.booleanOrNull
                if (errcode != 0L || success == false) {
                    val msg = obj["errmsg"]?.jsonPrimitive?.contentOrNull
                        ?: obj["message"]?.jsonPrimitive?.contentOrNull
                        ?: obj["msg"]?.jsonPrimitive?.contentOrNull
                        ?: "HTTP $code"
                    throw WeixinApiException(redact(msg, cfg))
                }
                (obj["data"] as? JsonObject)?.let { return@withContext it }
            }
            root
        }

    private fun urlFor(cfg: WeixinConfig, path: String): String {
        val base = cfg.baseUrl.trim().trimEnd('/').ifBlank { WeixinConfig.DEFAULT_BASE_URL }
        return base + path
    }

    private fun clientFor(cfg: WeixinConfig): OkHttpClient {
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

    private fun parseProxy(raw: String): Proxy? {
        if (raw.isBlank()) return null
        val normalized = if (raw.contains("://")) raw else "http://$raw"
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val host = uri.host ?: return null
        val isSocks = uri.scheme?.lowercase()?.startsWith("socks") == true
        val port = if (uri.port > 0) uri.port else if (isSocks) 1080 else 8080
        val type = if (isSocks) Proxy.Type.SOCKS else Proxy.Type.HTTP
        return Proxy(type, InetSocketAddress(host, port))
    }

    /**
     * 发请求并拿原始响应。
     *
     * `suspendCancellableCoroutine` 本身**不切线程**，里面的 `call.execute()` 是阻塞调用，
     * 不显式放到 IO 线程就会抛 NetworkOnMainThreadException（2026-10-18 微信扫码就是栽在这里，
     * 表现成「拿二维码失败」）。
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

    /** 扫码登录结果。 */
    sealed interface QrLoginState {
        data object Waiting : QrLoginState
        data object Scanned : QrLoginState
        data object Expired : QrLoginState
        data class Done(val botToken: String, val ilinkUserId: String, val baseUrl: String?) : QrLoginState
        data class Unknown(val status: String) : QrLoginState
    }

    /** 拿到的二维码：qrcode 是轮询用的串，display 是给用户看的图或链接。 */
    data class QrTicket(val qrcode: String, val display: String)

    /**
     * 扫码第一步：拿二维码。
     *
     * `GET /ilink/bot/get_bot_qrcode?bot_type=3`，带 `iLink-App-ClientVersion` 头。
     * 返回字段名在客户端侧被混淆过，这里不绑 data class，按候选键宽松取。
     */
    suspend fun requestQrCode(cfg: WeixinConfig): QrTicket? = runCatching {
        val client = clientFor(cfg)
        val url = cfg.baseUrl.trimEnd('/').ifBlank { WeixinConfig.DEFAULT_BASE_URL } + Paths.QR_CODE +
            "?bot_type=3"
        val request = Request.Builder()
            .url(url)
            // 扫码走公共请求头：除客户端版本外还要带 iLink-App-Id（固定 bot）
            .header("iLink-App-Id", "bot")
            .header(HEADER_CHANNEL_VERSION, CHANNEL_VERSION)
            .get()
            .build()
        val (status, raw) = execute(client, request)
        if (status !in 200..299) {
            FileLogger.w(TAG, "扫码取码失败：HTTP $status ${raw.take(200)}")
            return null
        }
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
        if (root == null) {
            FileLogger.w(TAG, "扫码取码响应不是 JSON：${raw.take(300)}")
            return null
        }
        val code = pick(root, "qrcode", "qr_code")
        if (code == null) {
            FileLogger.w(TAG, "扫码取码响应里没有 qrcode：${raw.take(300)}")
            return null
        }
        // JSON 键是蛇形（qrcode_img_content），驼峰只当兼容兑底
        val image = pick(
            root,
            "qrcode_img_content",
            "qrcodeImgContent",
            "qrcode_url"
        )
        return QrTicket(qrcode = code, display = image ?: code)
    }.onFailure { e ->
        // execute() 自己会抛（连不上、超时、TLS 失败），这里不捕就会静默返回 null，
        // 界面只显示「拿二维码失败」，日志里一点线索都没有。
        FileLogger.w(TAG, "扫码取码异常：${e.javaClass.simpleName}: ${e.message}")
    }.getOrNull()

    /**
     * 扫码第二步：查状态。
     *
     * `GET /ilink/bot/get_qrcode_status?qrcode=...`。平台给的状态词里已确认有 `wait`，
     * 成功时同包回凭证——同样用候选键宽取。
     */
    suspend fun pollQrStatus(cfg: WeixinConfig, qrcode: String): QrLoginState {
        val client = clientFor(cfg)
        val url = cfg.baseUrl.trimEnd('/').ifBlank { WeixinConfig.DEFAULT_BASE_URL } + Paths.QR_STATUS +
            "?qrcode=" + Uri.encode(qrcode)
        val request = Request.Builder()
            .url(url)
            .header("iLink-App-Id", "bot")
            .header(HEADER_CHANNEL_VERSION, CHANNEL_VERSION)
            .get()
            .build()
        val (_, raw) = execute(client, request)
        val root = runCatching { json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return QrLoginState.Unknown("unparseable")
        val status = pick(root, "status").orEmpty().lowercase()
        return when (status) {
            // 真实状态值：wait / scaned / confirmed / expired（没有 success）
            "wait", "waiting", "new" -> QrLoginState.Waiting
            "scaned", "scanned", "scan" -> QrLoginState.Scanned
            "expired", "expire", "timeout" -> QrLoginState.Expired
            "confirmed", "confirm", "success" -> {
                // 凭据同样用蛇形键（BotHub 的 toString 展示的是 Kotlin 属性名）
                val token = pick(root, "bot_token")
                val uid = pick(root, "ilink_user_id")
                if (token.isNullOrBlank() || uid.isNullOrBlank()) {
                    QrLoginState.Unknown("confirmed_without_credentials")
                } else {
                    QrLoginState.Done(token, uid, pick(root, "baseurl"))
                }
            }
            else -> QrLoginState.Unknown(status.ifBlank { "empty" })
        }
    }

    /** 按候选键名依次尝试，第一个非空即返回。 */
    private fun pick(root: JsonObject, vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key ->
            root[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        }

    /** 把 iLink 的一条消息归一化成内部模型。字段名以 BotHub 反编译的 toString 为准（全驼峰）。 */
    private fun parseMessage(seq: Long, obj: JsonObject): ImInboundMessage? {
        val from = obj["from_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (from.isBlank()) return null
        val items = (obj["item_list"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val text = items.firstNotNullOfOrNull { item ->
            (item["text_item"] as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.orEmpty()
        val attachments = items.mapNotNull(::parseAttachment)
        // 纯图片/文件也是有效消息（用户只发了一张图），不能只看文本
        if (text.isBlank() && attachments.isEmpty()) return null
        val to = obj["to_user_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val session = obj["session_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val contextToken = obj["context_token"]?.jsonPrimitive?.contentOrNull.orEmpty()
        // 发消息的 to_user_id 要的是对方 openid（from_user_id），不是会话 id。
        // 原来优先取 session，导致 send 的目标不是真人，服务端同样静默丢弃。
        val chatId = from.ifBlank { session }
        // 记下 context_token：回复必须回带，否则多半被服务端拒绝
        if (contextToken.isNotBlank()) contextTokens[chatId] = contextToken
        return ImInboundMessage(
            updateId = seq,
            chatId = chatId,
            senderId = from,
            senderName = from,
            chatTitle = chatId,
            text = text,
            isGroup = session.isNotBlank() && session != from && to.isNotBlank(),
            messageId = obj["client_id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            replyToSenderId = null,
            contextToken = contextToken.ifBlank { null },
            attachments = attachments
        )
    }

    /**
     * 发媒体：**暂不支持**。
     *
     * iLink 的上传是先把 `getUploadUrl` 返回的 `uploadParam` 交给 CDN，再在 `item_list` 里引用；
     * 这两处的具体形状都没有公开文档，BotHub 里也被混淆到挖不出来。
     * 与其按猜的形状发请求（必然失败，还可能被风控），不如明确不支持，等实测后补。
     */
    override suspend fun sendMedia(
        cfg: ImChannelConfig,
        chatId: String,
        file: File,
        kind: ImAttachment.Kind,
        caption: String
    ): String? = null

    /**
     * 解析 item_list 里的一份附件。
     *
     * iLink 的图片/文件都带 CDN 直链，字段名在不同版本里叫法不一（cdn_url / url / download_url），
     * 这里按候选名取，取不到就丢掉——宁可少一张图，也不要因为字段变动把整条消息丢了。
     */
    private fun parseAttachment(item: JsonObject): ImAttachment? {
        // 直链在 url，加密场景在 media.full_url；两者都没有就当拿不到
        fun pickUrl(obj: JsonObject): String? {
            obj["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
            val media = obj["media"] as? JsonObject ?: return null
            return (media["full_url"] ?: media["fullUrl"])
                ?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
        }

        (item["image_item"] as? JsonObject)?.let { image ->
            return ImAttachment(
                kind = ImAttachment.Kind.IMAGE,
                url = pickUrl(image),
                mimeType = "image/jpeg"
            )
        }
        (item["file_item"] as? JsonObject)?.let { file ->
            return ImAttachment(
                kind = ImAttachment.Kind.FILE,
                url = pickUrl(file),
                fileName = (file["file_name"] ?: file["fileName"])
                    ?.jsonPrimitive?.contentOrNull.orEmpty(),
                sizeBytes = file["len"]?.jsonPrimitive?.longOrNull ?: 0L
            )
        }
        (item["voice_item"] as? JsonObject)?.let { voice ->
            return ImAttachment(
                kind = ImAttachment.Kind.AUDIO,
                url = pickUrl(voice),
                mimeType = "audio/silk"
            )
        }
        return null
    }

    /** 附件下载：有直链就直接 GET，落到 cacheDir 交给编排层搬走。 */
    override suspend fun downloadAttachment(cfg: ImChannelConfig, attachment: ImAttachment): File? {
        val c = cfg.requireWeixin()
        val url = attachment.url?.takeIf { it.isNotBlank() } ?: return null
        val target = File(context.cacheDir, "weixin_${System.currentTimeMillis()}_${attachment.fileName.ifBlank { "file" }}")
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

    /** iLink 的具体路由没有公开文档，集中在这里，与官方不符时只改常量。 */
    private object Paths {
        // 真实路径全是小写且带 /ilink/bot/ 前缀，不是 /openapi/
        const val UPDATES = "/ilink/bot/getupdates"
        const val SEND_MESSAGE = "/ilink/bot/sendmessage"
        const val SEND_TYPING = "/ilink/bot/sendtyping"
        const val GET_CONFIG = "/ilink/bot/getconfig"
        const val GET_UPLOAD_URL = "/ilink/bot/getuploadurl"

        const val QR_CODE = "/ilink/bot/get_bot_qrcode"
        const val QR_STATUS = "/ilink/bot/get_qrcode_status"
    }

    companion object {
        private const val TAG = "WeixinIlinkClient"
        const val POLL_TIMEOUT_S = 25
        const val MAX_MESSAGE_LENGTH = 2000
        const val CHANNEL_VERSION = "2.4.8"
        const val BOT_AGENT = "OpenClaw"
        const val HEADER_CHANNEL_VERSION = "iLink-App-ClientVersion"
    }
}
