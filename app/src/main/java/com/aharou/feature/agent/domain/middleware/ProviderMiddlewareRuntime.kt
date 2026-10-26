package com.aharou.feature.agent.domain.middleware

import com.aharou.core.util.FileLogger

/**
 * 声明式中间件在「不碰请求体」这一层能落地的两部分：模型映射与系统提示注入。
 *
 * 参数覆盖（[MiddlewareConfig.paramOverrides]）作用在请求 JSON 上，需要按三种协议各自的组装点
 * 插入，暂不在这一层处理——配置里有它也不会被静默忽略：调用方可以拿 [MiddlewareConfig.paramOverrides]
 * 自行判断是否要提示用户。
 */
data class MiddlewareApplication(
    /** 真正发给服务端的模型名（未配置映射时与请求名相同）。 */
    val actualModel: String,
    /** 追加了中间件系统提示后的完整系统提示。 */
    val systemPrompt: String,
)

/**
 * 计算一次请求的中间件效果。
 *
 * @param rawConfig 供应商上的 JSON 配置原文；空/非法 JSON 按「未配置」处理，不抛异常。
 * @param requestedModel 用户在 UI 上选的模型名，用于查找 [MiddlewareConfig.modelMap]。
 */
fun applyProviderMiddleware(
    rawConfig: String?,
    providerId: String,
    providerType: String,
    requestedModel: String,
    systemPrompt: String,
): MiddlewareApplication {
    val parsed = RequestMiddleware.parseConfig(rawConfig)
    if (parsed == null && !rawConfig.isNullOrBlank()) {
        FileLogger.w("ProviderMiddleware", "中间件配置解析失败（已忽略）：${rawConfig.take(120)}")
    }
    val config = parsed?.takeIf { it.enabled } ?: return MiddlewareApplication(requestedModel, systemPrompt)

    val mapped = config.modelMap[requestedModel]?.takeIf { it.isNotBlank() } ?: requestedModel
    val ctx = MiddlewareContext(providerId, providerType, requestedModel, mapped)

    val extra = config.systemPrompt?.takeIf { it.isNotBlank() }?.takeIf { matchesTargets(config.systemPromptTargets, ctx) }
    val finalPrompt = if (extra == null) systemPrompt else buildString {
        append(systemPrompt)
        if (systemPrompt.isNotBlank()) append("\n\n")
        append(extra)
    }
    return MiddlewareApplication(mapped, finalPrompt)
}

/**
 * 目标匹配：为空 = 全部生效；否则命中 providerId / providerType / 请求模型名 / 映射后模型名任一即可。
 * 大小写不敏感——用户填 `OPENAI` 与 `openai` 不该有两种结果。
 */
private fun matchesTargets(targets: List<String>, ctx: MiddlewareContext): Boolean {
    val normalized = targets.map { it.trim() }.filter { it.isNotEmpty() }
    if (normalized.isEmpty()) return true
    val candidates = listOf(ctx.providerId, ctx.providerType, ctx.requestedModel, ctx.actualModel)
        .map { it.lowercase() }
        .toSet()
    return normalized.any { it.lowercase() in candidates }
}
