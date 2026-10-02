package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson

object ContextTokenEstimator {
    private val gson = Gson()

    fun estimate(systemPrompt: String, messages: List<AgentMessage>, tools: List<AgentTool>): Int {
        val total = ModelContextPolicy.estimateTextTokens(systemPrompt).toLong() +
            tools.sumOf { tool ->
                ModelContextPolicy.estimateTextTokens(tool.name + tool.description + gson.toJson(tool.toJsonSchema())).toLong() + 16
            } + messages.sumOf { estimate(it).toLong() }
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun estimate(message: AgentMessage): Int {
        val text: String
        val images: Int
        when (message) {
            is AgentMessage.UserMessage -> {
                text = message.content
                images = message.images.size
            }
            is AgentMessage.AssistantMessage -> {
                text = message.content + message.reasoning + message.toolCalls.joinToString { it.name + gson.toJson(it.arguments) }
                images = message.images.size
            }
            is AgentMessage.ToolResultMessage -> {
                text = message.toolName + (message.modelResult ?: modelToolResultText(message.toolName, message.result) ?: message.result)
                images = message.images.size
            }
        }
        return (ModelContextPolicy.estimateTextTokens(text).toLong() + images * 4_096L + 12)
            .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    fun calibrated(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int =
        if (baselineUsage > 0) {
            maxOf(estimated.toLong(), baselineUsage.toLong() + estimated - baselineEstimate)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        } else estimated
}
