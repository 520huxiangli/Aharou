package com.aharou.feature.remotedevice.crypto

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpEncryption
import com.aharou.feature.agent.domain.mcp.McpEncryptionFactory
import com.aharou.feature.remotedevice.domain.RemoteDeviceSettings
import com.aharou.feature.remotedevice.server.RemoteMcpServer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把「配了 encrypted 的 MCP server」接到远端设备加密通道上。
 *
 * 放在单独的工厂里而不是直接 new 在传输层：`feature/agent/domain/mcp` 不该反过来依赖
 * 远端设备模块，实现通过 Hilt 绑定注入，MCP 侧只认 [McpEncryptionFactory] 这个接口。
 *
 * [allowed] 回答「用户要不要加密」；[create] 回答「这条 server 能不能加密」。两者分开：
 * 总开关关掉是用户的正常选择，不能当成配置错误；而开关开着却造不出通道（没 token、
 * 端点不是 HTTP）就得让连接报错，不能惄惄退回明文。
 */
@Singleton
class AharouEncryptionFactory @Inject constructor(
    private val settings: RemoteDeviceSettings
) : McpEncryptionFactory {

    private companion object {
        const val TAG = "RemoteEncryption"
    }

    /**
     * 总开关关掉时这里直接说「不允许」：MCP 侧据此一律明文，不会把 `encrypted=true` 当成配置错误。
     * 读当前值而不是构造时缓存：开关不重启进程就要生效。
     */
    override suspend fun allowed(): Boolean = settings.current().encryptChannel

    override fun create(endpoint: String, token: String?): McpEncryption? {
        val raw = token?.trim()?.removePrefix("Bearer ")?.removePrefix("bearer ")?.trim()
        if (raw.isNullOrBlank()) {
            FileLogger.w(TAG, "该 server 要求加密但配置里没有 Bearer token: ${labelOf(endpoint)}")
            return null
        }
        val handshakeUrl = handshakeUrlFor(endpoint)
        if (handshakeUrl == null) {
            FileLogger.w(TAG, "无法从端点推出握手地址: ${labelOf(endpoint)}")
            return null
        }
        return AharouSessionClient(handshakeUrl, raw, labelOf(endpoint))
    }

    /** 握手端点与 MCP 端点同源，只换路径：端口、IPv6 方括号都由原始端点带过来。 */
    private fun handshakeUrlFor(endpoint: String): String? {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return null
        val scheme = uri.scheme ?: return null
        if (scheme != "http" && scheme != "https") return null
        val authority = uri.rawAuthority ?: return null
        return "$scheme://$authority${RemoteMcpServer.ENC_HANDSHAKE_PATH}"
    }

    private fun labelOf(endpoint: String): String =
        runCatching { URI(endpoint).rawAuthority }.getOrNull() ?: endpoint
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class RemoteEncryptionModule {
    @Binds
    @Singleton
    abstract fun bindEncryptionFactory(impl: AharouEncryptionFactory): McpEncryptionFactory
}
