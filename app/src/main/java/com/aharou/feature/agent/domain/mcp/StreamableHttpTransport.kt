package com.aharou.feature.agent.domain.mcp

import kotlin.coroutines.resumeWithException
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
 * MCP「Streamable HTTP」传输实现。
 *
 * 单一端点 POST JSON-RPC；server 可回 `application/json`（单条响应）或
 * `text/event-stream`（SSE，本传输一问一答、不维持服务端推送通道，故只读取其中 JSON-RPC `id`
 * 与本次请求匹配的那条 message 事件，跳过无 id 的通知与乱序事件）。`initialize` 响应里的
 * `Mcp-Session-Id` 头会被记下，之后每条请求都带上（spec 要求）。
 *
 * SSE 的解析方式与 AnthropicAdapter 一致——手动读 `data:` 行，避免引入 okhttp-sse。
 */
class StreamableHttpTransport(
    private val endpoint: String,
    private val client: OkHttpClient,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** OAuth 令牌提供者；非空时每条请求带 `Authorization: Bearer`，401 时强制刷新一次并重试。 */
    private val authProvider: McpBearerProvider? = null,
    private val json: Json = DEFAULT_JSON
) : McpTransport {

    private companion object {
        const val TAG = "McpHttpTransport"
        val JSON_MEDIA = "application/json".toMediaType()

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
        while (true) {
            val resp = awaitCall(client.newCall(buildRequest(bodyJson, bearer)))
            try {
                resp.header("Mcp-Session-Id")?.let { if (it.isNotBlank()) sessionId = it }

                if (resp.code == 401 && authProvider != null && !refreshed) {
                    refreshed = true
                    bearer = authProvider.refreshAfterUnauthorized()
                    continue
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
        awaitCall(client.newCall(buildRequest(bodyJson, bearer))).use { resp ->
            // 通知按 spec 服务端通常返回 202 且无 body；非 2xx 仅记日志，不阻断流程。
            if (!resp.isSuccessful) {
                FileLogger.w(TAG, "通知 $method 返回 HTTP ${resp.code}")
            }
        }
    }

    override fun close() {
        sessionId = null
    }

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

    private fun buildRequest(bodyJson: String, bearer: String? = null): Request {
        val headers = Headers.Builder().apply {
            add("Content-Type", "application/json")
            // 同时接受两种响应，让 server 自行决定单条 JSON 还是 SSE。
            add("Accept", "application/json, text/event-stream")
            sessionId?.let { add("Mcp-Session-Id", it) }
            // OAuth Bearer 优先；此时配置里同名的静态 Authorization 头不再重复添加。
            bearer?.let { if (it.isNotBlank()) add("Authorization", "Bearer $it") }
            // 名称为空的 header 会被 OkHttp 拒绝（name is empty）并整条连接失败，配置里常见的空行不该拖垮连接。
            extraHeaders.forEach { (k, v) ->
                val isDuplicateAuth = bearer != null && k.equals("Authorization", ignoreCase = true)
                if (k.isNotBlank() && !isDuplicateAuth) add(k, v)
            }
        }.build()

        return Request.Builder()
            .url(endpoint)
            .headers(headers)
            .post(bodyJson.toRequestBody(JSON_MEDIA))
            .build()
    }

    /**
     * 从 SSE 流里循环读事件，返回第一条 JSON-RPC `id` 与 [expectedId] 匹配的 `data:` 负载。
     * server 可能先推 progress 之类的通知（无 id）或乱序响应，第一个事件不能当成这次请求的响应；
     * 事件数设上限，避免 server 持续推无关事件导致死循环。多行 data 属于同一事件，
     * 按 SSE 规范以换行连接（JSON 允许空白，拼接后仍可解析）。
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
                    if (hasId(payload, expectedId)) return payload
                }
            }
        }
        // 流未以空行收尾：残留的 data 也当一条事件判一次。
        if (data.isNotEmpty()) {
            val payload = data.toString().trimEnd('\n')
            if (hasId(payload, expectedId)) return payload
        }
        return null
    }

    /** 事件负载的 JSON-RPC `id` 是否等于 [expectedId]；无 id（server 主动通知）或解析失败都算不匹配。 */
    private fun hasId(payload: String, expectedId: Long): Boolean {
        val resp = runCatching {
            json.decodeFromString(JsonRpcResponse.serializer(), payload)
        }.getOrNull()
        return resp?.id == expectedId
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
