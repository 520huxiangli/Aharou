package com.aharou.feature.agent.domain.mcp

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧版插件（`manifest.json` + `mcpEntry`）派生的 MCP server 已随插件系统重做下线：
 * 新版插件在 `plugin.json` 里声明 mcp 配置文件，由插件运行时解析后直接注册进 [McpManager]。
 *
 * 保留本类是让 [McpConfigRepository] 的可选合并入口不必改构造；恒返回空列表。
 */
@Singleton
class McpPluginServers @Inject constructor() {

    suspend fun servers(): List<McpServerConfig> = emptyList()
}
