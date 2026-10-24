package com.aharou.feature.remotedevice.server

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.remotedevice.domain.PairOutcome
import com.aharou.feature.remotedevice.domain.RemoteActionCatalog
import com.aharou.feature.remotedevice.domain.RemoteActionExecutor
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.domain.RemotePairingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
 *  1. 绑 IPv6 通配（回退 `0.0.0.0`；debug 只绑回环），因此多了两层准入：对端须过 [isAllowedPeer]
 *     （默认只放私网，开了「允许公网对端」才放行跨网络来源）、
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
    private val tickets: RemoteFileTicketStore,
    private val mcpConfigRepository: McpConfigRepository,
    @param:ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "RemoteMcpServer"
        const val MCP_PATH = "/mcp"
        const val PAIR_PATH = "/pair"
        const val HEALTH_PATH = "/health"

        /** 文件下载/上传的路径前缀，后面接一次性票据 id（见 [RemoteFileTicketStore]）。 */
        const val FILE_PATH = "/file"
        const val UPLOAD_PATH = "/upload"
        private const val PROTOCOL_VERSION = "2025-06-18"
        private const val SERVER_NAME = "aharou-remote-device"

        /** 单条连接的请求读取超时：只覆盖「读一条请求」，执行耗时不受它限制。 */
        private const val SOCKET_TIMEOUT_MS = 15_000

        /** 文件传输路由的读/写超时：默认的 15 秒对大文件远远不够，且它是空闲超时而非总时长。 */
        private const val FILE_TRANSFER_TIMEOUT_MS = 10 * 60 * 1000
        private const val COPY_BUFFER_BYTES = 64 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024

        /** 反向配对（一次配对即互通）的回连超时：对端应当就在线，超时短一点不拖住本机。 */
        private const val PAIR_BACK_TIMEOUT_MS = 8_000
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

    /** 上一次反向配对（一次配对即互通）失败的原因；null 表示上次成功或还没试过。 */
    private val _pairBackIssue = MutableStateFlow<String?>(null)
    val pairBackIssue: StateFlow<String?> = _pairBackIssue.asStateFlow()

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

    /**
     * 绑定监听端口：首选配置端口；被占就交给系统分配一个空闲端口（`ServerSocket(0)`），永不再因为端口冲突起不来。
     *
     * 优先用固定端口是因为它重启后不变，已配对的对端还能继续连；
     * 一旦换到系统分配的端口，配对信息必须重新给对端。
     */
    private suspend fun bindFallback(preferred: Int): Pair<ServerSocket, Int> {
        bindAt(preferred)?.let { return it to it.localPort }
        val fallback = bindAt(0)
            ?: error("无法创建监听端口（连系统分配的空闲端口都绑不上）")
        FileLogger.w(TAG, "端口 $preferred 被占用，改用系统分配的 ${fallback.localPort}")
        settings.setPort(fallback.localPort)
        return fallback to fallback.localPort
    }

    /** 在 [port] 上建监听 socket（0 = 系统分配）：先 IPv6 通配（双栈，跨网络直连靠它），再回退 IPv4 通配。 */
    private fun bindAt(port: Int): ServerSocket? {
        for (address in listOf("::", "0.0.0.0")) {
            runCatching {
                ServerSocket(port, SERVER_BACKLOG, InetAddress.getByName(address))
            }.getOrNull()?.let { return it }
        }
        return null
    }

    private suspend fun runServer() {
        val preferred = settings.current().port
        // 端口被占（同机正式包与 debug 包都开着协作、旧进程没退）时不能直接失败：端口设置项已不对外暴露，
        // 用户只会拿到一句 EADDRINUSE。往后试十个，成功就改用新端口并写回配置，界面自己跟上。
        val bound = bindFallback(preferred)
        val (socket, port) = bound
        serverSocket = socket
        // 全球 IPv6 排在前面：跨网络协助优先用它，局域网地址作兼底。
        val urls = localIpv6Addresses().map { "http://[$it]:$port$MCP_PATH" } +
            localIpv4Addresses().map { "http://$it:$port$MCP_PATH" }
        _state.value = RemoteServerState.Running(port, urls)
        FileLogger.i(TAG, "远端协作服务已监听端口 $port（IPv6 全球单播 ${urls.count { it.contains("[") }} 条）")

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
            // 是否接受公网对端由配置决定：关闭时只放行同一局域网，开启后才允许跨网络直连。
            val allowPublicPeer = settings.current().allowPublicPeer
            if (!isAllowedPeer(peer, allowPublicPeer)) {
                FileLogger.w(TAG, "拒绝对端（allowPublic=$allowPublicPeer）: ${peer?.hostAddress}")
                writeHttpResponse(socket.getOutputStream(), 403, """{"error":"peer_not_allowed"}""")
                return
            }
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val head = readHttpHead(input) ?: return

            // 文件传输的正文/响应是裸字节流：既不能按 JSON 读成 String，也不吃 256 KB 正文上限，
            // 大文件还会远超默认的 15 秒——所以这两条路由先挑出来单独处理。
            if (head.method == "POST" && head.path.startsWith("$UPLOAD_PATH/")) {
                socket.soTimeout = FILE_TRANSFER_TIMEOUT_MS
                handleUpload(head, input, out)
                return
            }
            if (head.method == "GET" && head.path.startsWith("$FILE_PATH/")) {
                socket.soTimeout = FILE_TRANSFER_TIMEOUT_MS
                handleDownload(head, out)
                return
            }

            val request = try {
                HttpRequest(head.method, head.target, head.headers, readHttpBody(input, head, MAX_BODY_BYTES))
            } catch (e: HttpBodyTooLarge) {
                writeHttpResponse(out, 413, """{"error":"payload_too_large"}""")
                return
            }

            when {
                request.path == HEALTH_PATH && request.method == "GET" ->
                    writeHttpResponse(out, 200, healthBody())
                request.path == PAIR_PATH && request.method == "POST" ->
                    handlePair(request, out, peer)
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

    // ── 文件传输（原生 HTTP 流） ────────────────────────────────

    /** `GET /file/<ticket>`：把票据指向的文件原样流回去。票据一次性、15 分钟时效，不设大小上限。 */
    private fun handleDownload(head: HttpHead, out: OutputStream) {
        val ticket = tickets.consume(head.path.removePrefix("$FILE_PATH/"), TransferDirection.DOWNLOAD)
        if (ticket == null) {
            writeHttpResponse(out, 403, """{"error":"invalid_ticket"}""")
            return
        }
        val file = File(ticket.path)
        if (!file.isFile) {
            writeHttpResponse(out, 404, """{"error":"not_found"}""")
            return
        }
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/octet-stream\r\n")
            append("Content-Disposition: attachment; filename=\"").append(file.name).append("\"\r\n")
            append("Content-Length: ").append(file.length()).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.ISO_8859_1))
        file.inputStream().use { it.copyTo(out, COPY_BUFFER_BYTES) }
        out.flush()
        FileLogger.i(TAG, "已发送文件 ${file.name}（${file.length()} 字节）")
    }

    /** `POST /upload/<ticket>`：按 Content-Length 把请求体原样落成文件。 */
    private fun handleUpload(head: HttpHead, input: InputStream, out: OutputStream) {
        val ticket = tickets.consume(head.path.removePrefix("$UPLOAD_PATH/"), TransferDirection.UPLOAD)
        if (ticket == null) {
            writeHttpResponse(out, 403, """{"error":"invalid_ticket"}""")
            return
        }
        if (head.contentLength <= 0L) {
            writeHttpResponse(out, 400, """{"error":"missing_content_length"}""")
            return
        }
        val file = File(ticket.path)
        val written = runCatching {
            file.parentFile?.mkdirs()
            file.outputStream().use { sink ->
                var remaining = head.contentLength
                val buf = ByteArray(COPY_BUFFER_BYTES)
                while (remaining > 0L) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    remaining -= n
                }
                head.contentLength - remaining
            }
        }.getOrNull()
        if (written == null) {
            writeHttpResponse(out, 500, """{"error":"write_failed"}""")
            return
        }
        writeHttpResponse(out, 200, """{"written":$written}""")
        FileLogger.i(TAG, "已接收文件 ${file.name}（$written 字节）")
    }

    // ── 配对 ──────────────────────────────────────────────────────

    private suspend fun handlePair(request: HttpRequest, out: java.io.OutputStream, peer: InetAddress?) {
        val obj = runCatching { json.parseToJsonElement(request.body) as? JsonObject }.getOrNull()
        val code = (obj?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val deviceName = (obj?.get("name") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val deviceId = (obj?.get("device_id") as? JsonPrimitive)?.contentOrNull.orEmpty()
        // 一次配对即互通：对端顺带把自己的协作端点与一枚码带了过来，配对成功后本机回连一次，
        // 把对方也登记成本机的 MCP 服务器——否则两边各要配一次。缺任一字段则跟老版本一样只开单向。
        val peerEndpoint = (obj?.get("peer_endpoint") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val peerCode = (obj?.get("peer_code") as? JsonPrimitive)?.contentOrNull.orEmpty()
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
                // 先把 token 交给对端再回连：回连失败只影响反向那一段，不影响本次配对结果。
                if (peer != null && peerEndpoint.isNotBlank() && peerCode.isNotBlank() &&
                    settings.current().autoPairBack
                ) {
                    runCatching { pairBack(peer, peerEndpoint, peerCode) }
                        .onSuccess { issue -> _pairBackIssue.value = issue }
                        .onFailure {
                            _pairBackIssue.value = it.message ?: "反向配对异常"
                            FileLogger.w(TAG, "反向配对失败: ${it.message}")
                        }
                }
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

    /**
     * 回连对端完成反向配对：拿对端给的码换它的 token，并把对端登记成本机的一条 MCP 服务器。
     *
     * 地址只认**请求来源 IP**，对端在参数里写的地址不参——它最多能影响端口。这样「配对」就不会
     * 被当成打任意主机的跳板（否则一个掌上有码的人就能让本机去打内网其它机器）。
     */
    private suspend fun pairBack(peer: InetAddress, peerEndpoint: String, peerCode: String): String? {
        // 用 URI 解端口：对端给的端点大概率是 IPv6（http://[240e:...]:47822/mcp），拿「第一个 :数字」
        // 的正则会先撞上 IPv6 里的段（如 :2001），从而回连到完全无关的端口。
        val port = runCatching { URI(peerEndpoint).port }.getOrNull()
            ?.takeIf { it in 1..65535 } ?: settings.current().port
        val host = peer.hostAddress ?: return "对端地址不可用"
        val literal = if (host.contains(':')) "[$host]" else host
        val endpoint = "http://$literal:$port$PAIR_PATH"
        val payload = buildJsonObject {
            put("code", peerCode.trim().uppercase())
            put("name", settings.displayName())
            put("device_id", settings.installId())
        }.toString()
        val http = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = PAIR_BACK_TIMEOUT_MS
                    readTimeout = PAIR_BACK_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                status to body
            }.getOrElse {
                // 连接失败不能静默返回：这条分支没日志的话，反向没生效会看着像「根本没执行」。
                FileLogger.w(TAG, "反向配对连不上 $endpoint：${it.javaClass.simpleName}: ${it.message}")
                null
            }
        } ?: return "连不上对端"
        val (status, body) = http
        if (status !in 200..299) {
            FileLogger.w(TAG, "反向配对被拒（HTTP $status）: $endpoint")
            return "对端拒绝（HTTP $status）"
        }
        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (token.isBlank()) return "对端没有返回 token"
        val serverName = "远端设备 ${if (literal.length > 24) literal.take(24) + "…" else literal}"
        val config = McpServerConfig(
            name = serverName,
            url = "http://$literal:$port$MCP_PATH",
            headers = mapOf("Authorization" to "Bearer $token")
        )
        val existing = runCatching { mcpConfigRepository.getGlobalServers() }.getOrDefault(emptyList())
        mcpConfigRepository.setGlobalServers(existing.filterNot { it.name == config.name } + config)
        FileLogger.i(TAG, "已与对端互通，登记 MCP 服务器「$serverName」")
        return null
    }

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

/**
 * 本机可被**跨网络**直接抵达的全局 IPv6 地址。
 *
 * 与 IPv4 不同，运营商给手机的 IPv6 通常是全球单播（不经 NAT），所以把地址交给对端就能直连，
 * 不需要中间服务器。只取全球单播：回环、链路本地（fe80::/10）与唯一本地地址（fc00::/7）对外都不可达。
 */
internal fun localIpv6Addresses(): List<String> {
    val result = mutableListOf<String>()
    runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (address !is Inet6Address) continue
                if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) continue
                if (address.isUniqueLocalV6()) continue
                // hostAddress 对 IPv6 会带 `%wlan0` 这样的 scope 后缀，写进 URL 会让对端解析失败。
                val literal = address.hostAddress?.substringBefore('%').orEmpty()
                // 2001:db8::/32 是 RFC 3849 的文档专用段，永远不可路由（虚拟网卡/测试接口常挂它）——
                // 列进协助地址只会让对端白连一次。
                if (literal.startsWith("2001:db8:") || literal.startsWith("2001:0db8:")) continue
                if (literal.isNotBlank()) result.add(literal)
            }
        }
    }
    return result.distinct()
}

/** 唯一本地地址（fc00::/7）：Java 的 isSiteLocalAddress 只覆盖已废弃的 fec0::/10，这里单独挡掉。 */
private fun Inet6Address.isUniqueLocalV6(): Boolean {
    val bytes = address
    return bytes.size == 16 && (bytes[0].toInt() and 0xFE) == 0xFC
}
