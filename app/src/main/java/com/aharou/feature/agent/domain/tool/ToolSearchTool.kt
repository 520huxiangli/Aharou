package com.aharou.feature.agent.domain.tool

import com.aharou.core.util.FileLogger
import com.google.gson.Gson
import dagger.Lazy
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 按需加载工具的发现入口：在延迟加载的工具目录（当前是 MCP 工具）里按关键词搜索，
 * 命中后把这些工具的完整定义放进后续每轮的 tools 数组。
 *
 * 为什么需要它：MCP 的工具定义每轮都完整注入时，几个 server 就能吃掉几十万 token，
 * 而单轮实际只会用到其中一两个。改成「平时只给目录、命中才展开」后，
 * 上下文里只留真正要用到的 schema。
 */
class ToolSearchTool @Inject constructor(
    // 必须是 Lazy：ToolRegistry 的 provider 要把本工具注册进去，直接注入会形成依赖环。
    private val toolRegistry: Lazy<ToolRegistry>
) : AgentTool() {

    private companion object {
        const val TAG = "ToolSearchTool"

        /** 单次最多展开的工具数：一次展开太多，等于把刚省下的上下文又还回去。 */
        const val MAX_RESULTS = 10

        /** 单个工具参数 schema 的截断长度，防止一个巨型 schema 独占返回。 */
        const val SCHEMA_MAX_CHARS = 1_200

        val gson = Gson()
    }

    override val name = "tool_search"

    override val description =
        "搜索按需加载的工具目录（MCP 工具默认不可直接调用，要靠它展开）。" +
            "query 传关键词，多个词用空格分隔且需全部命中；留空则列出目录里的全部工具。" +
            "命中的工具会立即展开，从下一轮起可直接调用。"

    // 纯目录查询，不改动任何外部状态，无需逐次过用户审核。
    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "搜索关键词（工具名或用途），多个词用空格分隔；留空列出全部。",
            required = false
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val catalog = toolRegistry.get().getDeferredTools()
        if (catalog.isEmpty()) {
            return ToolResult.Success(JsonPrimitive("按需加载的工具目录是空的（当前没有已连接的 MCP server）。"))
        }

        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val matched = if (terms.isEmpty()) {
            catalog
        } else {
            catalog.filter { tool ->
                val haystack = "${tool.name} ${tool.description}".lowercase()
                terms.all { haystack.contains(it) }
            }
        }
        if (matched.isEmpty()) {
            return ToolResult.Success(
                JsonPrimitive(
                    "没有匹配「$query」的工具。目录里现有 ${catalog.size} 个工具，" +
                        "换个关键词重试，或留空 query 列出全部。"
                )
            )
        }

        val picked = matched.take(MAX_RESULTS)
        toolRegistry.get().activate(picked.map { it.name })
        FileLogger.i(TAG, "tool_search「$query」匹配 ${matched.size} 个，展开 ${picked.size} 个")

        val text = buildString {
            append("已展开 ${picked.size} 个工具，从下一轮起可直接调用：")
            if (matched.size > picked.size) {
                append("（共匹配 ${matched.size} 个，单次最多展开 $MAX_RESULTS 个，可用更具体的关键词缩小范围）")
            }
            picked.forEachIndexed { index, tool ->
                append("\n\n${index + 1}. ${tool.name}")
                if (tool.deferredLoading) append("  [按需加载]")
                append("\n   ${tool.description}")
                val schema = runCatching { gson.toJson(tool.toJsonSchema()) }.getOrDefault("{}")
                append("\n   参数 schema：${schema.take(SCHEMA_MAX_CHARS)}")
            }
        }
        return ToolResult.Success(JsonPrimitive(text))
    }
}
