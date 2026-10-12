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
import com.aharou.feature.remotedevice.server.localIpv4Addresses
import com.aharou.feature.remotedevice.server.localIpv6Addresses
import com.aharou.feature.remotedevice.service.RemoteDeviceService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 设置页要展示的一份状态快照。 */
data class RemoteDeviceUiState(
    val config: RemoteDeviceConfig,
    val server: RemoteServerState,
    /** 本机可被局域网访问的 MCP 端点（多网卡时多条）。 */
    val endpoints: List<String>,
    /** 本机能力清单（info 会回给对端）。 */
    val capabilities: List<String>
)

/**
 * 「跨设备协作」的统一入口：设置页、应用启动接线都只跟它打交道。
 *
 * 本次改动不做设置页 UI（见报告里的落点方案），本类即 UI 要绑的那层：
 *  - 开关/端口/设备名/是否每次都问 → [setEnabled] / [setPort] / [setDeviceName] / [setAlwaysAskRemote]；
 *  - 配对码展示 → [pairingCode]（打开面板或点「生成配对码」时调用）；
 *  - 已授权设备列表与撤销 → [devices] / [revoke] / [revokeAll]；
 *  - 本机端点 URL → [state] 里的 endpoints（念给对端/生成二维码都用它）。
 *
 * 接入另一台设备（对端 = 本机 agent 连别人的 Aharou）用 [mcpConfigFor]：填进既有 MCP 配置即可，
 * 走现成的 MCP 客户端与工具注册（详见报告「接入方式」）。
 */
@Singleton
class RemoteDeviceController @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: RemoteDeviceSettings,
    private val pairing: RemotePairingStore,
    private val server: RemoteMcpServer,
    private val catalog: RemoteActionCatalog,
    private val mcpConfigRepository: McpConfigRepository
) {
    private companion object {
        const val TAG = "RemoteDeviceController"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 8_000
    }

    private val json = Json { ignoreUnknownKeys = true }

    val state: Flow<RemoteDeviceUiState> = combine(settings.config, server.state) { config, serverState ->
        RemoteDeviceUiState(
            config = config,
            server = serverState,
            endpoints = endpointsFor(config.port, serverState),
            capabilities = catalog.capabilityList()
        )
    }

    /** 应用启动时调用：配置里开着就把服务拉起来（接线点见报告，本次不改 AIEditorApp）。 */
    suspend fun startIfEnabled() {
        if (settings.current().enabled) {
            FileLogger.i(TAG, "配置已开启，启动远端协作服务")
            RemoteDeviceService.start(context)
        }
    }

    suspend fun setEnabled(enabled: Boolean) {
        settings.setEnabled(enabled)
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

    /** 生成（或复用）一枚 6 位配对码；**不过期**，对端拿它换长期 token，配对成功后自动换新。 */
    suspend fun pairingCode(): String = pairing.issueCode()

    /** 当前待用的配对码与到期时刻（固定 0，表示不过期）。 */
    suspend fun livePairingCode(): Pair<String, Long>? = pairing.liveCode()

    suspend fun devices(): List<RemotePairedDevice> = pairing.devices()

    suspend fun revoke(deviceId: String): Boolean = pairing.revoke(deviceId)

    suspend fun revokeAll() = pairing.revokeAll()

    private fun endpointsFor(port: Int, serverState: RemoteServerState): List<String> {
        if (serverState is RemoteServerState.Running && serverState.urls.isNotEmpty()) return serverState.urls
        // 服务没跑时的兵底：只列本机地址，供界面先把地址展示出来。
        return localIpv6Addresses().map { "http://[$it]:$port${RemoteMcpServer.MCP_PATH}" } +
            localIpv4Addresses().map { "http://$it:$port${RemoteMcpServer.MCP_PATH}" }
    }

    /** 接受协助的结果。 */
    sealed interface AssistOutcome {
        /** 配对成功且已登记为 MCP 服务器。 */
        data class Ok(val serverName: String) : AssistOutcome

        /** 连上了但被拒（码不对、太频繁等）。 */
        data class Rejected(val reason: String) : AssistOutcome

        /** 根本没连上（地址/端口不对，或对方没开服务）。 */
        data class Unreachable(val reason: String) : AssistOutcome
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
        if (target.isBlank() || code.isBlank()) return AssistOutcome.Unreachable("地址或配对码为空")

        val endpoint = "http://$target:$port${RemoteMcpServer.PAIR_PATH}"
        val payload = buildJsonObject {
            put("code", code.trim().uppercase())
            put("name", settings.displayName())
            put("device_id", settings.installId())
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
            return AssistOutcome.Unreachable("连不上 $endpoint：检查地址与端口，或确认对方已开启协作")
        }

        val (status, body) = http
        if (status !in 200..299) {
            return AssistOutcome.Rejected(
                when (status) {
                    403 -> "配对码不对，或已被用过（一枚码只能配一台设备）"
                    429 -> "尝试次数过多，稍后再试"
                    else -> "对端返回 HTTP $status"
                }
            )
        }

        val token = runCatching {
            json.parseToJsonElement(body).jsonObject["token"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty()
        if (token.isBlank()) return AssistOutcome.Rejected("对端没有返回 token")

        val serverName = "远端设备 ${if (target.length > 24) target.take(24) + "…" else target}"
        val config = mcpConfigFor(serverName, target, port, token)
        val existing = runCatching { mcpConfigRepository.getGlobalServers() }.getOrDefault(emptyList())
        mcpConfigRepository.setGlobalServers(existing.filterNot { it.name == config.name } + config)
        return AssistOutcome.Ok(serverName)
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
        url = "http://$host:$port${RemoteMcpServer.MCP_PATH}",
        headers = mapOf("Authorization" to "Bearer $token")
    )
}
