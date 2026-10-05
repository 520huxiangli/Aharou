package com.aharou.feature.agent.domain.mcp

import kotlinx.serialization.json.JsonObject

/**
 * MCP 传输层抽象：把一条 JSON-RPC 请求送达 server 并取回单条响应。
 *
 * 故意做成可插拔接口：远程走 [StreamableHttpTransport]、容器内子进程走 [StdioTransport]，
 * [McpClient] 不感知具体传输实现。
 */
interface McpTransport {
    /**
     * 发送一条需要应答的请求，阻塞直到拿到配对的 [JsonRpcResponse]。
     * 传输/协议错误以 [McpException] 抛出。
     */
    suspend fun request(method: String, params: JsonObject? = null): JsonRpcResponse

    /** 发送一条不需要应答的通知（fire-and-forget）。 */
    suspend fun notify(method: String, params: JsonObject? = null)

    /** 释放底层资源（连接、会话等）。可重复调用。 */
    fun close()

    /**
     * 这条传输是否仍然可用。默认 true（HTTP 传输不做廉价存活判定）；
     * stdio 实现按子进程存活返回 false，供管理层在进程死亡后摘除死连接。
     */
    val isAlive: Boolean get() = true
}
