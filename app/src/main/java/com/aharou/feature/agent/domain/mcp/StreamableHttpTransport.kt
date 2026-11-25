package com.aharou.feature.agent.domain.mcp

import kotlin.coroutines.resumeWithException
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * 一条应用层加密请求帧：正文（`nonce || ciphertext`）加上它需要的额外头（会话 id、加密标记）。
 */
class McpEnvelope(val body: ByteArray, val headers: Map<String, String>)

/**
 * 应用层加密通道。由远端设备模块实现，传输层只在这几处调用它。
 *
 * 语义是「要么真的加密，要么报错」：传输层只在配置明确要求加密（`encrypted=true`）时才拿到
 * 通道实例，拿到之后任何一步失败都以 [McpException] 上抛，**不会**静默退回明文——
 * 否则同网段中间人每次重连都能把加密剥掉而用户无感。
 */
interface McpEncryption {
    /** 建会话（懒握手）；失败抛 [McpException]，消息会被写进连接状态供用户诊断。 */
    suspend fun prepare()

    /** 把明文请求体封成一帧。 */
    fun seal(plain: ByteArray): McpEnvelope

    /** 解开一帧响应；失败抛 [McpException]。 */
    fun open(frame: ByteArray): ByteArray

    /** 丢掉当前会话，下一次 [prepare] 重新握手（对端回收会话 / 计数器错位后重新上台）。 */
    fun reset()
}

/**
 * 按对端端点与长期凭据造一条加密通道；返回 null 表示该 server 不加密。
 */
interface McpEncryptionFactory {
    /**
     * 用户总开关：false 时所有 server 一律明文（包括 `encrypted=true` 的），且**不算**配置错误。
     * 不缓存：开关关闭后新连接应该立刻按新值走，不要求重启进程。
     */
    suspend fun allowed(): Boolean

    fun create(endpoint: String, token: String?): McpEncryption?
}

/**
 * MCP「Streamable HTTP」传输实现。
 *
 * 单一端点 POST JSON-RPC；server 可回 `application/json`（单条响应）或
 * `text/event-stream`（SSE）。SSE 只读取其中 JSON-RPC `id` 与本次请求匹配的那条 message 事件，
 * 途中遇到的 server 主动通知（无 id 有 method）交给 [setNotificationHandler] 注册的回调；
 * 乱序响应照旧跳过。**本传输不维持独立的长连接推送通道**：一次 POST 结束，那条流就结束了，
 * 所以通知只在「有请求在飞」的窗口内收得到（详见 `extractSseJson` 的说明）。
 * `initialize` 响应里的 `Mcp-Session-Id` 头会被记下，之后每条请求都带上（spec 要求）。
 *
 * SSE 的解析方式与 AnthropicAdapter 一致——手动读 `data:` 行，避免引入 okhttp-sse。
 */
class StreamableHttpTransport(
    private val endpoint: String,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** OAuth 令牌提供者；非空时每条请求带 `Authorization: Bearer`，401 时强制刷新一次并重试。 */
    private val authProvider: McpBearerProvider? = null,
    private val json: Json = DEFAULT_JSON,
    /** 应用层加密通道；null 时整条传输的行为与加入加密前完全一致。 */
    private val encryption: McpEncryption? = null
) : McpTransport {

    private companion object {
        const val TAG = "McpHttpTransport"
        val JSON_MEDIA = "application/json".toMediaType()
        val BINARY_MEDIA = "application/octet-stream".toMediaType()

        /** 响应头：服务端用它标明正文是加密帧；缺席 = 明文（老服务端或不认会话）。 */
        const val ENC_RESPONSE_HEADER = "X-Aharou-Enc"

        /** SSE 事件读取上限：超出仍未等到匹配 id 的事件就当作没有响应，避免 server 持续推事件时死循环。 */
        const val MAX_SSE_EVENTS = 100

        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }

    private val idCounter = AtomicLong(0)

    @Volatile
    private var sessionId: String? = null

    /** server 主动推送消息的回调；null 时保持旧行为（只跳过）。 */
    @Volatile
    private var notificationHandler: ((String, JsonElement?) -> Unit)? = null

    override fun setNotificationHandler(handler: ((method: String, params: JsonElement?) -> Unit)?) {
        notificationHandler = handler
    }

    override suspend fun request(method: String, params: JsonObject?): JsonRpcResponse =
        withContext(Dispatchers.IO) {
            val id = idCounter.incrementAndGet()
            val payload = JsonRpcRequest(id = id, method = method, params = params)
            val bodyJson = json.encodeToString(JsonRpcRequest.serializer(), payload)
            FileLogger.d(TAG, "→ [$method] id=$id")
            sendWithAuth(bodyJson, id, method)
        }

    /**
     * 带 OAuth 令牌发一条请求。取令牌（含过期自动刷新）失败以 [McpException] 形式抛出，消息面向用户；
     * 服务端以 401 拒绝时强制刷新一次再重试，避免死循环。
     */
    private suspend fun sendWithAuth(bodyJson: String, id: Long, method: String): JsonRpcResponse {
        var bearer = authProvider?.accessToken()
        var refreshed = false
        // 配了加密的 server 必须先握手成功：这一步失败就直接报错（见 McpEncryption 的说明）。
        val channel = encryption
        channel?.prepare()
        // 对端回收了会话（空闲超时 / 重启）时重握手一次再发同一条请求：服务端在解密前就拒了，
        // 不会重复执行动作；只重试一次，避免死循环。
        var rehandshaked = false
        while (true) {
            val envelope = channel?.seal(bodyJson.toByteArray(Charsets.UTF_8))
            val resp = awaitCall(client.newCall(buildRequest(bodyJson, bearer, envelope)))
            try {
                resp.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) sessionId = it }

                if (resp.code == 401 && authProvider != null && !refreshed) {
                    refreshed = true
                    bearer = authProvider.refreshAfterUnauthorized()
                    continue
                }

                if (channel != null && envelope != null) {
                    // 要求加密的连接只接受加密响应：对端没带标记说明它没认这条会话（或根本不支持），
                    // 这时退回明文等于给中间人一次剥加密的机会，所以要么重握手，要么报错。
                    if (resp.header(ENC_RESPONSE_HEADER) == null) {
                        if (resp.code == 400 && !rehandshaked) {
                            rehandshaked = true
                            FileLogger.w(TAG, "对端不认加密会话（HTTP 400），重新握手后重试: $method")
                            channel.reset()
                            channel.prepare()
                            continue
                        }
                        throw McpException(
                            message = "对端未按应用层加密响应（HTTP ${resp.code}）：" +
                                "这条 server 要求加密，已拒绝本次调用"
                        )
                    }
                    if (!resp.isSuccessful) {
                        throw McpException(message = "HTTP ${resp.code} 调用 $method 失败: ${resp.message}")
                    }
                    val frame = resp.body?.bytes() ?: throw McpException(message = "$method 响应体为空")
                    return parseAndValidate(String(channel.open(frame), Charsets.UTF_8), id, method)
                }

                if (!resp.isSuccessful) {
                    throw McpException(message = "HTTP ${resp.code} 调用 $method 失败: ${resp.message}")
                }

                val contentType = resp.header("Content-Type").orEmpty()
                val rawJson = if (contentType.contains("text/event-stream", ignoreCase = true)) {
                    extractSseJson(resp.body?.charStream()?.buffered(), id)
                        ?: throw McpException(message = "SSE 响应中未找到 id=$id 的 $method 数据")
                } else {
                    resp.body?.string()
                        ?: throw McpException(message = "$method 响应体为空")
                }
                return parseAndValidate(rawJson, id, method)
            } finally {
                resp.close()
            }
        }
    }

    override suspend fun notify(method: String, params: JsonObject?) = withContext(Dispatchers.IO) {
        val payload = JsonRpcNotification(method = method, params = params)
        val bodyJson = json.encodeToString(JsonRpcNotification.serializer(), payload)
        FileLogger.d(TAG, "→ notify [$method]")
        val bearer = authProvider?.accessToken()
        val envelope = encryption?.seal(bodyJson.toByteArray(Charsets.UTF_8))
        awaitCall(client.newCall(buildRequest(bodyJson, bearer, envelope))).use { resp ->
            if (envelope != null && resp.header(ENC_RESPONSE_HEADER) == null) {
                // 通知没有回退价值，但也不静默：这条 server 要求加密，对端却没认，得在日志里留住原因。
                FileLogger.w(TAG, "通知 $method 未被对端按加密接收（HTTP ${resp.code}）")
            }
            // 通知按 spec 服务端通常返回 202 且无 body；非 2xx 仅记日志，不阻断流程。
            if (!resp.isSuccessful) {
                FileLogger.w(TAG, "通知 $method 返回 HTTP ${resp.code}")
            }
        }
    }

    override fun close() {
        sessionId = null
    }

    /** HTTP 是无状态的：close() 只是清 sessionId，下次请求会重新协商。 */
    override val isOpen: Boolean get() = true

    /** 把 OkHttp 的异步回调桥接成可取消的挂起调用；调用方取消时 cancel 掉 call。 */
    private suspend fun awaitCall(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                    return
                }
                cont.resume(response, null)
            }
        })
    }

    private fun buildRequest(bodyJson: String, bearer: String? = null, envelope: McpEnvelope? = null): Request {
        val headers = Headers.Builder().apply {
            // 加密帧是二进制，Content-Type 必须跟着换；明文分支保持原样。
            add("Content-Type", if (envelope != null) "application/octet-stream" else "application/json")
            // 同时接受两种响应，让 server 自行决定单条 JSON 还是 SSE。
            add("Accept", "application/json, text/event-stream")
            sessionId?.let { add("Mcp-Session-Id", it) }
            // OAuth Bearer 优先；此时配置里同名的静态 Authorization 头不再重复添加。
            if (envelope == null) {
                bearer?.let { if (it.isNotBlank()) add("Authorization", "Bearer $it") }
            }
            // 名称为空的 header 会被 OkHttp 拒绝（name is empty）并整条连接失败，配置里常见的空行不该拖垮连接。
            extraHeaders.forEach { (k, v) ->
                val isDuplicateAuth = bearer != null && k.equals("Authorization", ignoreCase = true)
                // 加密请求不带 Authorization：token 由通道封进密文体内，留在头上等于白加密。
                val isSealedAuth = envelope != null && k.equals("Authorization", ignoreCase = true)
                if (k.isNotBlank() && !isDuplicateAuth && !isSealedAuth) add(k, v)
            }
            envelope?.headers?.forEach { (k, v) -> if (k.isNotBlank()) add(k, v) }
        }.build()

        return Request.Builder()
            .url(endpoint)
            .headers(headers)
            .post(envelope?.body?.toRequestBody(BINARY_MEDIA) ?: bodyJson.toRequestBody(JSON_MEDIA))
            .build()
    }

    /**
     * 从 SSE 流里循环读事件，返回第一条 JSON-RPC `id` 与 [expectedId] 匹配的 `data:` 负载。
     * server 可能先推 progress / 资源变更之类的通知（无 id）或乱序响应，第一个事件不能当成这次请求的响应；
     * 途中遇到的通知交给注册的回调，不丢。事件数设上限，避免 server 持续推无关事件导致死循环。
     * 多行 data 属于同一事件，按 SSE 规范以换行连接（JSON 允许空白，拼接后仍可解析）。
     *
     * 限制：这条流只在本次 POST 期间存在，响应一返回就断开，所以没有持久推送通道；
     * 依赖实时通知的调用方需要保活一条长连接的 server，或周期性重新读资源。
     */
    private fun extractSseJson(reader: java.io.BufferedReader?, expectedId: Long): String? {
        reader ?: return null
        val data = StringBuilder()
        var events = 0
        while (events < MAX_SSE_EVENTS) {
            val line = reader.readLine() ?: break
            when {
                line.startsWith("data:") -> data.append(line.removePrefix("data:").trim()).append('\n')
                line.isBlank() && data.isNotEmpty() -> {
                    events++
                    val payload = data.toString().trimEnd('\n')
                    data.setLength(0)
                    if (handleSseEvent(payload, expectedId)) return payload
                }
            }
        }
        // 流未以空行收尾：残留的 data 也当一条事件判一次。
        if (data.isNotEmpty()) {
            val payload = data.toString().trimEnd('\n')
            if (handleSseEvent(payload, expectedId)) return payload
        }
        return null
    }

    /**
     * 判一条 SSE 事件是不是本次请求的响应；不是的话，若是 server 主动通知就分发给回调。
     *
     * `id` 用 `contentOrNull` 取再转数字：规范允许 id 为字符串，直接反序列化成 Long 会解析失败。
     */
    private fun handleSseEvent(payload: String, expectedId: Long): Boolean {
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return false
        val idElement = obj["id"]
        if ((idElement as? JsonPrimitive)?.contentOrNull?.toLongOrNull() == expectedId) return true
        val hasNoId = idElement == null || idElement is JsonNull
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull
        // 带 id 的 server 请求（sampling/roots 之类）需要应答，这里不会也不该当成通知往下传。
        if (hasNoId && method != null) dispatchNotification(method, obj["params"])
        return false
    }

    /**
     * 把一条 server 通知交给上层回调。
     *
     * 回调在读 SSE 的协程里同步执行，抛异常会带上整个请求——必须咽下，否则一次不听话的回调
     * 就会表现成「资源订阅期间请求随机失败」。
     */
    private fun dispatchNotification(method: String, params: JsonElement?) {
        val handler = notificationHandler
        if (handler == null) {
            FileLogger.d(TAG, "收到 server 通知（无接收方）: $method")
            return
        }
        runCatching { handler(method, params) }
            .onFailure { FileLogger.w(TAG, "通知回调异常（已忽略）: ${it.message}") }
    }

    private fun parseAndValidate(rawJson: String, expectedId: Long, method: String): JsonRpcResponse {
        val response = runCatching {
            json.decodeFromString(JsonRpcResponse.serializer(), rawJson)
        }.getOrElse {
            throw McpException(message = "$method 响应 JSON 解析失败: ${it.message}", cause = it)
        }

        response.error?.let {
            throw McpException(rpcCode = it.code, message = "$method 返回错误 [${it.code}] ${it.message}")
        }
        if (response.id != null && response.id != expectedId) {
            FileLogger.w(TAG, "响应 id 不匹配: 期望 $expectedId, 实际 ${response.id}")
        }
        return response
    }
}
