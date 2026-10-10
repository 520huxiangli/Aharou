package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.modelToolResultText
import com.aharou.feature.settings.domain.model.ModelContextPolicy
import com.google.gson.Gson

object ContextTokenEstimator {
    private val gson = Gson()

    /** 无校准基线时给压缩触发用的放大比例，见 [forCompactionTrigger]。 */
    private const val UNCALIBRATED_SAFETY_PERCENT = 110L

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

    /**
     * 「要不要压缩」专用的保守值。
     *
     * [calibrated] 只在拿得到上游真实用量时才把估算拉回地面；压缩之后基线会被清零，
     * 到下一次上游回话之前只剩原始估算，而它对代码、JSON、工具输出这类密集内容偏乐观
     * （实测约 2.5~3 字符/token）。拿这个偏小的数去比阈值会晚一步才压缩，
     * 代价是整轮请求被上游按超窗口拒掉——所以没有基线时留一点余量（[UNCALIBRATED_SAFETY_PERCENT]）。
     * 余量不能大：调大会让窗口本来就小的模型（如 Claude 的 20 万）压缩变勤，
     * 真正撞墙那一次由 `isContextOverflow` 的兜底强压负责恢复。
     *
     * 只用于压缩判断；界面显示的用量仍旧走 [calibrated]，不为了保守而虚报。
     */
    fun forCompactionTrigger(estimated: Int, baselineEstimate: Int, baselineUsage: Int): Int =
        if (baselineUsage > 0) {
            calibrated(estimated, baselineEstimate, baselineUsage)
        } else {
            (estimated.toLong() * UNCALIBRATED_SAFETY_PERCENT / 100)
                .coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        }
}
