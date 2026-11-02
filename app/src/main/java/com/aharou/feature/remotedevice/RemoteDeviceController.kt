package com.aharou.feature.remotedevice

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.remotedevice.domain.RemoteActionCatalog
import com.aharou.feature.remotedevice.domain.RemoteDeviceConfig
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.domain.RemotePairingStore
import com.aharou.feature.remotedevice.server.RemoteMcpServer
import com.aharou.feature.remotedevice.server.RemoteServerState
import com.aharou.feature.remotedevice.service.RemoteDeviceService
import com.aharou.feature.workspace.domain.WorkspacePathMapper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 一段「两端动作清单不一致」的提示数据。
 *
 * 控制器不持有用户可见文字（文案由 UI 层用资源渲染），这里只给渲染所需的事实。
 */
data class RemoteActionMismatch(
    /** 对端标识（接受协助时填的地址）。 */
    val peer: String,
    /** 对端 initialize 回包里带的指纹。 */
    val peerFingerprint: String,
    /** 本机清单的指纹。 */
    val localFingerprint: String
)

/** 设置页要展示的一份状态快照。 */
data class RemoteDeviceUiState(
    val config: RemoteDeviceConfig,
    val server: RemoteServerState,
    /** 本机可被局域网访问的 MCP 端点（多网卡时多条）。 */
    val endpoints: List<String>,
    /** 本机能力清单（info 会回给对端）。 */
    val capabilities: List<String>,
    /** 上一次反向配对失败的原因；null 表示没出问题。 */
    val pairBackIssue: String? = null,
    /**
     * 上一次接受协助时发现的两端动作清单指纹不一致；null = 一致 / 对端老版本没这项 / 还没探过。
     */
    val actionMismatch: RemoteActionMismatch? = null
)

/**
 * 「跨设备协作」的统一入口：设置页、应用启动接线都只跟它打交道。
 *
 * 设置页（`presentation/RemoteDeviceSection.kt`）绑的就是这层：
 *  - 开关/端口/设备名/是否每次都问 → [setEnabled] / [setPort] / [setDeviceName] / [setAlwaysAskRemote]；
 *  - 配对码展示 → [pairingCode]（打开面板或点「生成配对码」时调用）；
 *  - 已授权设备列表与撤销 → [devices] / [revoke] / [revokeAll]；
 *  - 本机端点 URL → [state] 里的 endpoints（念给对端/生成二维码都用它）。
 *
 * 接入另一台设备（对端 = 本机 agent 连别人的 Aharou）用 [mcpConfigFor]：填进既有 MCP 配置即可，
 * 走现成的 MCP 客户端与工具注册。
 */
@Singleton
class RemoteDeviceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: RemoteDeviceSettings,
    private val pairing: RemotePairingStore,
    private val server: RemoteMcpServer,
    private val catalog: RemoteActionCatalog,
    private val mcpConfigRepository: McpConfigRepository,
    private val pathMapper: WorkspacePathMapper
) {
    private companion object {
        const val TAG = "RemoteDeviceController"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 最近一次探到的两端动作清单不一致（供设置页提示）；null 表示一致、没探到或对端老版本。 */
    private val _actionMismatch = MutableStateFlow<RemoteActionMismatch?>(null)

    val state: Flow<RemoteDeviceUiState> = combine(
        settings.config, server.state, server.pairBackIssue, _actionMismatch
    ) { config, serverState, pairBackIssue, actionMismatch ->
        RemoteDeviceUiState(
            config = config,
            server = serverState,
            endpoints = endpointsFor(serverState),
            capabilities = catalog.capabilityList(),
            pairBackIssue = pairBackIssue,
            actionMismatch = actionMismatch
        )
    }

    /** 应用启动时调用：配置里开着就把服务拉起来（接线见 AIEditorApp）。 */
    suspend fun startIfEnabled() {
        val enabled = settings.current().enabled
        // 协作开即放开远端路径越界校验；关则恢复成只允许工作区/容器/配置/记忆/共享区。
        pathMapper.allowEscape = enabled
        if (enabled) {
            FileLogger.i(TAG, "配置已开启，启动远端协作服务")
            RemoteDeviceService.start(context)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
        pathMapper.allowEscape = enabled
        if (enabled) {
            RemoteDeviceService.start(context)
        } else {
            // 关开关 = 停掉服务（谁也连不上），但**不撤销已授权设备**：
            // 协助是长期关系，关一下就要求对端重新配对会让功能没法用。真要断掉某台设备，
            // 走设置页的「撤销」（[revoke]）。
            server.stop()
            RemoteDeviceService.stop(context)
        }
    }

    suspend fun setPort(port: Int) {
        settings.setPort(port)
        if (settings.current().enabled) server.restart()
    }

    suspend fun setDeviceName(name: String) = settings.setDeviceName(name)

    suspend fun setAlwaysAskRemote(value: Boolean) = settings.setAlwaysAskRemote(value)

    suspend fun setAllowPublicPeer(value: Boolean) = settings.setAllowPublicPeer(value)

    suspend fun setAutoPairBack(value: Boolean) = settings.setAutoPairBack(value)

    suspend fun setAllowRemoteAskAi(value: Boolean) = settings.setAllowRemoteAskAi(value)

    /** 生成（或复用）一枚 6 位配对码；**不过期**，对端拿它换长期 token，配对成功后自动换新。 */
    suspend fun pairingCode(): String = pairing.issueCode()

    /** 当前待用的配对码与到期时刻（固定 0，表示不过期）。 */
    suspend fun livePairingCode(): Pair<String, Long>? = pairing.liveCode()

    suspend fun devices(): List<RemotePairedDevice> = pairing.devices()

    suspend fun revoke(deviceId: String): Boolean = pairing.revoke(deviceId)

    suspend fun revokeAll() = pairing.revokeAll()

    private fun endpointsFor(serverState: RemoteServerState): List<String> = when (serverState) {
        is RemoteServerState.Running -> serverState.urls
        // 服务没起来时不能兜底编地址：没人在监听，用户却会把它念给对端去连。
        else -> emptyList()
    }

    /** 接受协助的结果。 */
    sealed interface AssistOutcome {
        /** 配对成功且已登记为 MCP 服务器。 */
        data class Ok(val serverName: String) : AssistOutcome

        /** 失败；[code] 决定界面文案，[detail] 是文案里要填的变量（地址、状态码等）。 */
        data class Failed(val code: AssistError, val detail: String = "") : AssistOutcome
    }

    /** 配对失败原因。文案在 UI 层用资源渲染，控制器不持有用户可见文字。 */
    enum class AssistError {
        EMPTY_INPUT,
        UNREACHABLE,
        INVALID_CODE,
        PEER_NOT_ALLOWED,
        TOO_MANY_ATTEMPTS,
        HTTP_ERROR,
        NO_TOKEN
    }

    /**
     * 接受另一台设备的协助：用对方给的「地址 + 6 位配对码」换长期 token，
     * 并把对端登记成一个 MCP 服务器——之后本机 agent 就能调用它的
     * `info` / `read_file` / `write_file` / `shell` / `screenshot`。
     */
    suspend fun acceptAssistance(host: String, port: Int, code: String): AssistOutcome {
        val target = host.trim()
            .removePrefix("https://").removePrefix("http://")
            .substringBefore('/')
        if (target.isBlank() || code.isBlank()) return AssistOutcome.Failed(AssistError.EMPTY_INPUT)

        val endpoint = "http://${hostLiteral(target)}:$port${RemoteMcpServer.PAIR_PATH}"
        val payload = buildJsonObject {
            put("code", code.trim().uppercase())
            put("name", settings.displayName())
            put("device_id", settings.installId())
            // 一次配对即互通：顺带把自己的协作端点与一枚码发过去，对端配对成功后会回连登记，
            // 省掉「反向再配一次」。老版本对端会忽略这两个字段，行为不变。
            put("peer_endpoint", endpointsFor(server.state.value).firstOrNull().orEmpty())
            put("peer_code", pairing.issueCode())
        }.toString()

        val http: Pair<Int, String>? = withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                status to body
            }.getOrNull()
        }
        if (http == null) {
            return AssistOutcome.Failed(AssistError.UNREACHABLE, endpoint)
        }

        val (status, body) = http
        if (status !in 200..299) {
            // 对端的拒绝一律回 403，只看状态码会把「公网对端被拒」误报成「配对码不对」，
            // 于是用户一直去查码。协议里的 error 字段是唯一能把两者分开的信息。
            val errorCode = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            return AssistOutcome.Failed(
                when (status) {
                    403 -> when (errorCode) {
                        "peer_not_allowed" -> AssistError.PEER_NOT_ALLOWED
                        else -> AssistError.INVALID_CODE
                    }
                    429 -> AssistError.TOO_MANY_ATTEMPTS
                    else -> AssistError.HTTP_ERROR
                },
                if (status in setOf(403, 429)) "" else status.toString()
            )
        }

        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (token.isBlank()) return AssistOutcome.Failed(AssistError.NO_TOKEN)

        val serverName = "远端设备 ${if (target.length > 24) target.take(24) + "…" else target}"
        val config = mcpConfigFor(serverName, target, port, token)
        val existing = runCatching { mcpConfigRepository.getGlobalServers() }.getOrDefault(emptyList())
        mcpConfigRepository.setGlobalServers(existing.filterNot { it.name == config.name } + config)
        // 配对已经落地，探活只负责提示：它失败不会把已经建立好的协助关系弄丢。
        // url 为空说明这份配置不是 HTTP 传输，没有可探的端点。
        config.url?.let { compareActionFingerprint(target, it, token) }
        return AssistOutcome.Ok(serverName)
    }

    /**
     * 比对两端动作清单的指纹，不一致就留一条明确警告并写进状态流。
     *
     * 为什么值得比：两端清单不一致时，本机调用对端没有的动作只会收到「未知动作」这类笼统拒绝，
     * 与「权限不够」在调用方看来一模一样。
     */
    private suspend fun compareActionFingerprint(peer: String, endpoint: String, token: String) {
        val local = catalog.fingerprint()
        val remote = probePeerFingerprint(endpoint, token)
        if (remote == null) {
            // 对端是老版本（回包里没这个字段）或这次没探通：一律不比对，正常放行。
            _actionMismatch.value = null
            return
        }
        if (remote == local) {
            _actionMismatch.value = null
            FileLogger.i(TAG, "两端动作清单指纹一致: $local（对端 $peer）")
            return
        }
        _actionMismatch.value = RemoteActionMismatch(peer, remote, local)
        FileLogger.w(
            TAG,
            "两端动作清单不一致：本机 $local，对端 $peer 是 $remote。" +
                "调用对端没有的动作只会收到笼统拒绝，先对齐两端版本，或改用对端清单里的动作。"
        )
    }

    /**
     * 探一次对端的动作清单指纹：向它的 `/mcp` 发一条 initialize，读回 `serverInfo` 里的扩展字段。
     *
     * 返回 null = **不比对**：对端回包里没有该字段、HTTP 非 2xx、连不上都走这条分支。
     * 这是硬要求：探活只是提示，任何时候都不能因为它把配对或后续调用拦下。
     */
    private suspend fun probePeerFingerprint(endpoint: String, token: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "initialize")
                    putJsonObject("params") {
                        put("protocolVersion", RemoteMcpServer.PROTOCOL_VERSION)
                        putJsonObject("capabilities") { }
                        putJsonObject("clientInfo") { put("name", "aharou-remote-device") }
                    }
                }.toString()
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                val body = (if (status in 200..299) connection.inputStream else connection.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                connection.disconnect()
                if (status !in 200..299) {
                    FileLogger.i(TAG, "对端指纹探活没成功（HTTP $status），本次不做清单比对: $endpoint")
                    return@runCatching null
                }
                json.parseToJsonElement(body).jsonObject["result"]
                    ?.jsonObject?.get("serverInfo")
                    ?.jsonObject?.get(RemoteActionCatalog.FINGERPRINT_FIELD)
                    ?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            }.getOrElse {
                FileLogger.i(
                    TAG,
                    "对端指纹探活没成功（${it.javaClass.simpleName}: ${it.message}），本次不做清单比对: $endpoint"
                )
                null
            }
        }

    /**
     * 组装一条指向「另一台 Aharou」的 MCP 配置：对端地址 + 配对换来的 token。
     * 既有 MCP 客户端支持 Streamable HTTP 与自定义 header，这里把 token 放到 `Authorization: Bearer`，
     * 不需要为远端设备另加传输层。
     */
    fun mcpConfigFor(
        name: String,
        host: String,
        port: Int = RemoteDeviceConfig.DEFAULT_PORT,
        token: String
    ): McpServerConfig = McpServerConfig(
        name = name,
        url = "http://${hostLiteral(host)}:$port${RemoteMcpServer.MCP_PATH}",
        headers = mapOf("Authorization" to "Bearer $token")
    )

    /**
     * 规范成 URL 里合法的主机字面量：IPv6 必须裹方括号。
     *
     * 裸着拼（`http://240e:473:…:47822/pair`）会被 URL 解析成 host=240e、port=473 之类，必失败——
     * 对端给的多半就是 IPv6，手输也一样。
     */
    private fun hostLiteral(host: String): String {
        val bare = host.trim().removePrefix("[").removeSuffix("]")
        return if (bare.contains(':')) "[$bare]" else bare
    }
}
