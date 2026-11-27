package com.aharou.feature.agent.domain.provider

import com.aharou.core.util.AILogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request

/**
 * Responses WebSocket 通道（`OpenAIAdapter.useResponseWebSocket`）的实现。
 *
 * 与 HTTP 版（`OpenAIAdapter.streamViaResponses`）语义一致，差别只在传输：事件走长连接上的
 * JSON 帧，且同一连接可以带 `previous_response_id` 只发新增消息。单独成文件是因为
 * OpenAIAdapter 已顶到架构门禁的行数上限，新逻辑按项目约定拆到同包新文件。
 */

/**
 * WS 通道的开关与跨轮状态。
 * [previousResponseId] / [submittedMessages] 是同一会话连续多轮只发增量消息的依据；
 * 续传前要确认本地历史没被上下文压缩改写，改写就作废重发。
 */
internal class ResponsesWebSocketState {
    var enabled = false
    var socket: ResponsesWebSocketConnection? = null
    var previousResponseId: String? = null
    var submittedMessages: List<AgentMessage> = emptyList()

    fun reset() {
        socket?.close()
        socket = null
        previousResponseId = null
        submittedMessages = emptyList()
    }
}

/** 一次 WS 请求的状态：整段历史、实际发出的请求体、提交快照与续传依据。 */
internal data class ResponsesSocketRequestState(
    val fullInput: List<Map<String, Any?>>,
    val request: MutableMap<String, Any?>,
    val sentMessages: List<AgentMessage>,
    val previousResponseId: String?
)

internal fun OpenAIAdapter.prepareResponsesSocketRequest(
    systemPrompt: String,
    messages: List<AgentMessage>,
    tools: List<AgentTool>,
    reasoningEffort: String?,
    fastMode: Boolean
): ResponsesSocketRequestState {
    val fullInput = rebuildResponsesFullInput(systemPrompt, messages)
    val continuation = prepareResponsesContinuationInput(
        fullInput = fullInput,
        messages = messages,
        submittedMessages = responsesWebSocket.submittedMessages,
        previousResponseId = responsesWebSocket.previousResponseId
    )
    // 能续传时 input 只装增量，历史由服务端凭 previous_response_id 自己接上；
    // 此时 system prompt 也不能再当首条 input（那会变成一条新的用户侧消息），改走 instructions。
    val request = buildResponsesRequest(
        "",
        if (continuation.canContinue) emptyList() else messages,
        tools,
        reasoningEffort,
        stream = false,
        fastMode = fastMode
    ).toMutableMap()
    if (continuation.canContinue && systemPrompt.isNotBlank()) request["instructions"] = systemPrompt
    request["type"] = "response.create"
    request.remove("stream")
    request["input"] = continuation.input
    continuation.previousResponseId?.let { request["previous_response_id"] = it }
    return ResponsesSocketRequestState(fullInput, request, messages.toList(), continuation.previousResponseId)
}

/** 复用 HTTP 端点拼出的地址：Responses WS 就挂在同一个路径上，只是换个 scheme。 */
internal fun OpenAIAdapter.responsesWebSocketUrl(): String {
    val httpUrl = resolveApiUrl()
    return when {
        httpUrl.startsWith("https://", ignoreCase = true) -> "wss://${httpUrl.substring(8)}"
        httpUrl.startsWith("http://", ignoreCase = true) -> "ws://${httpUrl.substring(7)}"
        else -> throw IllegalArgumentException("Responses WebSocket requires an http(s) API URL")
    }
}

internal fun OpenAIAdapter.webSocketRequest(): Request {
    val headers = linkedMapOf<String, String>()
    // 免鉴权的中转站不发空 Bearer，否则会被当成错误的凭据直接拒掉。
    bearerTokenOrNull(apiKey)?.let { headers["Authorization"] = it }
    extraHeaders().forEach { (name, value) ->
        headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(headers::remove)
        headers[name] = value
    }
    // beta 能力靠这个头开启；用户自定义头里若也写了 OpenAI-Beta，合并而不是覆盖。
    val betaKey = headers.keys.firstOrNull { it.equals("OpenAI-Beta", ignoreCase = true) }
    val betaValue = betaKey?.let(headers::remove).orEmpty()
    headers["OpenAI-Beta"] = listOf(betaValue, "responses_websockets=2026-02-06")
        .filter { it.isNotBlank() }
        .joinToString(", ")
    return Request.Builder().url(responsesWebSocketUrl()).apply {
        headers.forEach { (name, value) -> header(name, value) }
    }.build()
}

/** 建连（含换 Key / 网络重试）：握手失败也可能只是这个 Key 不可用，与 HTTP 路径同口径。 */
private suspend fun ProducerScope<AIStreamChunk>.openResponsesSocket(
    adapter: OpenAIAdapter,
    triedKeys: MutableSet<String>
): ResponsesWebSocketConnection {
    var attempt = 0
    while (true) {
        val connection = ResponsesWebSocketConnection(
            client = adapter.okHttpClient,
            request = adapter.webSocketRequest(),
            connectTimeoutMs = adapter.firstByteTimeoutMs
        )
        try {
            connection.awaitOpen()
            return connection
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            connection.cancel()
            if (e is WebSocketHandshakeException) {
                val switched = adapter.switchKeyOnFailure(e, triedKeys)
                if (switched != null) {
                    send(AIStreamChunk.KeySwitched(switched.newIndex, switched.total))
                    continue
                }
                if (e.statusCode !in setOf(408, 429, 500, 502, 503, 504) || attempt >= adapter.maxNetworkRetries) throw e
            } else if (attempt >= adapter.maxNetworkRetries || e !is IOException) {
                throw e
            }
            send(AIStreamChunk.Retrying(attempt + 1, adapter.maxNetworkRetries, e.toRetryErrorInfo()))
            delay(retryDelayMillis(attempt, e))
            attempt++
        }
    }
}

/**
 * Responses WebSocket 流式请求。
 *
 * 两条回退必须保留：服务端判定连接轮次超限（`websocket_connection_limit_reached`）或续传凭据
 * 失效（`previous_response_not_found`）时，都要重建连接并整段重发——否则用户会看到一连串
 * 无法恢复的失败。
 */
internal suspend fun OpenAIAdapter.streamViaResponsesWebSocket(
    scope: ProducerScope<AIStreamChunk>,
    systemPrompt: String,
    messages: List<AgentMessage>,
    tools: List<AgentTool>,
    reasoningEffort: String?,
    fastMode: Boolean
) {
    val triedKeys = mutableSetOf(apiKey)
    var state = prepareResponsesSocketRequest(systemPrompt, messages, tools, reasoningEffort, fastMode)
    val seq = AILogger.logRequest(logSessionId, "OpenAI", model, "WS", responsesWebSocketUrl(), state.request)
    var contentReceived = false
    val watchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { responsesWebSocket.socket?.cancel() }
    try {
        var socket = responsesWebSocket.socket?.takeIf { it.isOpen }
            ?: scope.openResponsesSocket(this, triedKeys).also { responsesWebSocket.socket = it }
        socket.send(state.request)
        var accumulator = ResponsesStreamAccumulator()
        var argProgress = ToolArgProgressSampler()
        var requestStartedAt = System.nanoTime()
        watchdog.touch()
        while (!accumulator.terminated) {
            coroutineContext.ensureActive()
            val event = if (!contentReceived && firstByteTimeoutMs > 0) {
                val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - requestStartedAt)
                val remaining = firstByteTimeoutMs - elapsed
                if (remaining <= 0) throw java.net.SocketTimeoutException("Responses WebSocket first-content timeout")
                socket.nextEvent(remaining)
            } else {
                socket.nextEvent(0L)
            }
            watchdog.touch()
            sampleResponsesArgProgress(event, argProgress)?.let { scope.send(it) }
            val delta = try {
                accumulator.accept(event)
            } catch (e: StreamApiException) {
                // 服务端对单条连接能承载的轮次有限：换一条连接、清掉续传状态后整段重发。
                if (e.code == "websocket_connection_limit_reached" && !contentReceived) {
                    responsesWebSocket.socket?.close()
                    val replacement = scope.openResponsesSocket(this, triedKeys).also { responsesWebSocket.socket = it }
                    responsesWebSocket.previousResponseId = null
                    responsesWebSocket.submittedMessages = emptyList()
                    state = state.copy(
                        request = state.request.toMutableMap().apply {
                            remove("previous_response_id")
                            remove("instructions")
                            put("input", rebuildResponsesFullInput(systemPrompt, messages))
                        },
                        previousResponseId = null
                    )
                    accumulator = ResponsesStreamAccumulator()
                    argProgress = ToolArgProgressSampler()
                    requestStartedAt = System.nanoTime()
                    replacement.send(state.request)
                    socket = replacement
                    continue
                }
                val switched = if (!contentReceived) switchKeyOnFailure(e, triedKeys) else null
                if (switched != null) {
                    scope.send(AIStreamChunk.KeySwitched(switched.newIndex, switched.total))
                    responsesWebSocket.socket?.close()
                    val replacement = scope.openResponsesSocket(this, triedKeys).also { responsesWebSocket.socket = it }
                    responsesWebSocket.previousResponseId = null
                    responsesWebSocket.submittedMessages = emptyList()
                    state = state.copy(
                        request = state.request.toMutableMap().apply {
                            remove("previous_response_id")
                            remove("instructions")
                            put("input", state.fullInput)
                        },
                        previousResponseId = null
                    )
                    accumulator = ResponsesStreamAccumulator()
                    argProgress = ToolArgProgressSampler()
                    requestStartedAt = System.nanoTime()
                    replacement.send(state.request)
                    socket = replacement
                    continue
                }
                // 服务端已经不记得上一轮（过期 / 换代）：清掉续传状态，同一连接重发整段。
                if (e.code == "previous_response_not_found" && state.previousResponseId != null && !contentReceived) {
                    responsesWebSocket.previousResponseId = null
                    responsesWebSocket.submittedMessages = emptyList()
                    val fullRequest = state.request.toMutableMap().apply {
                        remove("previous_response_id")
                        remove("instructions")
                    }
                    state = state.copy(
                        fullInput = rebuildResponsesFullInput(systemPrompt, messages),
                        request = fullRequest,
                        previousResponseId = null
                    )
                    fullRequest["input"] = state.fullInput
                    accumulator = ResponsesStreamAccumulator()
                    argProgress = ToolArgProgressSampler()
                    requestStartedAt = System.nanoTime()
                    socket.send(fullRequest)
                    continue
                }
                throw e
            }
            if (accumulator.receivedContent && !contentReceived) contentReceived = true
            when (delta) {
                is ResponsesDelta.Text -> scope.send(AIStreamChunk.TextDelta(delta.text))
                is ResponsesDelta.Reasoning -> scope.send(AIStreamChunk.ReasoningDelta(delta.text))
                is ResponsesDelta.ToolCallDeclared -> scope.send(AIStreamChunk.ToolCallDeclared(delta.name))
                null -> Unit
            }
        }
        val response = sanitizeTruncatedToolCalls(accumulator.toResponse())
        responsesWebSocket.previousResponseId = response.responseId
        responsesWebSocket.submittedMessages = state.sentMessages
        AILogger.logResponse(logSessionId, "OpenAI WebSocket", response, seq)
        scope.send(AIStreamChunk.Final(response))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // 连接一旦出错就不再可信（可能半开）：直接丢弃，下一轮重新握手。
        responsesWebSocket.socket?.cancel()
        responsesWebSocket.socket = null
        responsesWebSocket.previousResponseId = null
        responsesWebSocket.submittedMessages = emptyList()
        val enriched = e.enrichWithHttpErrorBody()
        AILogger.logError(logSessionId, "OpenAI", enriched, seq)
        throw enriched
    } finally {
        watchdog.cancel()
    }
}

private fun OpenAIAdapter.rebuildResponsesFullInput(
    systemPrompt: String,
    messages: List<AgentMessage>
): List<Map<String, Any?>> = buildResponsesInput(
    systemPrompt = systemPrompt,
    systemRole = systemRoleForModel(),
    messages = messages,
    includeReasoningItems = model.contains("deepseek", ignoreCase = true)
)
