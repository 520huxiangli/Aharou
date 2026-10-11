package com.aharou.feature.remotedevice

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.remotedevice.domain.RemoteActionCatalog
import com.aharou.feature.remotedevice.domain.RemoteDeviceConfig
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.domain.RemotePairedDevice
import com.aharou.feature.remotedevice.domain.RemotePairingStore
import com.aharou.feature.remotedevice.server.RemoteMcpServer
import com.aharou.feature.remotedevice.server.RemoteServerState
import com.aharou.feature.remotedevice.server.localIpv4Addresses
import com.aharou.feature.remotedevice.service.RemoteDeviceService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

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
    private val catalog: RemoteActionCatalog
) {
    private companion object {
        const val TAG = "RemoteDeviceController"
    }

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
            // 关服务即关掉全部入口，同时清掉已授权与待用配对码：
            // 「关掉开关」在用户心里就是「谁也别再连我」，留着 token 会与这个预期不符。
            server.stop()
            RemoteDeviceService.stop(context)
            pairing.revokeAll()
        }
    }

    suspend fun setPort(port: Int) {
        settings.setPort(port)
        if (settings.current().enabled) server.restart()
    }

    suspend fun setDeviceName(name: String) = settings.setDeviceName(name)

    suspend fun setAlwaysAskRemote(value: Boolean) = settings.setAlwaysAskRemote(value)

    /** 生成（或复用有效期内）一枚 6 位配对码，对端在 5 分钟内用它换长期 token。 */
    suspend fun pairingCode(): String = pairing.issueCode()

    /** 当前仍在有效期内的配对码与到期时刻；无有效码返回 null。 */
    suspend fun livePairingCode(): Pair<String, Long>? = pairing.liveCode()

    suspend fun devices(): List<RemotePairedDevice> = pairing.devices()

    suspend fun revoke(deviceId: String): Boolean = pairing.revoke(deviceId)

    suspend fun revokeAll() = pairing.revokeAll()

    private fun endpointsFor(port: Int, serverState: RemoteServerState): List<String> {
        if (serverState is RemoteServerState.Running && serverState.urls.isNotEmpty()) return serverState.urls
        return localIpv4Addresses().map { "http://$it:$port${RemoteMcpServer.MCP_PATH}" }
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
