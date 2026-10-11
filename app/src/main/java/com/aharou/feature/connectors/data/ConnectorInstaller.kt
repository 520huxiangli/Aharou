package com.aharou.feature.connectors.data

import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.mcp.McpOAuthConfig
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpServerEntry
import com.aharou.feature.connectors.domain.Connector
import com.aharou.feature.connectors.domain.ConnectorAuthKind
import com.aharou.feature.vault.VaultRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 连接器 → MCP 配置的落地逻辑：把目录里的一条连接器变成一条 [McpServerConfig] 并写进全局配置，
 * 随后让 [McpManager] 重连。密钥类连接器走 [com.aharou.feature.vault.VaultRepository]：
 * 真值存凭据库，配置里只留 `{{vault:NAME}}` 占位符，连接建立时再解析——密钥绝不落进 mcp.json。
 *
 * 不改配置仓库与 MCP 客户端，只用它们已有的 get/set/find/reload/remove 接口。
 */
@Singleton
class ConnectorInstaller @Inject constructor(
    private val configRepository: McpConfigRepository,
    private val mcpManager: McpManager,
    private val vault: VaultRepository
) {
    private companion object {
        const val DEFAULT_HEADER = "Authorization"
    }

    /** 无鉴权：直接写一条只带 url 的配置。 */
    suspend fun installOpen(connector: Connector): McpServerEntry =
        upsert(connector, McpServerConfig(name = connector.id, url = connector.url))

    /**
     * OAuth：写一条带空 oauth 块的配置，使 [com.aharou.feature.agent.domain.mcp.McpOAuthClient]
     * 的授权流程有时间落盘 client id / 端点（它按 server 名更新已有的 oauth 块）。
     */
    suspend fun installOAuth(connector: Connector): McpServerEntry =
        upsert(
            connector,
            McpServerConfig(name = connector.id, url = connector.url, oauth = McpOAuthConfig())
        )

    /**
     * 密钥类（header / query）：密钥存凭据库，配置只写占位符。header 形态写进 [ConnectorAuth.header]
     * （默认 Authorization，可带 [ConnectorAuth.prefix]）；query 形态拼进 URL。
     */
    suspend fun installWithKey(connector: Connector, key: String): McpServerEntry {
        vault.set(connector.vaultName, key.trim())
        val placeholder = "{{vault:${connector.vaultName}}}"
        val config = when (connector.authKind) {
            ConnectorAuthKind.KEY_QUERY -> {
                val param = connector.auth.query?.takeIf { it.isNotBlank() } ?: "key"
                val sep = if (connector.url.contains('?')) '&' else '?'
                McpServerConfig(name = connector.id, url = "${connector.url}$sep$param=$placeholder")
            }
            else -> {
                val header = connector.auth.header?.takeIf { it.isNotBlank() } ?: DEFAULT_HEADER
                val prefix = connector.auth.prefix ?: ""
                McpServerConfig(
                    name = connector.id,
                    url = connector.url,
                    headers = mapOf(header to "$prefix$placeholder")
                )
            }
        }
        return upsert(connector, config)
    }

    suspend fun setEnabled(name: String, enabled: Boolean, scope: McpScope) {
        val base = scopeServers(scope)
        if (base.none { it.name == name }) return
        val updated = base.map { if (it.name == name) it.copy(enabled = enabled) else it }
        write(scope, updated)
        mcpManager.reloadServer(name)
    }

    /** 移除连接：删配置、删对应凭据、断开连接。 */
    suspend fun remove(name: String) {
        val entry = configRepository.findEntry(name) ?: return
        val updated = scopeServers(entry.scope).filterNot { it.name == name }
        write(entry.scope, updated)
        vault.delete("connector_$name")
        mcpManager.removeServer(name)
    }

    /**
     * 写入配置。已存在同 id 条目时保留用户设置（启停、禁用工具、已授权令牌），
     * 只补上本次新增的部分（如 OAuth 首次接入时的空 oauth 块）。
     */
    private suspend fun upsert(connector: Connector, config: McpServerConfig): McpServerEntry {
        val scope = configRepository.findEntry(connector.id)?.scope ?: McpScope.GLOBAL
        val ordered = LinkedHashMap<String, McpServerConfig>()
        scopeServers(scope).forEach { ordered[it.name] = it }
        val prev = ordered[connector.id]
        val merged = if (prev == null) {
            config
        } else {
            config.copy(
                enabled = prev.enabled,
                disabledTools = prev.disabledTools,
                oauth = prev.oauth ?: config.oauth
            )
        }
        ordered[connector.id] = merged
        write(scope, ordered.values.toList())
        mcpManager.reloadServer(connector.id)
        return McpServerEntry(merged, scope)
    }

    private suspend fun scopeServers(scope: McpScope): List<McpServerConfig> =
        if (scope == McpScope.GLOBAL) configRepository.getGlobalServers()
        else configRepository.getProjectServers()

    private suspend fun write(scope: McpScope, servers: List<McpServerConfig>) {
        if (scope == McpScope.GLOBAL) configRepository.setGlobalServers(servers)
        else configRepository.setProjectServers(servers)
    }
}
