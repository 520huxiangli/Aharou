package com.aharou.feature.settings.domain.service

import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LLM 调用费用估算。单价经 [ModelMetadataService] 按「渠道自定义元数据 → models.dev → 内置」三级回退解析，
 * 所以必须带上渠道（providerId + type），只按模型算会取不到渠道自定义单价。
 */
@Singleton
class ModelCostCalculator @Inject constructor(
    private val modelMetadataService: ModelMetadataService
) {
    /**
     * 一组 token 用量的预估费用（USD）；模型无单价返回 null。
     * 缓存读价缺失时按输入价 [CACHE_READ_DISCOUNT] 倍估算，缓存写价缺失时按输入价 [CACHE_WRITE_MARKUP] 倍估算。
     */
    suspend fun costUsd(
        providerId: String,
        providerType: ProviderType,
        model: String,
        inputTokens: Long,
        cachedInputTokens: Long,
        outputTokens: Long,
        cacheCreationTokens: Long = 0
    ): Double? {
        val meta = modelMetadataService.resolve(providerId, providerType, model)
        val inputPrice = meta.inputCostUsdPerM ?: return null
        val outputPrice = meta.outputCostUsdPerM ?: 0.0
        val cachePrice = meta.cacheReadCostUsdPerM ?: inputPrice * CACHE_READ_DISCOUNT
        val cacheWritePrice = meta.cacheWriteCostUsdPerM ?: inputPrice * CACHE_WRITE_MARKUP
        val uncached = (inputTokens - cachedInputTokens).coerceAtLeast(0)
        return (uncached * inputPrice + cachedInputTokens * cachePrice +
            cacheCreationTokens * cacheWritePrice + outputTokens * outputPrice) / 1_000_000.0
    }

    companion object {
        /** 缓存读价缺失时按输入价的折扣估算。 */
        const val CACHE_READ_DISCOUNT = 0.1

        /** 缓存写入单价缺失时相对输入价的倍率（Anthropic 官方为 1.25×）。 */
        const val CACHE_WRITE_MARKUP = 1.25
    }
}
