package com.aharou.feature.agent.domain.mcp

import com.aharou.core.util.FileLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * 单个 MCP server 的会话客户端：负责握手、列举工具、转发工具调用。
 *
 * 生命周期：[connect] 完成 initialize → notifications/initialized → tools/list 后，
 * [tools] 即为该 server 暴露的工具描述符；[callTool] 把一次调用转成 `tools/call`。
 * 任何一步失败都抛 [McpException]，由上层（McpManager）按 server 粒度兜底，不影响其它 server。
 */
class McpClient(
    val serverName: String,
    private val transport: McpTransport,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private companion object {
        const val TAG = "McpClient"
        const val PROTOCOL_VERSION = "2025-06-18"
    }

    @Volatile
    var tools: List<McpToolDescriptor> = emptyList()
        private set

    /** 完整握手：initialize → initialized 通知 → tools/list。返回拿到的工具数。 */
    suspend fun connect(): Int {
        FileLogger.i(TAG, "[$serverName] 开始连接")
        initialize()
        transport.notify("notifications/initialized")
        val count = refreshTools()
        FileLogger.i(TAG, "[$serverName] 握手完成，发现 $count 个工具")
        return count
    }

    private suspend fun initialize() {
        val params = buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") { /* 客户端暂不声明额外能力 */ }
            putJsonObject("clientInfo") {
                put("name", "ai-code-editor")
                put("version", "1.0.0")
            }
        }
        FileLogger.i(TAG, "[$serverName] 发送 initialize（协议 v$PROTOCOL_VERSION）")
        val t0 = System.currentTimeMillis()
        transport.request("initialize", params)
        FileLogger.i(TAG, "[$serverName] initialize 完成（${System.currentTimeMillis() - t0}ms）")
        // 返回的 serverInfo / capabilities 暂不使用，只要不报错即视为握手成功。
    }

    /** 重新拉取工具列表（配置变更或手动刷新时调用）。 */
    suspend fun refreshTools(): Int {
        FileLogger.i(TAG, "[$serverName] 拉取工具列表 tools/list")
        val t0 = System.currentTimeMillis()
        val response = transport.request("tools/list")
        val result = response.result ?: throw McpException(message = "[$serverName] tools/list 无 result")
        tools = runCatching {
            json.decodeFromJsonElement(McpToolsListResult.serializer(), result).tools
        }.getOrElse {
            throw McpException(message = "[$serverName] 解析工具列表失败: ${it.message}", cause = it)
        }
        FileLogger.i(TAG, "[$serverName] tools/list 完成，${tools.size} 个工具（${System.currentTimeMillis() - t0}ms）")
        return tools.size
    }

    /**
     * 执行一次工具调用，返回扁平化为文本的结果（喂回模型）与是否报错。
     * MCP 的 `tools/call` 结果是 content 块数组；这里把 text 块拼接，其它类型给出占位描述。
     * [McpCallResult.isError] 直接取自结果里的 `isError` 字段，避免把文本前缀当判据。
     */
    suspend fun callTool(toolName: String, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", toolName)
            put("arguments", arguments)
        }
        val response = transport.request("tools/call", params)
        val result = response.result ?: throw McpException(message = "[$serverName] tools/call 无 result")

        val isError = (result["isError"] as? JsonPrimitive)?.contentOrNull == "true"
        val text = flattenContent(result["content"])
        return McpCallResult(text, isError)
    }

    /** 把 content 数组里的块转成纯文本：text 块取 text；其它块给类型占位。 */
    private fun flattenContent(content: kotlinx.serialization.json.JsonElement?): String {
        val array = content as? JsonArray ?: return ""
        val sb = StringBuilder()
        for (block in array) {
            val obj = block as? JsonObject ?: continue
            when ((obj["type"] as? JsonPrimitive)?.contentOrNull) {
                "text" -> sb.append((obj["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                "image" -> sb.append("[image content]")
                "resource" -> sb.append("[resource content]")
                else -> obj["text"]?.let { sb.append((it as? JsonPrimitive)?.contentOrNull.orEmpty()) }
            }
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }

    fun close() = transport.close()

    /** 底层连接是否还活着（stdio 看子进程）；false 说明这条 client 已死，需重建。 */
    val isAlive: Boolean get() = transport.isAlive
}

/** 一次 `tools/call` 的结果：扁平化文本 + 是否报错（源自 MCP 结果的 `isError` 字段）。 */
data class McpCallResult(val text: String, val isError: Boolean)
