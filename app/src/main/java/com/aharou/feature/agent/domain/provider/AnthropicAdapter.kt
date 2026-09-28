package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessage
import com.aharou.feature.agent.data.remote.anthropic.AnthropicContentBlock
import com.aharou.feature.agent.data.remote.anthropic.AnthropicThinkingConfig
import com.aharou.feature.agent.data.remote.anthropic.AnthropicOutputConfig
import com.aharou.feature.agent.data.remote.anthropic.AnthropicToolDefinition
import com.aharou.core.util.AILogger
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.model.defaultProviderApiPath
import com.google.gson.JsonParser
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

class AnthropicAdapter @Inject constructor(
    private val api: AnthropicApi
) : AIProvider {

    override var apiKey = ""
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var baseUrl = "https://api.anthropic.com/"
    override var useFullUrl = false
    override var useResponseApi = false
    override var model = "claude-3-5-sonnet-20241022"
    override var providerId = ""
    override var logSessionId: String? = null
    override var firstByteTimeoutMs: Long = FIRST_BYTE_TIMEOUT_MS
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = MAX_NETWORK_RETRIES

    /** 是否启用显式缓存断点（cache_control）。默认开启；第三方兼容网关严格校验未知字段时由设置项关闭。 */
    var cacheBreakpointsEnabled: Boolean = true

    /** 自定义请求头：占位符替换后写出，完全覆盖同名默认头。 */
    override var customHeaders: Map<String, String> = emptyMap()

    override var maxOutputTokens: Int? = null

    // null 时请求体不带 temperature（Gson 跳过 null 字段），交由服务端默认；开启 thinking 时官方要求必须不带。
    override var temperature: Float? = null

    private fun extraHeaders(): Map<String, String> =
        resolveCustomHeaders(customHeaders, logSessionId, apiKey)

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)

        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                // 断点预算有限（每请求最多 4 个），tools 只打最后一个。
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = false
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)

        val triedKeys = mutableSetOf(apiKey)
        val response = try {
            retryStaircase(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, _ -> switchKeyOnFailure(e, triedKeys) != null }
            ) {
                api.createMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        }
        AILogger.logResponse(logSessionId, "Anthropic", response, seq)

        var contentText = ""
        var thinkingText = ""
        var signature: String? = null
        val toolCalls = mutableListOf<ToolCall>()
        // thinking / redacted_thinking 块按返回原序原样留存，回传时不得修改也不得重排。
        val thinkingBlocks = mutableListOf<AnthropicContentBlock>()

        for (block in response.content) {
            when (block.type) {
                "text" -> contentText += block.text ?: ""
                "thinking" -> {
                    thinkingText += block.thinking ?: ""
                    signature = block.signature ?: signature
                    thinkingBlocks.add(block)
                }
                "redacted_thinking" -> thinkingBlocks.add(block)
                "tool_use" -> {
                    val arguments = block.input?.let { mapToJson(it) } ?: JsonObject(emptyMap())
                    toolCalls.add(
                        ToolCall(
                            id = block.id ?: "",
                            name = block.name ?: "",
                            arguments = arguments
                        )
                    )
                }
            }
        }

        return AIResponse(content = contentText, toolCalls = toolCalls, stopReason = response.stop_reason, stopDetail = response.stop_details?.explanation, reasoning = thinkingText.ifEmpty { null }, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks), inputTokens = totalInputTokens(response.usage.input_tokens, response.usage.cache_read_input_tokens, response.usage.cache_creation_input_tokens), outputTokens = response.usage.output_tokens, cachedInputTokens = response.usage.cache_read_input_tokens ?: 0, cacheCreationTokens = response.usage.cache_creation_input_tokens ?: 0)
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> = flow {
        val anthropicMessages = convertToAnthropicMessages(messages, cacheBreakpointsEnabled)
        val toolDefs = tools.takeIf { it.isNotEmpty() }?.mapIndexed { index, tool ->
            AnthropicToolDefinition(
                name = tool.name,
                description = tool.description,
                input_schema = tool.toJsonSchema(),
                cache_control = if (cacheBreakpointsEnabled && index == tools.lastIndex) CACHE_BREAKPOINT else null
            )
        }

        val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, defaultProviderApiPath(ProviderType.ANTHROPIC))
        val (thinking, outputConfig) = buildThinkingConfig(reasoningEffort)
        val request = AnthropicMessageRequest(
            model = model,
            messages = anthropicMessages,
            system = buildSystemPayload(systemPrompt),
            max_tokens = resolveMaxTokens(thinking),
            temperature = if (thinking != null) null else temperature,
            thinking = thinking,
            output_config = outputConfig,
            tools = toolDefs,
            stream = true
        )
        val seq = AILogger.logRequest(logSessionId, "Anthropic", model, "POST", url, request)
        // 原始 SSE 分批落盘（见 AILogger.beginRawSse），本轮结束在 finally 收尾。
        val sseLog = AILogger.beginRawSse(logSessionId, "Anthropic", seq)

        // 流式请求整体可重试；重试前上层会收到 Retrying 事件并清空已展示文本。
        val triedKeys = mutableSetOf(apiKey)
        try {
            streamWithStaircaseRetry(
                maxRetries = maxNetworkRetries,
                onKeyFailure = { e, canRetry ->
                    val outcome = switchKeyOnFailure(e, triedKeys)
                    if (outcome != null && canRetry) {
                        emit(AIStreamChunk.KeySwitched(outcome.newIndex, outcome.total))
                        true
                    } else false
                },
                attemptOnce = { onContent ->
            val textBuilder = StringBuilder()
            val budget = StreamBudget()
            // content block index -> 累积中的 tool_use（仅 tool_use 块建条目，保序）。
            val toolBlocks = LinkedHashMap<Int, ToolBlockAcc>()
            var stopReason: String? = null
            var stopDetail: String? = null
            var streamInputTokens = 0
            var streamOutputTokens = 0
            var streamCachedInputTokens = 0
            var streamCacheCreationTokens = 0
            // thinking block 的加密签名（signature_delta 事件携带），随 Final 上抛供工具循环回传。
            var signature: String? = null
            // content block index -> thinking / redacted_thinking 累积；按 index 分槽，避免一轮多个思考块被合并。
            val thinkingBlocks = LinkedHashMap<Int, ThinkingBlockAcc>()

            val body = api.streamMessage(url = url, apiKey = apiKey, extraHeaders = extraHeaders(), request = request)

            body.use { rb ->
                // 首字节超时 watchdog：超时内未收到首个内容块则关闭流，触发可重试的 IOException。
                val firstByteReceived = java.util.concurrent.atomic.AtomicBoolean(false)
                val watchdog = launchFirstByteWatchdog(firstByteTimeoutMs, { rb.close() }) { firstByteReceived.get() }
                val idleWatchdog = launchStreamIdleWatchdog(streamIdleTimeoutMs) { rb.close() }
                val closeHandle = coroutineContext[Job]?.invokeOnCompletion {
                    runCatching { rb.close() }
                }
                try {
                    val reader = rb.charStream().buffered()
                    // 收到服务端 message_stop 事件即 break 正常结束；readLine() 返回 null 则视为
                    // 流被异常截断（网络中断/TCP 重置/readTimeout），必须抛异常让重试/日志接管——
                    // 否则原本会用截断数据「正常完成」，表现为 AI 突然中断且无任何错误日志。
                    // （收到 message_stop 即 break，故走到 readLine()==null 时必然未收到过结束标记。）
                    while (true) {
                        coroutineContext.ensureActive()
                        val line = reader.readLine()
                            ?: throw IOException("SSE 流被中断：未收到 message_stop 结束标记（疑似网络断开）")
                        idleWatchdog.touch()
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty()) continue
                        sseLog.append(line)
                        val obj = runCatching { JsonParser.parseString(data).asJsonObject }.getOrNull() ?: continue
                        // 单行 SSE 解析：不同上游/模型的字段类型偶有出入，Gson 的 getAsJsonObject/getAsJsonArray
                        // 在类型不符时会直接抛 ClassCastException，asString/asInt 对非原始值抛 UnsupportedOperationException。
                        // 单行异常不应中断整条流——宽松解析，出错仅跳过该行；必须放行 CancellationException。
                        try {
                            when (obj.get("type")?.asString) {
                                "error" -> {
                                    val errObj = obj.getAsJsonObject("error")
                                    val code = errObj?.get("type")?.takeIf { !it.isJsonNull }?.asString
                                    val msg = errObj?.get("message")?.takeIf { !it.isJsonNull }?.asString ?: "未知错误"
                                    throw StreamApiException(code, msg)
                                }
                                "message_start" -> {
                                    val usage = obj.get("message")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    streamInputTokens = usage?.get("input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    // 缓存命中数在 message_start 的 usage 里返回（message_delta 的 usage 只有 output_tokens）
                                    streamCachedInputTokens = usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                    streamCacheCreationTokens = usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt ?: 0
                                }
                                "content_block_start" -> {
                                    val index = obj.get("index")?.asInt ?: continue
                                    val block = obj.getAsJsonObject("content_block")
                                    when (block?.get("type")?.asString) {
                                        "tool_use" -> {
                                            val name = block.get("name")?.asString ?: ""
                                            toolBlocks[index] = ToolBlockAcc(
                                                id = block.get("id")?.asString ?: "",
                                                name = name
                                            )
                                            // 工具名先于参数到达：立刻告诉 UI「模型准备调什么」，
                                            // 免得参数流式期间一直停在「正在思考」
                                            if (name.isNotEmpty()) emit(AIStreamChunk.ToolCallDeclared(name))
                                        }
                                        "thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "thinking").also { acc ->
                                            val initial = block.get("thinking")?.takeIf { !it.isJsonNull }?.asString ?: ""
                                            budget.add(initial)
                                            acc.thinking.append(initial)
                                            acc.signature = block.get("signature")?.takeIf { !it.isJsonNull }?.asString
                                        }
                                        // redacted_thinking 的 data 在 start 事件一次性给全，没有对应 delta。
                                        "redacted_thinking" -> thinkingBlocks[index] = ThinkingBlockAcc(type = "redacted_thinking").also { acc ->
                                            acc.data = block.get("data")?.takeIf { !it.isJsonNull }?.asString
                                        }
                                    }
                                }
                                "content_block_delta" -> {
                                    val delta = obj.getAsJsonObject("delta") ?: continue
                                    when (delta.get("type")?.asString) {
                                        "text_delta" -> {
                                            val t = delta.get("text")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                textBuilder.append(t)
                                                if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                onContent()
                                                emit(AIStreamChunk.TextDelta(t))
                                            }
                                        }
                                        "thinking_delta" -> {
                                            val t = delta.get("thinking")?.asString ?: ""
                                            if (t.isNotEmpty()) {
                                                budget.add(t)
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .thinking.append(t)
                                                }
                                                // 思考内容不落库、可重试重流出，但收到即说明连接已活，取消首字节超时。
                                                if (firstByteReceived.compareAndSet(false, true)) watchdog.cancel()
                                                onContent()
                                                emit(AIStreamChunk.ReasoningDelta(t))
                                            }
                                        }
                                        "signature_delta" -> {
                                            val sig = delta.get("signature")?.asString ?: ""
                                            if (sig.isNotEmpty()) {
                                                signature = sig
                                                obj.get("index")?.asInt?.let { idx ->
                                                    thinkingBlocks.getOrPut(idx) { ThinkingBlockAcc(type = "thinking") }
                                                        .signature = sig
                                                }
                                            }
                                        }
                                        "input_json_delta" -> {
                                            val index = obj.get("index")?.asInt
                                            val partial = delta.get("partial_json")?.asString ?: ""
                                            budget.add(partial)
                                            if (index != null) toolBlocks[index]?.args?.append(partial)
                                        }
                                    }
                                }
                                "message_stop" -> break
                                "message_delta" -> {
                                    val delta = obj.get("delta")?.takeIf { it.isJsonObject }?.asJsonObject
                                    delta?.get("stop_reason")?.takeIf { !it.isJsonNull }?.asString?.let {
                                        stopReason = it
                                    }
                                    delta?.get("stop_details")?.takeIf { it.isJsonObject }?.asJsonObject
                                        ?.get("explanation")?.takeIf { !it.isJsonNull }?.asString?.let {
                                            stopDetail = it
                                        }
                                    val usage = obj.get("usage")?.takeIf { it.isJsonObject }?.asJsonObject
                                    usage?.get("output_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamOutputTokens = it
                                    }
                                    usage?.get("cache_read_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCachedInputTokens = it
                                    }
                                    usage?.get("cache_creation_input_tokens")?.takeIf { !it.isJsonNull }?.asInt?.let {
                                        streamCacheCreationTokens = it
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            coroutineContext.ensureActive()
                            // 该行 SSE 解析失败，跳过；不影响已累积文本与后续行。
                        }
                    }
                } finally {
                    watchdog.cancel()
                    idleWatchdog.cancel()
                    closeHandle?.dispose()
                }
            }

            val toolCalls = toolBlocks.values.map { acc ->
                ToolCall(id = acc.id, name = acc.name, arguments = parseArgs(acc.args.toString()))
            }
            emit(AIStreamChunk.Final(AIResponse(content = textBuilder.toString(), toolCalls = toolCalls, stopReason = stopReason, stopDetail = stopDetail, signature = signature, thinkingBlocksJson = encodeThinkingBlocks(thinkingBlocks.values.map { it.toBlock() }), inputTokens = totalInputTokens(streamInputTokens, streamCachedInputTokens, streamCacheCreationTokens), outputTokens = streamOutputTokens, cachedInputTokens = streamCachedInputTokens, cacheCreationTokens = streamCacheCreationTokens)))
                },
                onRetry = { attempt, max, error -> emit(AIStreamChunk.Retrying(attempt, max, error)) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            coroutineContext.ensureActive()
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(logSessionId, "Anthropic", enriched, seq)
            throw enriched
        } finally {
            // 无论成功/失败/取消，把已收到的原始 SSE 落盘（重试时会从上次中断处续写）。
            sseLog.finish()
        }
    }.flowOn(Dispatchers.IO)

    /** 流式过程中按 content block index 累积的 tool_use 状态。 */
    private class ToolBlockAcc(val id: String, val name: String) {
        val args = StringBuilder()
    }

    /** 流式过程中按 content block index 累积的 thinking / redacted_thinking 状态。 */
    private class ThinkingBlockAcc(val type: String) {
        val thinking = StringBuilder()
        var signature: String? = null
        var data: String? = null

        fun toBlock(): AnthropicContentBlock = AnthropicContentBlock(
            type = type,
            thinking = if (type == "thinking") thinking.toString() else null,
            signature = signature,
            data = data
        )
    }

    /**
     * 最大输出 token：优先用模型元数据的输出上限，缺失时回退 [DEFAULT_MAX_TOKENS]。
     * 开启 thinking 时思考预算计入 max_tokens，须留出正文空间，故不得低于预算 + [MIN_CONTENT_TOKENS]。
     */
    private fun resolveMaxTokens(thinking: AnthropicThinkingConfig?): Int {
        val limit = maxOutputTokens?.takeIf { it > 0 } ?: DEFAULT_MAX_TOKENS
        val budget = thinking?.budget_tokens ?: return limit
        return maxOf(limit, budget + MIN_CONTENT_TOKENS)
    }

    /** thinking / redacted_thinking 块原样序列化为快照；无块时 null。 */
    private fun encodeThinkingBlocks(blocks: List<AnthropicContentBlock>): String? =
        blocks.takeIf { it.isNotEmpty() }?.let { gson.toJson(it) }

    /** 反序列化 [encodeThinkingBlocks] 的快照；损坏或为空时返回空列表，由调用方回退旧逻辑。 */
    private fun decodeThinkingBlocks(json: String): List<AnthropicContentBlock> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            gson.fromJson(json, Array<AnthropicContentBlock>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())
    }

    /**
     * 思考强度 → Anthropic thinking 配置。
     * - 新模型（4.6+/5 系，支持 adaptive）：effort 档位直传 output_config，thinking 用 adaptive+summarized；
     *   "none" 关闭思考用 disabled。
     * - 旧模型（4.5 及更早，仅 budget_tokens）：low/medium/high 映射 1024/4096/8192（须小于 max_tokens），
     *   xhigh/max 归一到 high。
     */
    private fun buildThinkingConfig(reasoningEffort: String?): Pair<AnthropicThinkingConfig?, AnthropicOutputConfig?> {
        if (reasoningEffort == null) return null to null
        if (supportsAdaptiveThinking()) {
            if (reasoningEffort == "none") {
                return AnthropicThinkingConfig(type = "disabled") to null
            }
            return AnthropicThinkingConfig(type = "adaptive", display = "summarized") to AnthropicOutputConfig(effort = reasoningEffort)
        }
        val budget = when (reasoningEffort) {
            "low" -> 1024
            "medium" -> 4096
            "high", "xhigh", "max" -> 8192
            else -> return null to null
        }
        return AnthropicThinkingConfig(type = "enabled", budget_tokens = budget) to null
    }

    /** 是否支持 adaptive thinking：4.6+ 及 5 系（claude-<family>-4-6/4-7/4-8 或主版本 5）。 */
    private fun supportsAdaptiveThinking(): Boolean {
        val m = Regex("claude-(?:opus|sonnet|haiku|fable|mythos)-(\\d+)(?:-(\\d+))?").find(model)
        val major = m?.groupValues?.get(1)?.toIntOrNull() ?: return false
        val minor = m?.groupValues?.get(2)?.toIntOrNull()
        return major >= 5 || (major == 4 && (minor ?: 0) >= 6)
    }

    /** 把累积的工具入参 JSON 字符串解析为 JsonObject；为空或非法时回退为空对象。 */
    private fun parseArgs(raw: String): JsonObject {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(trimmed).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    private fun convertToAnthropicMessages(messages: List<AgentMessage>, addMessageBreakpoint: Boolean): MutableList<AnthropicMessage> {
        val result = mutableListOf<AnthropicMessage>()
        // 防御性跟踪：上一个 assistant 消息是否包含 tool_use
        var lastAssistantHadToolUse = false
        // 最后一条普通 user 消息（非 tool_result）在 result 中的索引，供 messages 断点打点。
        var lastPlainUserIndex: Int? = null

        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> {
                    result.add(AnthropicMessage(role = "user", content = message.toAnthropicUserContent()))
                    lastPlainUserIndex = result.lastIndex
                    lastAssistantHadToolUse = false
                }
                is AgentMessage.AssistantMessage -> {
                    val contentBlocks = mutableListOf<AnthropicContentBlock>()
                    // 工具循环/多轮时须把上轮 thinking / redacted_thinking 块原样、原序回传，否则 400。
                    // 优先用原生快照（唯一能满足「不得修改」的形式）；旧数据/备份恢复只有 signature 时退回单块。
                    val snapshot = decodeThinkingBlocks(message.thinkingBlocksJson)
                    if (snapshot.isNotEmpty()) {
                        contentBlocks.addAll(snapshot)
                    } else if (message.signature.isNotEmpty()) {
                        contentBlocks.add(
                            AnthropicContentBlock(
                                type = "thinking",
                                thinking = message.reasoning,
                                signature = message.signature
                            )
                        )
                    }
                    if (message.content.isNotEmpty()) {
                        contentBlocks.add(AnthropicContentBlock(type = "text", text = message.content))
                    }

                    for (toolCall in message.toolCalls) {
                         @Suppress("UNCHECKED_CAST")
                         val inputMap = jsonElementToMap(JsonObject(toolCall.arguments)) as Map<String, Any>

                         contentBlocks.add(
                            AnthropicContentBlock(
                                type = "tool_use",
                                id = toolCall.id,
                                name = toolCall.name,
                                input = inputMap
                            )
                        )
                    }

                    lastAssistantHadToolUse = message.toolCalls.isNotEmpty()

                    if (contentBlocks.isNotEmpty()) {
                        result.add(AnthropicMessage(role = "assistant", content = contentBlocks))
                    }
                }
                is AgentMessage.ToolResultMessage -> {
                    // 防御性清理：跳过没有配对 tool_use 的孤立 tool_result
                    if (!lastAssistantHadToolUse) continue
                    // 文件类工具喂模型用精简投影文本（减少完整 diff 回传的 token），UI/持久化仍走完整 result。
                    val modelText = message.modelResult
                        ?: modelToolResultText(message.toolName, message.result)
                        ?: message.result
                    val content: Any = if (message.images.isNotEmpty()) {
                        val contentList = mutableListOf<AnthropicContentBlock>()
                        if (modelText.isNotBlank()) {
                            contentList.add(AnthropicContentBlock(type = "text", text = modelText))
                        }
                        message.images.forEach { img ->
                            contentList.add(img.toAnthropicImageBlock())
                        }
                        contentList
                    } else {
                        modelText
                    }
                    val resultBlock = AnthropicContentBlock(
                        type = "tool_result",
                        tool_use_id = message.id,
                        content = content
                    )
                    // 同一条 assistant 里的多个 tool_use，其 tool_result 必须全部放进紧随的那一条 user 消息：
                    // 官方端会把连续的 user 消息合并成一轮，但部分兼容网关不合并，会直接报
                    // "`tool_use` ids were found without `tool_result` blocks immediately after"。
                    val previous = result.lastOrNull()
                    val previousBlocks = (previous?.content as? List<*>)?.filterIsInstance<AnthropicContentBlock>()
                    if (
                        previous?.role == "user" &&
                        !previousBlocks.isNullOrEmpty() &&
                        previousBlocks.all { it.type == "tool_result" }
                    ) {
                        result[result.lastIndex] = previous.copy(content = previousBlocks + resultBlock)
                    } else {
                        result.add(AnthropicMessage(role = "user", content = listOf(resultBlock)))
                    }
                }
            }
        }

        // messages 断点：打在最后一条普通 user 消息的 text 块上（Anthropic 不允许打在 tool_result/thinking/image 块）。
        // 工具循环中最后一条 user 是 tool_result 时不打点，保持 system+tools 两个断点即可。
        if (addMessageBreakpoint) {
            val idx = lastPlainUserIndex
            if (idx != null) {
                val msg = result[idx]
                val newContent = when (val c = msg.content) {
                    is String -> listOf(
                        AnthropicContentBlock(type = "text", text = c, cache_control = CACHE_BREAKPOINT)
                    )
                    is List<*> -> {
                        val blocks = c.map { it as AnthropicContentBlock }
                        val lastText = blocks.indexOfLast { it.type == "text" }
                        if (lastText >= 0) {
                            blocks.mapIndexed { i, b ->
                                if (i == lastText) b.copy(cache_control = CACHE_BREAKPOINT) else b
                            }
                        } else {
                            blocks
                        }
                    }
                    else -> c
                }
                result[idx] = msg.copy(content = newContent)
            }
        }

        return result
    }

    /** 显式缓存断点值：Anthropic ephemeral prompt caching。 */
    private fun buildSystemPayload(systemPrompt: String): Any? {
        if (systemPrompt.isBlank()) return null
        if (!cacheBreakpointsEnabled) return systemPrompt
        return listOf(
            mapOf(
                "type" to "text",
                "text" to systemPrompt,
                "cache_control" to CACHE_BREAKPOINT
            )
        )
    }

    /** Convert a Map<String, Any> (from Gson) to a JsonObject */
    private fun mapToJson(map: Map<String, Any>): JsonObject {
        val mutable = mutableMapOf<String, kotlinx.serialization.json.JsonElement>()
        for ((k, v) in map) {
            mutable[k] = when (v) {
                is String -> JsonPrimitive(v)
                is Number -> JsonPrimitive(v)
                is Boolean -> JsonPrimitive(v)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    mapToJson(v as Map<String, Any>)
                }
                else -> JsonPrimitive(v.toString())
            }
        }
        return JsonObject(mutable)
    }

    /** Convert a JsonObject back to Map<String, Any> for Anthropic API */
    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Any {
        return when (element) {
            is JsonObject -> element.mapValues { (_, v) -> jsonElementToMap(v) }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToMap(it) }
            is JsonPrimitive -> element.contentOrNull ?: ""
        }
    }

    private fun AgentMessage.UserMessage.toAnthropicUserContent(): Any {
        if (images.isEmpty()) return content

        val blocks = mutableListOf<AnthropicContentBlock>()
        if (content.isNotBlank()) {
            blocks.add(AnthropicContentBlock(type = "text", text = content))
        }
        images.forEach { image ->
            blocks.add(image.toAnthropicImageBlock())
        }
        return blocks
    }

    private fun AgentImage.toAnthropicImageBlock(): AnthropicContentBlock {
        return AnthropicContentBlock(
            type = "image",
            source = mapOf(
                "type" to "base64",
                "media_type" to mimeType,
                "data" to base64Data
            )
        )
    }

    private companion object {
        /** 显式缓存断点：Anthropic ephemeral prompt caching。 */
        val CACHE_BREAKPOINT = mapOf("type" to "ephemeral")

        /**
         * Anthropic 的 `input_tokens` 只算未命中缓存的部分，总输入还要加上 cache_read / cache_creation——
         * 与 OpenAI（`prompt_tokens` 含 `cached_tokens`）、Gemini（`promptTokenCount`）口径对齐。
         * 上下文占用、压缩触发、费用与缓存命中率都按总输入算。
         */
        fun totalInputTokens(input: Int, cacheRead: Int?, cacheCreation: Int?): Int =
            input + (cacheRead ?: 0) + (cacheCreation ?: 0)

        /** 模型元数据缺输出上限时的兜底最大输出 token。 */
        const val DEFAULT_MAX_TOKENS = 16384

        /** 开启 thinking 时为正文预留的最小 token 数（max_tokens 必须大于思考预算）。 */
        const val MIN_CONTENT_TOKENS = 4096

        val gson = com.google.gson.Gson()
    }
}
