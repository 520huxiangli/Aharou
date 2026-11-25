package com.aharou.feature.agent.domain.mcp

import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonObject

/**
 * MCP App 的调用网关：把内嵌界面要做的事收敛成四个动作。
 *
 * 所谓 MCP App，就是 server 通过 MCP 的 resources 暴露一个 HTML 页面，客户端把它当界面跑起来，
 * 页面里的交互再经 `tools/call` 回到同一条 server 上。这里只做转发，不缓存、不改写结果。
 *
 * 三条边界：
 * 1. **只连指定 server**：每个方法都要求 server 名，不会跨 server 借工具。页面是外部内容，
 *    不能让它顺手调到别的 server 的能力。
 * 2. **拿不到连接就如实报错**：server 未连接时抛异常，让界面显示原因，而不是白屏装作在加载。
 * 3. **不持有 WebView**：这一层不知道界面的存在，方便在别处（工具、测试）复用。
 *
 * [Lazy] 不是为了省事：McpManager → ToolRegistry → McpAppTool → 本类 → McpManager 会形成
 * 依赖环（Hilt 编译期直接报错）。这里只要「用到时才拿」，构造期不去解析依赖，环就断开了。
 */
@Singleton
class McpAppGateway @Inject constructor(
    private val mcpManagerLazy: Lazy<McpManager>,
) {

    private val mcpManager: McpManager get() = mcpManagerLazy.get()

    /** 取某条 server 的客户端；server 不在当前工作区的连接表里时返回 null。 */
    fun client(server: String, workspacePath: String?): McpClient? = runCatching {
        if (workspacePath.isNullOrBlank()) {
            mcpManager.clientFor(server)
        } else {
            mcpManager.clientFor(server, workspacePath)
        }

    }.getOrNull()

    /** 列出可当界面跑的资源（mimeType 为 HTML 的那几条）。 */
    suspend fun listAppResources(server: String, workspacePath: String?): List<McpResource> {
        val client = client(server, workspacePath) ?: return emptyList()
        return client.listResources().filter { resource ->
            resource.mimeType?.contains("html", ignoreCase = true) == true
        }
    }

    /** 读入口页面的 HTML。 */
    suspend fun readEntry(server: String, uri: String, workspacePath: String?): String {
        val client = client(server, workspacePath)
            ?: throw IllegalStateException("MCP server 未连接：$server")
        return client.readResource(uri)
    }

    /** 页面发起的一次工具调用。 */
    suspend fun callTool(
        server: String,
        toolName: String,
        arguments: JsonObject,
        workspacePath: String?,
    ): McpCallResult {
        val client = client(server, workspacePath)
            ?: throw IllegalStateException("MCP server 未连接：$server")
        return client.callTool(toolName, arguments)
    }
}
