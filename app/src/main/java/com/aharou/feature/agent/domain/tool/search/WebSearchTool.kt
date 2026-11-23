package com.aharou.feature.agent.domain.tool.search

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class WebSearchTool @Inject constructor() : AgentTool() {

    private companion object {
        const val TAG = "WebSearchTool"
        // 使用与 opencode 一致的 Parallel AI 公开 MCP 接口
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"

        /** 日志里留的原始响应长度：够看出是 HTML 错误页还是别的格式，又不至于把整页灌进日志。 */
        const val RAW_PREVIEW_CHARS = 200

        /** 服务端错误信息的上限：MCP 的 error/message 偶尔会把整段堆栈塞回来。 */
        const val ERROR_MESSAGE_CHARS = 300

        // 走全局/提供商代理链路（AppProxy 的全局 ProxySelector + 按 host 分派的认证），
        // 与对话等其它 HTTP 请求保持一致；lazy 确保 client 在 applyGlobal 之后首次构建。
        val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .proxyAuthenticator(com.aharou.core.net.AppProxy.okHttpAuthenticator)
                .build()
        }
    }

    override val name = "websearch"
    override val description = "通过互联网搜索获取实时信息。适用于需要最新资料或时效性信息的任务。"
    override val capabilities = setOf(ToolCapability.NETWORK_READ)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "query" to ToolParameter(
            name = "query",
            type = ParameterType.STRING,
            description = "搜索关键字。请提炼出准确、易于搜索的短语。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val query = args["query"]?.jsonPrimitive?.contentOrNull 
            ?: return ToolResult.Error("缺少 query 参数")

        return withContext(Dispatchers.IO) {
            try {
                val requestBody = JsonObject(
                    mapOf(
                        "jsonrpc" to JsonPrimitive("2.0"),
                        "id" to JsonPrimitive(1),
                        "method" to JsonPrimitive("tools/call"),
                        "params" to JsonObject(
                            mapOf(
                                "name" to JsonPrimitive("web_search"),
                                "arguments" to JsonObject(
                                    mapOf(
                                        "objective" to JsonPrimitive(query),
                                        "search_queries" to JsonArray(listOf(JsonPrimitive(query))),
                                        "session_id" to JsonPrimitive("aicode-android")
                                    )
                                )
                            )
                        )
                    )
                ).toString()

                FileLogger.i(TAG, "发起 WebSearch 请求: $query")

                val request = Request.Builder()
                    .url(PARALLEL_MCP_URL)
                    .header("Accept", "application/json, text/event-stream")
                    .header("User-Agent", "aharou/1.0")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()

                client.newCall(request).execute().use { resp ->
                    val responseCode = resp.code
                    val responseBody = resp.body?.string().orEmpty()

                    // 非 2xx 一律算服务端异常：绝不能落进「没搜到」分支，否则模型只会一直换关键词
                    if (responseCode !in 200..299) {
                        FileLogger.e(TAG, "WebSearch 失败: HTTP $responseCode, ${preview(responseBody)}")
                        return@withContext ToolResult.Error(
                            "网络搜索失败：搜索服务返回 HTTP $responseCode。这不是「没有搜到」，是服务端异常；" +
                                "不要反复换关键词重试，请如实告诉用户搜索服务不可用。",
                            "SEARCH_SERVICE_ERROR"
                        )
                    }

                    // 空 body 既可能是零结果也可能服务挂了，无法判定时按失败处理并记日志
                    if (responseBody.isBlank()) {
                        FileLogger.e(TAG, "WebSearch 响应体为空 (HTTP $responseCode)")
                        return@withContext ToolResult.Error(
                            "网络搜索失败：搜索服务返回了空响应体（HTTP $responseCode），无法判断是否有结果。" +
                                "不要把这种情况当成「没搜到」，也不要反复换关键词重试。",
                            "SEARCH_EMPTY_RESPONSE"
                        )
                    }

                    when (val parsed = parseMcpResponse(responseBody)) {
                        is McpOutcome.Payload -> payloadResult(query, parsed.text)
                        is McpOutcome.ToolFailure -> {
                            FileLogger.e(TAG, "WebSearch 服务层报错: ${preview(parsed.message)}")
                            ToolResult.Error(
                                "网络搜索失败：搜索服务返回错误：${parsed.message}",
                                "SEARCH_SERVICE_ERROR"
                            )
                        }
                        is McpOutcome.RpcError -> {
                            FileLogger.e(TAG, "WebSearch JSON-RPC 错误: ${preview(parsed.describe())}")
                            ToolResult.Error(
                                "网络搜索失败：搜索服务返回 JSON-RPC 错误（${parsed.describe()}）。" +
                                    "这不是「没有搜到」，不要靠换关键词重试。",
                                "SEARCH_SERVICE_ERROR"
                            )
                        }
                        McpOutcome.EmptyContent -> {
                            FileLogger.e(TAG, "WebSearch 结果里没有内容块: ${preview(responseBody)}")
                            ToolResult.Error(
                                "网络搜索失败：搜索服务的响应里没有结果内容块，无法判断是否真的没有结果。" +
                                    "不要把这种情况当成「没搜到」，也不要反复换关键词重试。",
                                "SEARCH_PARSE_FAILED"
                            )
                        }
                        McpOutcome.NoEnvelope -> {
                            FileLogger.e(TAG, "WebSearch 响应不是预期的 MCP 结构: ${preview(responseBody)}")
                            ToolResult.Error(
                                "网络搜索失败：搜索服务返回的内容不是预期的 MCP 格式（HTTP $responseCode），解析不出结果。" +
                                    "这不是「没有搜到」，不要靠换关键词重试；请如实告诉用户搜索服务异常。",
                                "SEARCH_PARSE_FAILED"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "WebSearch 发生异常", e)
                ToolResult.Error("搜索时发生异常: ${e.message}", "SEARCH_EXCEPTION")
            }
        }
    }

    /**
     * 服务正常返回了正文时，再分「真空结果」与「有结果/其他可读正文」两种：
     * 只有 `{"results": [...]}` 且数组为空才算真空；结构对不上但正文非空时原样回给模型，
     * 保持服务端返回摘要式文本时仍然可用。
     */
    private fun payloadResult(query: String, text: String): ToolResult {
        val obj = runCatching { Json.parseToJsonElement(text.trim()) as? JsonObject }.getOrNull()
        val results = obj?.get("results") as? JsonArray
        return when {
            obj == null || results == null -> ToolResult.Success(JsonPrimitive(text))
            results.isEmpty() -> ToolResult.Success(
                JsonPrimitive("未找到关于 '$query' 的搜索结果（搜索服务正常返回，结果为空）。可以换个关键词或换一种问法再试。")
            )
            else -> ToolResult.Success(obj)
        }
    }

    /**
     * 解析 MCP 返回体：兼容直接的 JSON 与 SSE（`data: {...}`）两种形态。
     *
     * 三种结局必须分开：找到正文、找到「服务端声明失败」、什么都没找到。
     * 混成同一个 null 就是原来那个 bug 的根源——失败被说成没搜到。
     */
    private fun parseMcpResponse(body: String): McpOutcome {
        var sawResultEnvelope = false
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            val jsonStr = when {
                trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
                trimmed.startsWith("{") -> trimmed
                else -> continue
            }
            val obj = runCatching { Json.parseToJsonElement(jsonStr).jsonObject }.getOrNull() ?: continue

            obj["error"]?.let { error ->
                val errorObj = error as? JsonObject
                return McpOutcome.RpcError(
                    code = errorObj?.get("code")?.jsonPrimitive?.contentOrNull,
                    message = (errorObj?.get("message")?.jsonPrimitive?.contentOrNull
                        ?: error.toString()).truncate(ERROR_MESSAGE_CHARS)
                )
            }

            val result = obj["result"] ?: continue
            sawResultEnvelope = true
            val contentArr = (result as? JsonObject)?.get("content")?.let { it as? JsonArray }
            val text = contentArr?.firstOrNull()?.jsonObject?.get("text")?.jsonPrimitive?.contentOrNull
            // MCP 约定工具执行失败时仍在 result 里返回 content，但 isError=true
            if ((result as? JsonObject)?.get("isError")?.jsonPrimitive?.contentOrNull == "true") {
                return McpOutcome.ToolFailure((text?.takeIf { it.isNotBlank() } ?: "未知错误").truncate(ERROR_MESSAGE_CHARS))
            }
            if (!text.isNullOrBlank()) return McpOutcome.Payload(text)
        }
        return if (sawResultEnvelope) McpOutcome.EmptyContent else McpOutcome.NoEnvelope
    }

    private fun preview(raw: String): String = raw.take(RAW_PREVIEW_CHARS)

    private fun String.truncate(limit: Int): String =
        if (length <= limit) this else take(limit) + "…"

    private sealed interface McpOutcome {
        /** 正常的工具结果正文。 */
        data class Payload(val text: String) : McpOutcome

        /** MCP 层声明工具执行失败（`result.isError == true`）。 */
        data class ToolFailure(val message: String) : McpOutcome

        /** JSON-RPC 层的错误对象。 */
        data class RpcError(val code: String?, val message: String) : McpOutcome {
            fun describe(): String = if (code.isNullOrBlank()) message else "code=$code: $message"
        }

        /** 有结果信封但里面没有文本内容。 */
        data object EmptyContent : McpOutcome

        /** 整份响应里没有可识别的 JSON-RPC 结果信封。 */
        data object NoEnvelope : McpOutcome
    }
}
