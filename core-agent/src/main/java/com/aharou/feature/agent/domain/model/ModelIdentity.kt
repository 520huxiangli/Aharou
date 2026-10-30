package com.aharou.feature.agent.domain.model

/**
 * 本轮实际运行的模型身份，供系统提示词注入「当前模型身份」段。
 *
 * 只承载**同步可得**的信息（会话里本来就有的 provider / model，以及调用方顺手拿到的窗口），
 * 不在提示词装配路径上引入异步查询——那里是同步渲染，为一段身份说明去查网络或数据库不值当。
 * 拿不到的字段留默认值，渲染时自然省略。
 */
data class ModelIdentity(
    val modelId: String,
    val providerId: String? = null,
    /** 模型的上下文窗口；0 表示未知，注入时省略该项。 */
    val contextTokens: Int = 0
)
