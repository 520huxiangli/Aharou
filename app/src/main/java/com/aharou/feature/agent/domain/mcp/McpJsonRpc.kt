package com.aharou.feature.agent.domain.mcp

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 最小化 JSON-RPC 2.0 报文模型，用于与 MCP server 通信。
 *
 * 只覆盖 MCP 当前需要的子集：带 id 的请求/响应、不带 id 的通知（notification）。
 * id 用整型递增；服务端必须原样回传，[McpClient] 据此把响应配对到请求。
 */
const val JSONRPC_VERSION = "2.0"

@Serializable
data class JsonRpcRequest(
    val id: Long,
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

/** 通知没有 id，服务端不应答（如 notifications/initialized）。 */
@Serializable
data class JsonRpcNotification(
    val method: String,
    val params: JsonElement? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

@Serializable
data class JsonRpcResponse(
    val id: Long? = null,
    val result: JsonObject? = null,
    val error: JsonRpcError? = null,
    val jsonrpc: String = JSONRPC_VERSION
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)

/** 调用 MCP server 失败时抛出，携带 JSON-RPC 错误码便于上层区分。 */
class McpException(
    val rpcCode: Int? = null,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

// ---- MCP 领域模型（initialize / tools/list 的返回片段）----

@Serializable
data class McpToolDescriptor(
    val name: String,
    val description: String? = null,
    @SerialName("inputSchema") val inputSchema: JsonObject? = null
)

@Serializable
data class McpToolsListResult(
    val tools: List<McpToolDescriptor> = emptyList(),
    val nextCursor: String? = null
)

// ---- MCP 资源（resources/*）----

/**
 * `resources/list` 里的一条资源。
 *
 * 字段都做成可空且带默认值：server 实现普遍不严格遵循规范，少个 name / mimeType 不该让整张表解析失败。
 */
@Serializable
data class McpResource(
    val uri: String,
    val name: String? = null,
    val description: String? = null,
    val mimeType: String? = null
)

@Serializable
data class McpResourceListResult(
    val resources: List<McpResource> = emptyList(),
    val nextCursor: String? = null
)

/**
 * `resources/read` 的内容块：text 与 blob 二选一。
 * blob 是 base64（二进制资源），这里只留长度信息给占位描述，不把大段 base64 拼进上下文。
 */
@Serializable
data class McpResourceContent(
    val uri: String? = null,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null
)

@Serializable
data class McpResourceReadResult(
    val contents: List<McpResourceContent> = emptyList()
)
