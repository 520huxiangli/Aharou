package com.aharou.debugmcp

import android.app.Application
import android.content.Context
import com.aharou.core.util.FileLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * debug 变体专属：进程内最小 MCP server（MCP「Streamable HTTP」传输）。
 *
 * 目的：让另一个 Aharou 实例（正式版，跑着 AI）通过 MCP 连上本测试包，直接调用语义级工具
 * （见 [DebugMcpTools]）驱动界面与读日志，不必截图 / 点坐标。
 *
 * 协议对齐 `feature/agent/domain/mcp/StreamableHttpTransport.kt` + [com.aharou.feature.agent.domain.mcp.McpClient]：
 *  - 单一端点 POST JSON-RPC（Content-Type: application/json，Accept 同时含 application/json 与 text/event-stream）；
 *  - 本实现固定回 `application/json` 单条响应（客户端对两种响应都支持，SSE 只是可选项）；
 *  - initialize 响应带 `Mcp-Session-Id` 头，客户端记录后每条请求回带（本实现只回带、不强制校验）；
 *  - 通知（无 id，如 notifications/initialized）回 202 空体；
 *  - 协议版本照抄客户端的 "2025-06-18"。
 *
 * 绑定 127.0.0.1（仅回环），端口默认 [DEFAULT_PORT]，可用 `<filesDir>/debug-mcp-port.txt` 覆盖。
 * 只在 debug 变体编译（整个文件位于 app/src/debug/），正式包绝不包含。
 */
object DebugMcpServer {

    private const val TAG = "DebugMcp"
    const val DEFAULT_PORT = 47821
    private const val PROTOCOL_VERSION = "2025-06-18"
    private const val SOCKET_TIMEOUT_MS = 15_000
    private const val SERVER_BACKLOG = 16

    private val started = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /** 幂等启动：绑定回环端口并在守护线程里跑 accept 循环。由 debug 专属 Provider 在启动时调用。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        // applicationContext 运行时总是 Application，但静态类型是 Context；拿不到就不注册活动栈
        // （工具层会取不到 AIAgentViewModel），并复位启动标记，留给下次再试。
        val app = context.applicationContext as? android.app.Application ?: run {
            started.set(false)
            return
        }
        appContext = app
        // 记录活动栈，供工具线程取「当前 Activity 的 AIAgentViewModel」（ViewModel 非单例，只能从 Activity 拿）。
        if (app is Application) DebugMcpActivityTracker.register(app)
        // 端口解析（可能读盘）与 socket 绑定都放后台线程，避免拖慢启动主线程。
        Thread({ acceptLoop(resolvePort(app)) }, "debug-mcp-server").apply { isDaemon = true }.start()
    }

    private fun resolvePort(context: Context): Int = runCatching {
        val f = java.io.File(context.filesDir, "debug-mcp-port.txt")
        if (f.isFile) f.readText().trim().toIntOrNull() ?: DEFAULT_PORT else DEFAULT_PORT
    }.getOrDefault(DEFAULT_PORT)

    private fun acceptLoop(port: Int) {
        val server = try {
            ServerSocket(port, SERVER_BACKLOG, InetAddress.getByName("127.0.0.1"))
        } catch (e: Exception) {
            FileLogger.e(TAG, "无法绑定 127.0.0.1:$port，MCP server 未启动", e)
            started.set(false)
            return
        }
        FileLogger.i(TAG, "MCP server 已监听 http://127.0.0.1:$port/mcp（仅本机可访问）")
        while (!Thread.currentThread().isInterrupted) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                FileLogger.w(TAG, "accept 失败，停止监听: ${e.message}")
                break
            }
            Thread({ handleSocket(socket) }, "debug-mcp-conn").apply { isDaemon = true }.start()
        }
        runCatching { server.close() }
    }

    /** 每个连接一条请求（响应带 Connection: close，OkHttp 会另起连接，MCP 调用频率极低不构成负担）。 */
    private fun handleSocket(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val request = readRequest(BufferedInputStream(socket.getInputStream())) ?: return
            val outcome = try {
                runBlocking { handleRequest(request) }
            } catch (e: Exception) {
                FileLogger.e(TAG, "处理请求失败", e)
                RpcOutcome(
                    body = errorResponse(null, -32603, "Internal error: ${e.message}"),
                    status = 200,
                    sessionId = null,
                )
            }
            socket.getOutputStream().use { writeResponse(it, outcome) }
        } catch (e: Exception) {
            FileLogger.w(TAG, "连接处理异常: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private suspend fun handleRequest(req: HttpRequest): RpcOutcome {
        if (req.method != "POST") {
            FileLogger.w(TAG, "收到非 POST 请求：${req.method} ${req.path}")
            return RpcOutcome(body = "", status = 405, sessionId = null)
        }
        val obj = runCatching { json.parseToJsonElement(req.body) as? JsonObject }.getOrNull()
            ?: return RpcOutcome(errorResponse(null, -32700, "Parse error"), 200, null)

        val idEl = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull

        // 无 id → 通知（如 notifications/initialized），按 spec 回 202 无体。
        if (idEl == null || idEl is JsonNull) {
            FileLogger.d(TAG, "收到通知: $method")
            return RpcOutcome(body = "", status = 202, sessionId = null)
        }
        if (method.isNullOrBlank()) {
            return RpcOutcome(errorResponse(idEl, -32600, "Invalid Request"), 200, null)
        }

        return when (method) {
            "initialize" -> {
                val sessionId = UUID.randomUUID().toString()
                FileLogger.i(TAG, "initialize 握手，签发会话 $sessionId")
                RpcOutcome(resultResponse(idEl, initializeResult()), 200, sessionId)
            }
            "tools/list" -> RpcOutcome(resultResponse(idEl, toolsListResult()), 200, null)
            "tools/call" -> {
                val (text, isError) = callTool(obj["params"] as? JsonObject)
                RpcOutcome(resultResponse(idEl, toolCallResult(text, isError)), 200, null)
            }
            "ping" -> RpcOutcome(resultResponse(idEl, buildJsonObject { }), 200, null)
            else -> RpcOutcome(errorResponse(idEl, -32601, "Method not found: $method"), 200, null)
        }
    }

    private suspend fun callTool(params: JsonObject?): Pair<String, Boolean> {
        val context = appContext ?: return "server 未初始化" to true
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
            ?: return "缺少工具名（params.name）" to true
        val args = params["arguments"] as? JsonObject
        return try {
            when (name) {
                "ui_state" -> DebugMcpTools.uiState(context) to false
                "chat_send" -> DebugMcpTools.chatSend(context, args) to false
                "logs" -> DebugMcpTools.logs(args) to false
                else -> "未知工具：$name" to true
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "工具 $name 执行失败", e)
            ("工具 $name 执行失败：" + (e.message ?: e.javaClass.simpleName)) to true
        }
    }

    // ---- JSON-RPC 报文构造 ----

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") { putJsonObject("tools") { } }
        putJsonObject("serverInfo") {
            put("name", "aharou-debug-mcp")
            put("version", "1.0.0")
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            addJsonObject {
                put("name", "ui_state")
                put(
                    "description",
                    "返回当前 App 的结构化运行状态（工作区路径、当前会话 id/标题、当前路由、" +
                        "是否在跑 agent、是否有待处理工具授权、是否隐身会话）。不返回 UI 树或截图。"
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") { }
                    putJsonArray("required") { }
                }
            }
            addJsonObject {
                put("name", "chat_send")
                put(
                    "description",
                    "把一段文本注入当前会话的输入草稿并发送（等价用户在输入框打字后点发送）。" +
                        "走 App 内部真实链路；AI 忙时自动入队，斜杠命令按正常分流执行。中文可正常发送。"
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "要发送给 AI 的文本")
                        }
                    }
                    putJsonArray("required") { add(JsonPrimitive("text")) }
                }
            }
            addJsonObject {
                put("name", "logs")
                put(
                    "description",
                    "读取 App 运行日志的尾部（只保留最近 7 天，按天分文件）。支持可选关键词过滤与行数上限。"
                )
                putJsonObject("inputSchema") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("query") {
                            put("type", "string")
                            put("description", "关键词过滤，大小写不敏感；空则不过滤")
                        }
                        putJsonObject("limit") {
                            put("type", "integer")
                            put("description", "最多返回行数，默认 200，上限 2000（尾部优先）")
                        }
                        putJsonObject("date") {
                            put("type", "string")
                            put("description", "要读的日期 yyyy-MM-dd，缺省今天")
                        }
                    }
                    putJsonArray("required") { }
                }
            }
        }
    }

    private fun toolCallResult(text: String, isError: Boolean): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
        }
        put("isError", isError)
    }

    private fun resultResponse(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun errorResponse(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    // ---- 极简 HTTP/1.1 读写 ----

    private class HttpRequest(
        val method: String,
        val path: String,
        val body: String,
    )

    private class RpcOutcome(
        val body: String,
        val status: Int,
        val sessionId: String?,
    )

    private fun readRequest(input: InputStream): HttpRequest? {
        val requestLine = readLine(input) ?: return null
        if (requestLine.isBlank()) return null
        val parts = requestLine.split(" ")
        if (parts.size < 3) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (length > 0) {
            val buf = ByteArray(length)
            readFully(input, buf)
            String(buf, StandardCharsets.UTF_8)
        } else {
            ""
        }
        return HttpRequest(parts[0], parts[1], body)
    }

    /** 读一行（到 \n），去掉行尾 \r；从字节流按字节读，绝不越过正文长度读到 body。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var any = false
        while (true) {
            val b = input.read()
            if (b == -1) return if (any) sb.toString().trimEnd('\r') else null
            any = true
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
    }

    private fun writeResponse(out: OutputStream, outcome: RpcOutcome) {
        val bodyBytes = outcome.body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (outcome.status) {
            200 -> "OK"
            202 -> "Accepted"
            405 -> "Method Not Allowed"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 ").append(outcome.status).append(' ').append(reason).append("\r\n")
            if (bodyBytes.isNotEmpty()) append("Content-Type: application/json; charset=utf-8\r\n")
            outcome.sessionId?.let { append("Mcp-Session-Id: ").append(it).append("\r\n") }
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
        out.flush()
    }
}
