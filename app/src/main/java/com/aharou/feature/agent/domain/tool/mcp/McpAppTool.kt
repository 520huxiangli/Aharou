package com.aharou.feature.agent.domain.tool.mcp

import android.content.Context
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.mcp.McpAppGateway
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.presentation.mcpapp.McpAppActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 把 MCP server 通过 resources 暴露的页面当界面跑起来。
 *
 * 只做两件事：列页面、打开页面。真正的交互发生在 [McpAppActivity] 的 WebView 里，
 * 页面自己经桥回调 `tools/call`，不经过这个工具。所以工具的职责边界很窄，
 * 也不需要在这里做权限审批——它不改变任何数据，只是打开一个窗口。
 */
@Singleton
class McpAppTool @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val gateway: McpAppGateway,
) : AgentTool() {

    private companion object {
        const val TAG = "McpAppTool"
        val ACTIONS = listOf("list", "open")
    }

    override val name = "mcp_app"

    override val description =
        "打开 MCP server 提供的界面（server 用 resources 暴露 HTML 页面时才有）。action 取值：" +
            "list=列出某条 server 可当界面跑的资源（server 必填）；" +
            "open=在独立窗口里打开它（server 与 resource 必填，resource 取 list 返回的 uri）。" +
            "页面里的交互由它自己处理，不需要你代为调用工具。server 未连接时会明确报错。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action",
            ParameterType.STRING,
            "操作：list / open",
            true,
            enum = ACTIONS,
        ),
        "server" to ToolParameter("server", ParameterType.STRING, "MCP server 名（与设置里显示的一致）", true),
        "resource" to ToolParameter("resource", ParameterType.STRING, "resource 专用：要打开的资源 uri，取自 list 的结果", false),
        "workspace" to ToolParameter("workspace", ParameterType.STRING, "可选：该 server 所在的工作区路径；留空用当前工作区", false),
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (action !in ACTIONS) {
            return ToolResult.Error("缺少或不支持的 action（${ACTIONS.joinToString(" / ")}）", "MISSING_ACTION")
        }
        val server = args["server"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (server.isEmpty()) return ToolResult.Error("mcp_app 需要 server", "MISSING_SERVER")
        val workspace = args["workspace"]?.jsonPrimitive?.contentOrNull?.trim()?.ifBlank { null }

        return try {
            when (action) {
                "list" -> {
                    val resources = gateway.listAppResources(server, workspace)
                    if (resources.isEmpty()) {
                        ToolResult.Success(buildJsonObject {
                            put("server", server)
                            put("count", 0)
                            put("note", "这条 server 没有声明可当界面跑的 HTML 资源；也可能它根本没连上。")
                        })
                    } else {
                        ToolResult.Success(buildJsonObject {
                            put("server", server)
                            put("count", resources.size)
                            put("resources", buildJsonArray {
                                resources.forEach { resource ->
                                    add(buildJsonObject {
                                        put("uri", resource.uri)
                                        put("name", resource.name.orEmpty())
                                        put("description", resource.description.orEmpty())
                                        put("mimeType", resource.mimeType.orEmpty())
                                    })
                                }
                            })
                        })
                    }
                }

                "open" -> {
                    val resource = args["resource"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (resource.isEmpty()) {
                        ToolResult.Error("open 需要 resource（取 list 返回的 uri）", "MISSING_RESOURCE")
                    } else if (gateway.client(server, workspace) == null) {
                        ToolResult.Error("MCP server 未连接：$server", "SERVER_NOT_CONNECTED")
                    } else {
                        McpAppActivity.start(context, server, resource, workspace)
                        ToolResult.Success(buildJsonObject {
                            put("opened", true)
                            put("server", server)
                            put("resource", resource)
                            put("note", "已在独立窗口打开。页面里的操作由它自己完成，你不需要再代为调用工具。")
                        })
                    }
                }

                else -> ToolResult.Error("不支持的 action：$action", "INVALID_ACTION")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "mcp_app 执行失败：$action", e)
            ToolResult.Error("MCP 应用操作失败：${e.message}", "MCP_APP_FAILED")
        }
    }
}
