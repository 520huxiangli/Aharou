package com.aharou.feature.agent.domain.middleware

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.provider.AIProvider
import kotlinx.serialization.json.JsonPrimitive

/**
 * 把声明式中间件的 `paramOverrides` 落到 provider 实例上。
 *
 * 目前只支持三种协议都会读取的两个公共参数：
 * - `temperature` → [AIProvider.temperature]
 * - `max_tokens` / `maxTokens` / `max_output_tokens` → [AIProvider.maxOutputTokens]
 *
 * 其它路径（`top_p`、`reasoning.effort` 之类）没有在请求体上做改写，会被跳过并记一条日志——
 * 之所以不做：三种协议有十个请求体组装点，每个都要做 JSON 往返，改动面与回归风险远高于收益。
 */
fun applyProviderParamOverrides(
    rawConfig: String?,
    providerId: String,
    providerType: String,
    model: String,
    provider: AIProvider,
) {
    val config = RequestMiddleware.parseConfig(rawConfig)?.takeIf { it.enabled } ?: return
    if (config.paramOverrides.isEmpty()) return
    val ctx = MiddlewareContext(providerId, providerType, model, model)
    for (rule in config.paramOverrides) {
        if (!ruleMatches(rule, ctx)) continue
        when (rule.path.trim().lowercase()) {
            "temperature" -> {
                val value = (rule.value as? JsonPrimitive)?.content?.toFloatOrNull()
                if (rule.op == ParamOp.REMOVE) provider.temperature = null else if (value != null) provider.temperature = value
            }
            "max_tokens", "maxtokens", "max_output_tokens" -> {
                val value = (rule.value as? JsonPrimitive)?.content?.toIntOrNull()
                if (rule.op == ParamOp.REMOVE) {
                    provider.maxOutputTokens = null
                } else if (value != null) {
                    provider.maxOutputTokens = value
                }
            }
            else -> FileLogger.w("ProviderParamOverrides", "跳过不支持的参数路径：${rule.path}")
        }
    }
}

/** 条件匹配与 [RequestMiddleware] 保持一致：为空即不限，大小写不敏感。 */
private fun ruleMatches(rule: ParamOverride, ctx: MiddlewareContext): Boolean {
    val modelHits = rule.whenModel?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { it.equals(ctx.requestedModel, true) || it.equals(ctx.actualModel, true) } ?: true
    val providerHits = rule.whenProvider?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { it.equals(ctx.providerId, true) || it.equals(ctx.providerType, true) } ?: true
    return modelHits && providerHits
}
