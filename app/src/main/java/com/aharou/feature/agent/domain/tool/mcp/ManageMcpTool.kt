package com.aharou.feature.agent.domain.tool.mcp

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpConfigRepository
import com.aharou.feature.agent.domain.mcp.McpScope
import com.aharou.feature.agent.domain.mcp.McpServerConfig
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

class ManageMcpTool @Inject constructor(
    private val mcpConfigRepository: McpConfigRepository
) : AgentTool() {
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

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("缺少 action 参数")
        val scope = resolveScope(args)
        val scopeLabel = if (scope == McpScope.PROJECT) "当前项目" else "全局"

        suspend fun readServers(): List<McpServerConfig> =
            if (scope == McpScope.PROJECT) mcpConfigRepository.getProjectServers() else mcpConfigRepository.getGlobalServers()

        suspend fun writeServers(servers: List<McpServerConfig>) {
            if (scope == McpScope.PROJECT) mcpConfigRepository.setProjectServers(servers) else mcpConfigRepository.setGlobalServers(servers)
        }

        return try {
            when (action) {
                "list" -> {
                    val servers = readServers()
                    ToolResult.Success(JsonPrimitive(mcpConfigRepository.serialize(servers)))
                }
                "remove" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("remove 缺少 server_name")
                    val servers = readServers().toMutableList()
                    val removed = servers.removeIf { it.name == name }
                    if (removed) {
                        writeServers(servers)
                        ToolResult.Success(JsonPrimitive("已成功移除 $scopeLabel MCP server: $name"))
                    } else {
                        ToolResult.Error("未找到 $scopeLabel MCP server: $name")
                    }
                }
                "add_stdio" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val command = args["command"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_stdio 缺少 command")
                    val commandArgs = args["args"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
                    
                    val servers = readServers().toMutableList()
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
                    )

                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    writeServers(servers)
                    
                    ToolResult.Success(JsonPrimitive("成功添加 $scopeLabel 本地 MCP server: $name。配置将在下一次会话生效。若命令依赖 Node/Python 等运行时，请通过命令工具在用户确认后安装。"))
                }
                "add_http" -> {
                    val name = args["server_name"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 server_name")
                    if (!McpServerConfig.isValidName(name)) return ToolResult.Error(invalidNameMessage(name))
                    val url = args["url"]?.jsonPrimitive?.contentOrNull ?: return ToolResult.Error("add_http 缺少 url")
                    
                    val servers = readServers().toMutableList()
                    // 同 add_stdio：同名覆盖时保留旧凭据（headers 里常见 API Key，oauth 里是授权令牌）。
                    val existing = servers.firstOrNull { it.name == name }
                    val newServer = McpServerConfig(
                        name = name,
                        url = url,
                        headers = existing?.headers ?: emptyMap(),
                        enabled = existing?.enabled ?: true,
                        disabledTools = existing?.disabledTools ?: emptySet(),
                        oauth = existing?.oauth,
                    )

                    servers.removeIf { it.name == name }
                    servers.add(newServer)
                    writeServers(servers)
                    
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
