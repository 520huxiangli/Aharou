package com.aharou.feature.agent.data.remote.anthropic

data class AnthropicMessageRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    // 纯文本或内容块数组（打 cache_control 断点时用块数组）。
    val system: Any? = null,
    val max_tokens: Int = 16384,
    // 开启 extended thinking 时不能携带 temperature（官方要求），置 null 由 Gson 跳过该字段。
    val temperature: Float? = null,
    val thinking: AnthropicThinkingConfig? = null,
    // adaptive thinking 的 effort 档位（新模型，如 opus-4.7+/sonnet-5）。
    val output_config: AnthropicOutputConfig? = null,
    val tools: List<AnthropicToolDefinition>? = null,
    val stream: Boolean = false
)

/**
 * Anthropic thinking 配置：
 * - 旧模型（4.5 及更早）：type="enabled" + budget_tokens（思考 token 预算）
 * - 新模型（4.6+/5 系）：type="adaptive" + display（思考展示方式），effort 走 [AnthropicOutputConfig]
 * - 关闭思考：type="disabled"
 * 未用到的字段置 null 由 Gson 跳过。
 */
data class AnthropicThinkingConfig(
    val type: String = "enabled",
    val budget_tokens: Int? = null,
    val display: String? = null
)

/** adaptive thinking 的 effort 档位（low/medium/high/xhigh/max）。 */
data class AnthropicOutputConfig(
    val effort: String? = null
)

data class AnthropicMessage(
    val role: String, // "user" or "assistant"
    val content: Any // Can be String or List<AnthropicContentBlock>
)

data class AnthropicContentBlock(
    val type: String, // "text", "tool_use", "tool_result", "thinking", "redacted_thinking"
    val text: String? = null,
    val source: Map<String, Any>? = null,
    val id: String? = null, // for tool_use
    val name: String? = null, // for tool_use
    val input: Map<String, Any>? = null, // for tool_use
    val tool_use_id: String? = null, // for tool_result
    val content: Any? = null, // for tool_result: String or List<AnthropicContentBlock>
    val is_error: Boolean? = null, // for tool_result
    val thinking: String? = null, // for thinking block：思考摘要文本
    val signature: String? = null, // for thinking block：加密签名，多轮/工具循环须原样回传
    val data: String? = null, // for redacted_thinking：不透明加密串，同样必须原样回传
    val cache_control: Map<String, String>? = null // 显式缓存断点，仅 {type: ephemeral}
)

data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    val input_schema: Map<String, Any>,
    val cache_control: Map<String, String>? = null // 显式缓存断点，仅最后一个工具打点
)

data class AnthropicMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContentBlock>,
    val model: String,
    val stop_reason: String?,
    val stop_sequence: String?,
    /** 拒答等非正常结束时的结构化说明（stop_reason=refusal 时正文可能为空，理由只在这里）。 */
    val stop_details: AnthropicStopDetails? = null,
    val usage: AnthropicUsage
)

/** `stop_details`：拒答/降级等场景的补充说明。 */
data class AnthropicStopDetails(
    val type: String? = null,
    val category: String? = null,
    val explanation: String? = null,
    val recommended_model: String? = null
)

data class AnthropicUsage(
    val input_tokens: Int,
    val output_tokens: Int,
    /** 命中缓存的前缀 token 数（prompt caching 生效时返回）。 */
    val cache_read_input_tokens: Int? = null,
    /** 本次写入缓存的新增 token 数。 */
    val cache_creation_input_tokens: Int? = null
)
