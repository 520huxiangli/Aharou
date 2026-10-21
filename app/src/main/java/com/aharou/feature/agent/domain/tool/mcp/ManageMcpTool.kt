package com.aharou.feature.agent.domain.tool.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpManager
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
import com.aharou.feature.agent.domain.mcp.McpUpdateOutcome
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ManageMcpTool @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository,
    // 用 Lazy 断环：McpManager 依赖工具注册表，而注册表里有本工具，直接注入会形成循环。
    private val mcpManager: dagger.Lazy<McpManager>
) : AbstractContextualTool() {
    private companion object {
        const val TAG = "ManageMcpTool"
    }

    override val name = "manageMcp"
    override val description = "管理 Model Context Protocol (MCP) 服务器。支持添加、删除与列表查询。添加/删除会修改 Agent 配置，不会自动安装 Node/Python 等运行时。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_AGENT_CONFIG)

    override fun effectiveCapabilities(args: Map<String, JsonElement>): Set<ToolCapability> {
        return when (args["action"]?.jsonPrimitive?.contentOrNull) {
            "list" -> setOf(ToolCapability.READ_AGENT_CONFIG)
            else -> capabilities
        }
    }

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作类型：list=列出服务器；add_stdio=添加本地 stdio 服务；add_http=添加远程 HTTP 服务；remove=移除服务器",
            enum = listOf("list", "add_stdio", "add_http", "remove")
        ),
        "server_name" to ToolParameter(
            name = "server_name",
            type = ParameterType.STRING,
            description = "MCP 服务器名称（操作非 list 时必填），只能含 ASCII 字母、数字、下划线与连字符",
            required = false
        ),
        "keep_alive" to ToolParameter(
            name = "keep_alive",
            type = ParameterType.BOOLEAN,
            description = "保活开关（默认 false）：true 时连接一直保持，不因空闲被回收；false 时超过 15 分钟没调用就断开，下次调用自动重连",
            required = false
        ),
        "command" to ToolParameter(
            name = "command",
            type = ParameterType.STRING,
            description = "执行命令，如 npx 或 python (仅 add_stdio 必填)",
            required = false
        ),
        "args" to ToolParameter(
            name = "args",
            type = ParameterType.ARRAY,
            description = "命令参数数组 (仅 add_stdio 可选)",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "url" to ToolParameter(
            name = "url",
            type = ParameterType.STRING,
            description = "HTTP 远程服务地址 (仅 add_http 必填)",
            required = false
        ),
        "scope" to ToolParameter(
            name = "scope",
            type = ParameterType.STRING,
            description = "配置作用域：global（全局，默认）或 project（当前项目，仅当前工作区生效，项目级优先于全局）",
            enum = listOf("global", "project"),
            required = false
        )
    )

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: "unknown"
        val server = args["server_name"]?.jsonPrimitive?.contentOrNull ?: "未指定"
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "当前项目" else "全局"
        val summary = when (action) {
            "add_stdio" -> "添加本地 MCP server: $server ($scopeLabel)"
            "add_http" -> "添加 HTTP MCP server: $server ($scopeLabel)"
            "remove" -> "移除 MCP server: $server ($scopeLabel)"
            "list" -> "列出 MCP server ($scopeLabel)"
            else -> "管理 MCP server"
        }
        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "确认修改 MCP 配置",
            summary = summary,
            details = "操作：$action\n服务：$server\n作用域：$scopeLabel\n该操作会修改 Agent 的 MCP 配置，新增配置将在下一次会话生效。",
            argsPreview = argsPreview
        )
    }

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 action 参数")
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "当前项目" else "全局"
        // 项目级配置写到「本会话所属工作区」，不是全局活动工作区：多工作区并行时，
        // 后者会把配置静默写进别人正在用的工作区。
        val targetWorkspace = context.projectRoot.takeIf { it.isNotBlank() }

        /**
         * 原子读-改-写：「读磁盘 → 变换 → 写回」在仓库内部一把锁里做完。
         *
         * 不能用 readServers()+writeServers() 分两步：中间任何外部改动（容器里编辑、别的代码
         * 路径写入）都会被旧快照整片覆盖——已实际发生过「手改的内容被抹、加的新项也没落盘」。
         */
        suspend fun updateServers(
            transform: (List<McpServerConfig>) -> List<McpServerConfig>?
        ): McpUpdateOutcome =
            if (scope == McpScope.PROJECT) mcpConfigRepository.updateProjectServers(targetWorkspace ?: "", transform)
            else mcpConfigRepository.updateGlobalServers(transform)

        fun outcomeFailure(outcome: McpUpdateOutcome): ToolResult = when (outcome) {
            McpUpdateOutcome.NOT_FOUND -> ToolResult.Error("未找到 $scopeLabel MCP server")
            else -> ToolResult.Error("写入$scopeLabel 配置失败，请检查文件权限或容器状态；改动未生效")
        }

        return try {
            when (action) {
                "list" -> {
                    // 列「本工作区**生效**的合并视图」（全局 + 项目，项目同名覆盖），并标每项来源。
                    // 旧实现只读单侧配置、表头却写「本工作区」，会让人以为项目级没加载。
                    val effectiveWs = targetWorkspace ?: mcpManager.get().activeWorkspacePath
                    val entries = mcpConfigRepository.getEffectiveEntriesFor(effectiveWs)
                    val config = mcpConfigRepository.serialize(entries.map { it.server })
                    val origin = entries.joinToString("；") { entry ->
                        val scopeLabel = if (entry.scope == McpScope.PROJECT) "项目" else "全局"
                        val keepAliveLabel = if (entry.server.keepAlive) "（保活）" else ""
                        "${entry.server.name}=$scopeLabel$keepAliveLabel"
                    }
                    val live = mcpManager.get().statusesFor(effectiveWs).joinToString("；") { s ->
                        val err = s.error?.takeIf { it.isNotBlank() }?.let { "——$it" }.orEmpty()
                        "${s.name}=${s.state}$err"
                    }
                    val text = buildString {
                        append("配置（本工作区 $effectiveWs，全局 + 项目合并，项目同名覆盖）：\n")
                        append(config)
                        if (origin.isNotBlank()) append("\n\n来源：$origin")
                        if (live.isNotBlank()) append("\n\n运行状态（工作区 $effectiveWs）：$live")
                        val active = mcpManager.get().activeWorkspacePath
                        if (active.isNotBlank() && active != effectiveWs) {
                            append("\n\n注意：当前工具表生效的是活动工作区（$active），与本会话工作区不同")
                        }
                    }
                    ToolResult.Success(JsonPrimitive(text))
                }
                "remove" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("remove 缺少 server_name")
                    val outcome = updateServers { servers ->
                        if (servers.none { it.name == name }) null else servers.filterNot { it.name == name }
                    }
                    if (outcome != McpUpdateOutcome.OK) return outcomeFailure(outcome)
                    ToolResult.Success(JsonPrimitive("已成功移除 $scopeLabel MCP server: $name"))
                }
                "add_stdio" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 command")
                    val commandArgs = args["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    
                    val outcome = updateServers { servers ->
                        // 同名覆盖时继承旧 server 的凭据类字段：env / headers / oauth 里都可能放着密钥，
                        // 直接重建会把它们清空（令牌/API Key 丢失，用户得重新配）。
                        val existing = servers.firstOrNull { it.name == name }
                        val newServer = McpServerConfig(
                            name = name,
                            command = command,
                            args = commandArgs,
                            headers = existing?.headers ?: emptyMap(),
                            env = existing?.env ?: emptyMap(),
                            enabled = existing?.enabled ?: true,
                            disabledTools = existing?.disabledTools ?: emptySet(),
                            oauth = existing?.oauth,
                            keepAlive = args["keep_alive"]?.jsonPrimitive?.booleanOrNull ?: existing?.keepAlive ?: false,
                        )
                        servers.filterNot { it.name == name } + newServer
                    }
                    if (outcome != McpUpdateOutcome.OK) return outcomeFailure(outcome)

                    ToolResult.Success(JsonPrimitive("成功添加 $scopeLabel 本地 MCP server: $name。配置将在下一次会话生效。若命令依赖 Node/Python 等运行时，请通过命令工具在用户确认后安装。"))
                }
                "add_http" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 url")
                    
                    val outcome = updateServers { servers ->
                        // 同 add_stdio：同名覆盖时保留旧凭据（headers 里常见 API Key，oauth 里是授权令牌）。
                        val existing = servers.firstOrNull { it.name == name }
                        val newServer = McpServerConfig(
                            name = name,
                            url = url,
                            headers = existing?.headers ?: emptyMap(),
                            enabled = existing?.enabled ?: true,
                            disabledTools = existing?.disabledTools ?: emptySet(),
                            oauth = existing?.oauth,
                            keepAlive = args["keep_alive"]?.jsonPrimitive?.booleanOrNull ?: existing?.keepAlive ?: false,
                        )
                        servers.filterNot { it.name == name } + newServer
                    }
                    if (outcome != McpUpdateOutcome.OK) return outcomeFailure(outcome)
                    
                    ToolResult.Success(JsonPrimitive("成功添加 $scopeLabel HTTP MCP server: $name. 配置将在下一次会话生效。"))
                }
                else -> ToolResult.Error("未知的 action: $action")
            }
        } catch (e: Exception) {
            FileLogger.e(TAG, "manage_mcp 执行失败: ${e.message}", e)
            ToolResult.Error("管理 MCP 失败: ${e.message}")
        }
    }

    private fun invalidNameMessage(name: String): String =
        "server_name 只能含 ASCII 字母、数字、下划线与连字符（当前：$name）——server 名称会拼成 mcp__<server_name>__<tool> 送给模型，必须符合工具命名规范。"

    private fun resolveScope(args: Map<String, JsonElement>): McpScope =
        when (args["scope"]?.jsonPrimitive?.contentOrNull) {
            "project", "PROJECT" -> McpScope.PROJECT
            else -> McpScope.GLOBAL
        }
    
}
