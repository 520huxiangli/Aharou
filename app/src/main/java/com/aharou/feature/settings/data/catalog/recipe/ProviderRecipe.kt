package com.aharou.feature.settings.data.catalog.recipe

import kotlinx.serialization.Serializable

// 一家 provider 的请求形状与怪癖（数据化描述，供对照与回归；当前不参与请求构造）。
// 字段取值全部对照 feature/agent/data/remote 下的请求模型与 domain/provider 下的适配器得出。
//
// 约定：
// - protocol 取 [ProviderProtocol] 里的常量之一；
// - temperatureMode 取 "model-metadata"（按模型元数据 + 固定温度表决定是否发）、
//   "fixed-per-model"（只有固定温度表命中的模型才发）或 "never"（从不发送）；
// - 未知字段在解析时被忽略，缺省字段走默认值（宽松降级）。
@Serializable
data class ProviderRecipe(
    val providerId: String,
    val displayName: String = "",
    val protocol: String,
    val defaultBaseUrl: String = "",
    // 请求路径；Gemini 的模型名在路径里，用 {model} 占位。
    val requestPath: String = "",
    // 模型名写进请求体（body.model）还是拼进 URL 路径。
    val modelField: String = "model",
    val modelInPath: Boolean = false,
    val authHeader: String = "",
    // system prompt 的落点：system / developer / systemInstruction，或顶层 system 字段。
    val systemField: String = "",
    // system 是请求体顶层字段（Anthropic 的 system、Gemini 的 systemInstruction）而不是消息角色。
    val systemAsTopLevelField: Boolean = false,
    // 推理模型（OpenAI o 系 / gpt-5 系）要求把 system 角色换成 developer。
    val systemRoleOverrideForReasoningModels: Boolean = false,
    // 输出上限字段名；null 表示该请求形状不发送输出上限（用服务端默认）。
    val maxTokensField: String? = null,
    val temperatureMode: String = "model-metadata",
    // 开启思考时是否仍允许发送 temperature（Anthropic 官方要求不带）。
    val sendsTemperatureWhenThinking: Boolean = true,
    // SSE 结束标记；null 表示没有显式标记，读到流结束即视为正常结束。
    val streamEndMarker: String? = null,
    // 「不思考」的厂商私有字段表达；null 表示不支持。
    val thinkingOffField: String? = null,
    // 是否要求把上一轮的思考内容（reasoning_content）原样回传。
    val echoReasoningContent: Boolean = false,
    // 是否要求把思考块（thinking / redacted_thinking 块）原样回传。
    val echoThinkingBlocks: Boolean = false,
    val requiredFields: List<String> = emptyList(),
    val optionalFields: List<String> = emptyList(),
    // 同一份 recipe 适用的其它 provider id（预设 id 与 models.dev id 不一致时用）。
    val aliases: List<String> = emptyList()
)

object ProviderProtocol {
    const val OPENAI_CHAT = "openai-chat"
    const val OPENAI_RESPONSES = "openai-responses"
    const val ANTHROPIC_MESSAGES = "anthropic-messages"
    const val GEMINI_GENERATE_CONTENT = "gemini-generate-content"
    const val GEMINI_INTERACTIONS = "gemini-interactions"

    val ALL: Set<String> = setOf(
        OPENAI_CHAT, OPENAI_RESPONSES, ANTHROPIC_MESSAGES,
        GEMINI_GENERATE_CONTENT, GEMINI_INTERACTIONS
    )
}
