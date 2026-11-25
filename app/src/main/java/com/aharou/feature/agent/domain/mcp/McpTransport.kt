package com.aharou.feature.agent.domain.mcp

import kotlinx.serialization.json.JsonElement
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

    /**
     * 注册 server 主动推送的无 id 消息（notification）的接收回调，例如
     * `notifications/resources/updated`。传 null 注销。
     *
     * 默认实现是空操作：不为这条能力改造过的传输实现（含测试替身）行为完全不变，
     * 收到 server 推送照旧丢弃。
     *
     * 实现约定：handler 在传输的读线程上同步调用，**抛异常不得影响读循环**（实现方要自己兜住）；
     * 回调里也不要阻塞，需要耗时处理请自行切协程。
     */
    fun setNotificationHandler(handler: ((method: String, params: JsonElement?) -> Unit)?) {}

    /** 释放底层资源（连接、会话等）。可重复调用。 */
    fun close()

    /**
     * 传输当前是否可用。`close()` 之后为 false，stdio 进程退出 / 管道断开后也应为 false。
     *
     * 管理层靠它识别「还挂在表里但已死」的连接：只靠引用相等会把死连接当成活的重用，
     * 表现就是工具调用一律报 transport 已关闭。
     */
    val isOpen: Boolean get() = true

    /**
     * 这条传输是否仍然可用。默认 true（HTTP 传输不做廉价存活判定）；
     * stdio 实现按子进程存活返回 false，供管理层在进程死亡后摘除死连接。
     */
    val isAlive: Boolean get() = true
}
