package com.aharou.feature.agent.domain.provider

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * 一条 Responses WebSocket 连接：把 OkHttp 回调线程上的事件转成可挂起消费的队列。
 *
 * 回调线程不能阻塞，也不能直接跑协程逻辑，故用容量有限的 Channel 当桥；队列溢出意味着消费端
 * 已经跟不上，此时直接断连接让上层按失败处理，比继续在内存里堆事件更安全（事件里带整段 base64
 * 图片时尤其危险）。
 */
internal class ResponsesWebSocketConnection(
    client: OkHttpClient,
    request: Request,
    private val connectTimeoutMs: Long
) {
    private sealed interface Incoming {
        data class Event(val value: JsonObject) : Incoming
        data class Failure(val error: IOException) : Incoming
    }

    private val incoming = Channel<Incoming>(256)
    private val gson = Gson()
    private val opened = CompletableDeferred<Unit>()

    /** 是否处于打开状态：上层据此判断能否复用这条连接。 */
    @Volatile
    var isOpen = false
        private set

    private val socket: WebSocket = client.newWebSocket(request, object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isOpen = true
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            runCatching { JsonParser.parseString(text).asJsonObject }
                .onSuccess { offer(Incoming.Event(it)) }
                .onFailure { offer(Incoming.Failure(IOException("Invalid WebSocket JSON event", it))) }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // Responses 协议只发文本事件；二进制说明对端不是我们预期的服务端点。
            offer(Incoming.Failure(IOException("Unexpected binary WebSocket event")))
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isOpen = false
            if (!opened.isCompleted) {
                opened.completeExceptionally(WebSocketHandshakeException(code, reason))
            }
            offer(Incoming.Failure(IOException("Responses WebSocket closed ($code): $reason")))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isOpen = false
            val error = if (response != null) {
                WebSocketHandshakeException(response.code, response.message, t)
            } else {
                IOException(t.message ?: "Responses WebSocket failed", t)
            }
            if (!opened.isCompleted) opened.completeExceptionally(error)
            offer(Incoming.Failure(error))
        }
    })

    private fun offer(event: Incoming) {
        if (incoming.trySend(event).isFailure) {
            incoming.close(IOException("Responses WebSocket event queue overflow"))
            socket.cancel()
        }
    }

    /** 等待握手完成；超时按可重试的网络错误处理（取消连接，避免半开）。 */
    suspend fun awaitOpen() {
        if (connectTimeoutMs <= 0L) {
            opened.await()
            return
        }
        try {
            withTimeout(connectTimeoutMs) { opened.await() }
        } catch (e: TimeoutCancellationException) {
            socket.cancel()
            coroutineContext.ensureActive()
            throw java.net.SocketTimeoutException("Responses WebSocket handshake timed out after ${connectTimeoutMs}ms")
        } catch (e: Exception) {
            socket.cancel()
            throw e
        }
    }

    fun send(payload: Map<String, Any?>) {
        if (!socket.send(gson.toJson(payload))) {
            throw IOException("Responses WebSocket is not available for sending")
        }
    }

    /** 取下一个事件；[timeoutMs] <= 0 表示由空闲看门狗负责兜底，这里不设上限。 */
    suspend fun nextEvent(timeoutMs: Long): JsonObject {
        val next = try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) { incoming.receive() }
            } else {
                incoming.receive()
            }
        } catch (e: TimeoutCancellationException) {
            coroutineContext.ensureActive()
            throw java.net.SocketTimeoutException("Timed out waiting for a Responses WebSocket event")
        } catch (e: ClosedReceiveChannelException) {
            throw IOException("Responses WebSocket event channel is closed", e)
        }
        return when (next) {
            is Incoming.Event -> next.value
            is Incoming.Failure -> throw next.error
        }
    }

    /** 正常收尾：发 close 帧让服务端释放这一轮，同时关掉本地事件队列。 */
    fun close() {
        socket.close(1000, "run complete")
        incoming.close()
    }

    fun cancel() {
        socket.cancel()
        incoming.close()
    }
}

/**
 * 握手阶段的 HTTP 状态码失败：需要单独区分，因为 429/5xx 可以重试（或换 Key 重试），
 * 而 404/401 这类重试也没用。message 拼进异常文案，用户能在错误提示里看到服务端原话。
 */
internal class WebSocketHandshakeException(
    val statusCode: Int,
    message: String,
    cause: Throwable? = null
) : IOException("Responses WebSocket handshake failed with HTTP $statusCode: $message", cause)
