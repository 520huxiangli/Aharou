package com.aharou.feature.localmodel

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AIStreamChunk
import com.aharou.feature.agent.domain.provider.KeySwitchOutcome
import com.aharou.feature.agent.domain.tool.AgentTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 本地模型当作一个 provider 接进聊天。
 *
 * 原生推理是一次性出的：没有 SSE、也不能逐 token 回调，所以 [completeStream] 是「生成完
 * 再一次性推一条增量」，界面看起来会先转圈、然后整段出现——这是 llama.cpp 这条路的现状，
 * 不是没做流式。
 *
 * 也不支持工具调用：0.6B 级别的模型扛不住 function calling，传了 tools 也会被忽略。
 */
internal class LocalProvider : AIProvider {

    override var apiKey: String = ""
    override var baseUrl: String = ""
    override var useFullUrl: Boolean = false
    override var useResponseApi: Boolean = false
    override var model: String = ""
    override var providerId: String = ""
    override var logSessionId: String? = null
    override var customHeaders: Map<String, String> = emptyMap()
    override var keySwitcher: (suspend (Throwable, String, Set<String>) -> KeySwitchOutcome?)? = null
    override var maxOutputTokens: Int? = null
    override var temperature: Float? = null
    override var serviceTier: String? = null
    override var firstByteTimeoutMs: Long = 0L
    override var streamIdleTimeoutMs: Long = 0L
    override var maxNetworkRetries: Int = 0

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
    ): AIResponse {
        val engine = LocalModelEngine.current
            ?: throw IllegalStateException("本地模型引擎没有初始化")
        val id = model.trim().ifBlank { engine.loaded().orEmpty() }
        require(id.isNotBlank()) { "没有选本地模型" }
        engine.load(id).getOrThrow()
        val reply = engine.chat(
            system = systemPrompt,
            user = dialog(messages),
            maxTokens = maxOutputTokens ?: DEFAULT_MAX_TOKENS,
            temperature = temperature ?: DEFAULT_TEMPERATURE,
        ) ?: throw IllegalStateException("本地模型没有返回内容，可能是内存不够")
        return AIResponse(content = reply, stopReason = "stop")
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?,
    ): Flow<AIStreamChunk> = flow {
        val response = complete(systemPrompt, messages, tools, reasoningEffort)
        if (response.content.isNotEmpty()) emit(AIStreamChunk.TextDelta(response.content))
        emit(AIStreamChunk.Final(response))
    }

    /**
     * 把多轮对话压成一段文本。
     *
     * 小模型只吃「系统提示 + 一段对话」，工具结果对它没有意义，直接略过。
     */
    private fun dialog(messages: List<AgentMessage>): String = buildString {
        for (message in messages) {
            when (message) {
                is AgentMessage.UserMessage -> if (message.content.isNotBlank()) {
                    append("用户：").append(message.content.trim()).append('\n')
                }
                is AgentMessage.AssistantMessage -> if (message.content.isNotBlank()) {
                    append("助手：").append(message.content.trim()).append('\n')
                }
                is AgentMessage.ToolResultMessage -> Unit
            }
        }
        append("助手：")
    }

    private companion object {
        const val DEFAULT_MAX_TOKENS = 512
        const val DEFAULT_TEMPERATURE = 0.7f
    }
}
