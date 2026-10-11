package com.aharou.feature.remotedevice.server

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.remotedevice.domain.PairOutcome
import com.aharou.feature.remotedevice.domain.RemoteActionCatalog
import com.aharou.feature.remotedevice.domain.RemoteActionExecutor
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.domain.RemotePairingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
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

/** 服务的运行状态（设置页与前台通知据此展示）。 */
sealed interface RemoteServerState {
    data object Stopped : RemoteServerState

    data class Running(val port: Int, val urls: List<String>) : RemoteServerState

    data class Failed(val message: String) : RemoteServerState
}

/**
 * 本机能力服务端：局域网内的 MCP Streamable HTTP 服务，把本机能力暴露成 MCP 工具给已配对设备。
 *
 * 协议对齐既有的 `feature/agent/domain/mcp/StreamableHttpTransport.kt` 与 debug 变体的
 * `debugmcp/DebugMcpServer.kt`（单一端点 POST JSON-RPC、initialize 回 `Mcp-Session-Id`、
 * 通知回 202、协议版本 `2025-06-18`）——不另造协议，对端用现成 MCP 客户端即可接入。
 *
 * 与 debug 实现的三处产品化差别：
 *  1. 绑 `0.0.0.0`（debug 只绑回环），因此多了两层准入：对端必须是私网地址（[isPrivatePeer]）、
 *     请求必须带已配对的 Bearer token；
 *  2. 多了 `/pair`（配对码换 token）与 `/health`（无鉴权探活，只回服务名与版本）；
 *  3. 正文大小、并发连接与每台设备的动作并发都设了上限，避免被一个局域网对端拖垮。
 *
 * 端口从配置读（默认 [RemoteDeviceSettings] 的 47822），改端口需重启服务（由控制器负责）。
 */
@Singleton
class RemoteMcpServer @Inject constructor(
    private val settings: RemoteDeviceSettings,
    private val pairing: RemotePairingStore,
    private val catalog: RemoteActionCatalog,
    private val executor: RemoteActionExecutor,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RemoteMcpServer"
        const val MCP_PATH = "/mcp"
        const val PAIR_PATH = "/pair"
        const val HEALTH_PATH = "/health"
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_NAME = "aharou-remote-device"

        /** 单条连接的请求读取超时：只覆盖「读一条请求」，执行耗时不受它限制。 */
        private const val SOCKET_TIMEOUT_MS = 15_000
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val MAX_CONNECTIONS = 8
        private const val SERVER_BACKLOG = 16
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = Semaphore(MAX_CONNECTIONS)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    private val _state = MutableStateFlow<RemoteServerState>(RemoteServerState.Stopped)
    val state: StateFlow<RemoteServerState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    /** 幂等启动：已在跑就直接返回。 */
    fun start() {
        if (acceptJob?.isActive == true) return
        acceptJob = scope.launch { runServer() }
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _state.value = RemoteServerState.Stopped
        FileLogger.i(TAG, "远端协作服务已停止")
    }

    /** 重启（改端口后调用）：先停再起。 */
    fun restart() {
        stop()
        start()
    }

    private suspend fun runServer() {
        val config = settings.current()
        val port = config.port
        val socket = try {
            ServerSocket(port, SERVER_BACKLOG, InetAddress.getByName("0.0.0.0"))
        } catch (e: Exception) {
            FileLogger.e(TAG, "无法绑定 0.0.0.0:$port", e)
            _state.value = RemoteServerState.Failed(e.message ?: e.javaClass.simpleName)
            return
        }
        serverSocket = socket
        val urls = localIpv4Addresses().map { "http://$it:$port$MCP_PATH" }
        _state.value = RemoteServerState.Running(port, urls)
        FileLogger.i(TAG, "远端协作服务已监听 0.0.0.0:$port$MCP_PATH（局域网可达）")

        while (scope.isActive && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                FileLogger.i(TAG, "accept 结束: ${e.message}")
                break
            }
            if (!connections.tryAcquire()) {
                runCatching { client.close() }
                FileLogger.w(TAG, "并发连接已达上限，拒绝来自 ${client.inetAddress?.hostAddress} 的连接")
                continue
            }
            scope.launch {
                try {
                    handleConnection(client)
                } finally {
                    connections.release()
                }
            }
        }
        runCatching { socket.close() }
    }

    private suspend fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val peer = socket.inetAddress
            if (!isPrivatePeer(peer)) {
                FileLogger.w(TAG, "拒绝非私网对端: ${peer?.hostAddress}")
                writeHttpResponse(socket.getOutputStream(), 403, """{"error":"forbidden"}""")
                return
            }
            val out = socket.getOutputStream()
            val request = try {
                readHttpRequest(BufferedInputStream(socket.getInputStream()), MAX_BODY_BYTES)
            } catch (e: HttpBodyTooLarge) {
                writeHttpResponse(out, 413, """{"error":"payload_too_large"}""")
                return
            } ?: return

            when {
                request.path == HEALTH_PATH && request.method == "GET" ->
                    writeHttpResponse(out, 200, healthBody())
                request.path == PAIR_PATH && request.method == "POST" ->
                    handlePair(request, out)
                request.path == MCP_PATH && request.method == "POST" ->
                    handleMcp(request, out)
                request.path == MCP_PATH ->
                    writeHttpResponse(
                        out, 405, """{"error":"method_not_allowed"}""",
                        headers = mapOf("Allow" to "POST")
                    )
                else -> writeHttpResponse(out, 404, """{"error":"not_found"}""")
            }
        } catch (e: Exception) {
            FileLogger.w(TAG, "连接处理异常: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    // ── 配对 ──────────────────────────────────────────────────────

    private suspend fun handlePair(request: HttpRequest, out: java.io.OutputStream) {
        val obj = runCatching { json.parseToJsonElement(request.body) as? JsonObject }.getOrNull()
        val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val deviceName = (obj?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val deviceId = (obj?.get("device_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
        when (val outcome = pairing.pair(code, deviceName, deviceId)) {
            is PairOutcome.Ok -> {
                val body = buildJsonObject {
                    put("token", outcome.token)
                    put("device_id", outcome.device.deviceId)
                    put("device_name", outcome.device.deviceName)
                    put("protocol_version", PROTOCOL_VERSION)
                    put("path", MCP_PATH)
                }.toString()
                writeHttpResponse(out, 200, body)
            }
            PairOutcome.InvalidCode ->
                writeHttpResponse(out, 403, """{"error":"invalid_code"}""")
            is PairOutcome.Throttled ->
                writeHttpResponse(
                    out, 429,
                    """{"error":"too_many_attempts","retry_after_ms":${outcome.retryAfterMs}}"""
                )
        }
    }

    private fun healthBody(): String = buildJsonObject {
        put("service", SERVER_NAME)
        put("protocol", "mcp")
        put("protocol_version", PROTOCOL_VERSION)
        put("version", appVersion())
    }.toString()

    // ── MCP（Streamable HTTP） ────────────────────────────────────

    private suspend fun handleMcp(request: HttpRequest, out: java.io.OutputStream) {
        val device = pairing.authenticate(bearerToken(request))
        if (device == null) {
            FileLogger.w(TAG, "MCP 调用未通过鉴权")
            writeHttpResponse(
                out, 401, """{"error":"unauthorized"}""",
                headers = mapOf("WWW-Authenticate" to "Bearer")
            )
            return
        }

        val obj = runCatching { json.parseToJsonElement(request.body) as? JsonObject }.getOrNull()
            ?: run {
                writeHttpResponse(out, 200, rpcError(null, -32700, "Parse error"))
                return
            }
        val idEl = obj["id"]
        val method = (obj["method"] as? JsonPrimitive)?.contentOrNull

        // 无 id → 通知（如 notifications/initialized）：按 spec 回 202 无体。
        if (idEl == null || idEl is JsonNull) {
            writeHttpResponse(out, 202, "")
            return
        }
        if (method.isNullOrBlank()) {
            writeHttpResponse(out, 200, rpcError(idEl, -32600, "Invalid Request"))
            return
        }

        when (method) {
            "initialize" -> {
                val sessionId = UUID.randomUUID().toString()
                writeHttpResponse(
                    out, 200, rpcResult(idEl, initializeResult()),
                    headers = mapOf("Mcp-Session-Id" to sessionId)
                )
            }
            "tools/list" -> writeHttpResponse(out, 200, rpcResult(idEl, toolsListResult()))
            "tools/call" -> writeHttpResponse(out, 200, rpcResult(idEl, callTool(obj["params"] as? JsonObject, device)))
            "ping" -> writeHttpResponse(out, 200, rpcResult(idEl, buildJsonObject { }))
            else -> writeHttpResponse(out, 200, rpcError(idEl, -32601, "Method not found: $method"))
        }
    }

    private suspend fun callTool(
        params: JsonObject?,
        device: RemotePairedDevice
    ): JsonObject {
        val name = (params?.get("name") as? JsonPrimitive)?.contentOrNull
        val action = name?.let { catalog.byName(it) }
        if (action == null) {
            return toolResult("未知动作：${name ?: "(missing name)"}", isError = true)
        }
        val args = (params?.get("arguments") as? JsonObject).orEmpty()
        val result = executor.execute(action, args, device)
        return toolResult(result.text, result.isError, result.images)
    }

    private fun initializeResult(): JsonObject = buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        putJsonObject("capabilities") { putJsonObject("tools") { } }
        putJsonObject("serverInfo") {
            put("name", SERVER_NAME)
            put("version", appVersion())
        }
    }

    private fun toolsListResult(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            catalog.actions.forEach { action ->
                addJsonObject {
                    put("name", action.name)
                    put("description", action.description)
                    put("inputSchema", catalog.inputSchema(action))
                }
            }
        }
    }

    private fun toolResult(
        text: String,
        isError: Boolean,
        images: List<AgentImage> = emptyList()
    ): JsonObject = buildJsonObject {
        putJsonArray("content") {
            addJsonObject {
                put("type", "text")
                put("text", text)
            }
            images.forEach { image ->
                addJsonObject {
                    put("type", "image")
                    put("data", image.base64Data)
                    put("mimeType", image.mimeType)
                }
            }
        }
        put("isError", isError)
    }

    private fun rpcResult(id: JsonElement, result: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", result)
    }.toString()

    private fun rpcError(id: JsonElement?, code: Int, message: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonNull)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }.toString()

    /** 只认 `Authorization: Bearer <token>`；对端用 MCP 配置里的静态 header 即可带上。 */
    private fun bearerToken(request: HttpRequest): String? =
        request.header("authorization")
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring("Bearer ".length)
            ?.trim()

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "unknown"
}

/**
 * 本机可在局域网内被访问的 IPv4 地址（参考 `FtpServerManager.getLocalIpAddress()` 的枚举方式，
 * 这里返回全部而不是第一个：手机可能同时有 Wi-Fi 与热点两个网段）。
 */
internal fun localIpv4Addresses(): List<String> {
    val result = mutableListOf<String>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    address.hostAddress?.let { result.add(it) }
                }
            }
        }
    }
    return result.distinct()
}
