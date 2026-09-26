package com.aharou.feature.agent.domain.model

/**
 * 思考强度档位。apiValue 与 models.dev reasoning_options（effort 类型）的 values 一致，
 * 直接透传给支持该参数的 provider（OpenAI reasoning_effort / Gemini thinkingLevel）；
 * Anthropic 由适配器按模型代际映射为 adaptive effort 或 budget_tokens。
 */
enum class ReasoningEffort(val apiValue: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max");

    companion object {
        /** 把 models.dev 的 effort values 解析为可识别的档位列表（保持 values 顺序）。 */
        fun fromValues(values: List<String>): List<ReasoningEffort> =
            values.mapNotNull { v -> entries.firstOrNull { it.apiValue == v } }
    }
}