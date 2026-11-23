package com.aharou.feature.plugin.runtime

import com.aharou.core.util.FileLogger
import com.aharou.feature.plugin.domain.PluginHostException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 插件可用的文本 WebSocket。
 *
 * Operit 把它做成 handle + 轮询 read 的形状（插件进程里没有事件循环），
 * 这里照同样形状实现：open 返回 handle，read 取走当前积压的事件，close 释放。
 */
@Singleton
class PluginWebSocketService @Inject constructor() {

    private class Session(
        val pluginId: String,
        val socket: WebSocket,
        val events: ArrayDeque<JsonObject> = ArrayDeque(),
        var closed: Boolean = false
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    /** 按 op 分派；放在服务里而不是 dispatcher，免得 op 字面量被当成宿主方法名。 */
    fun dispatch(pluginId: String, params: JsonObject): JsonObject {
        val op = (params["op"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim().orEmpty()
        fun requireText(key: String): String =
            (params[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw PluginHostException("PARAM_MISSING", "$key is required")
        return when (op) {
            "open", "open_binary" -> open(
                pluginId,
                requireText("url"),
                (params["connect_timeout_seconds"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 15L
            )
            "read" -> read(pluginId, requireText("handle"))
            "send" -> send(pluginId, requireText("handle"), requireText("text"))
            "close" -> close(pluginId, requireText("handle"))
            else -> throw PluginHostException("NOT_SUPPORTED", "webSocket op 不支持: $op")
        }
    }

    fun open(pluginId: String, url: String, timeoutSeconds: Long): JsonObject {
        val request = Request.Builder().url(url).build()
        val handle = UUID.randomUUID().toString()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                push(handle, buildJsonObject { put("type", "open") })
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                push(handle, buildJsonObject { put("type", "text"); put("data", text) })
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                push(handle, buildJsonObject { put("type", "closing"); put("code", code); put("reason", reason) })
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                push(handle, buildJsonObject { put("type", "closed"); put("code", code); put("reason", reason) })
                sessions[handle]?.closed = true
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                push(handle, buildJsonObject {
                    put("type", "error")
                    put("message", t.message ?: t.javaClass.simpleName)
                })
                sessions[handle]?.closed = true
            }
        }
        val socket = client.newWebSocket(request, listener)
        sessions[handle] = Session(pluginId, socket)
        return buildJsonObject { put("op", "open"); put("handle", handle) }
    }

    fun read(pluginId: String, handle: String): JsonObject {
        val session = require(pluginId, handle)
        val out = ArrayList<JsonObject>()
        synchronized(session.events) {
            while (out.size < MAX_READ_EVENTS && session.events.isNotEmpty()) {
                out.add(session.events.removeFirst())
            }
        }
        return buildJsonObject {
            put("op", "read")
            put("events", kotlinx.serialization.json.JsonArray(out))
            put("closed", session.closed)
        }
    }

    fun send(pluginId: String, handle: String, text: String): JsonObject {
        val session = require(pluginId, handle)
        if (!session.socket.send(text)) {
            throw PluginHostException("SEND_FAILED", "WebSocket 已经关闭或队列已满")
        }
        return buildJsonObject { put("op", "send") }
    }

    fun close(pluginId: String, handle: String): JsonObject {
        val session = require(pluginId, handle)
        session.socket.close(NORMAL_CLOSURE, "plugin requested")
        sessions.remove(handle)
        return buildJsonObject { put("op", "close") }
    }

    /** 插件被停用/卸载时释放它的全部连接。 */
    fun closePlugin(pluginId: String) {
        sessions.entries.filter { it.value.pluginId == pluginId }.forEach { (handle, session) ->
            runCatching { session.socket.close(NORMAL_CLOSURE, "plugin stopped") }
            sessions.remove(handle)
        }
    }

    private fun push(handle: String, event: JsonObject) {
        val session = sessions[handle] ?: return
        synchronized(session.events) {
            if (session.events.size >= MAX_BUFFERED_EVENTS) {
                // 积压过多说明插件不读；丢最旧的，避免无界增长
                session.events.removeFirst()
            }
            session.events.addLast(event)
        }
    }

    private fun require(pluginId: String, handle: String): Session {
        val session = sessions[handle]
            ?: throw PluginHostException("HANDLE_NOT_FOUND", "WebSocket handle 不存在: $handle")
        if (session.pluginId != pluginId) {
            FileLogger.w(TAG, "插件 $pluginId 试图操作别的插件的 WebSocket: $handle")
            throw PluginHostException("HANDLE_FORBIDDEN", "WebSocket handle 不属于当前插件")
        }
        return session
    }

    private companion object {
        const val TAG = "PluginWebSocket"
        const val NORMAL_CLOSURE = 1000
        const val MAX_READ_EVENTS = 64
        const val MAX_BUFFERED_EVENTS = 256
    }
}
