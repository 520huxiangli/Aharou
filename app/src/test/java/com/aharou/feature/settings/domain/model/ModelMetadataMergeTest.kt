package com.aharou.feature.settings.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelMetadataMergeTest {

    private fun meta(
        contextTokens: Int = 128_000,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        inputCost: Double? = null,
        outputCost: Double? = null,
        cacheReadCost: Double? = null
    ) = ModelMetadata(
        id = "gpt-test",
        displayName = "gpt-test",
        contextTokens = contextTokens,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        inputCostUsdPerM = inputCost,
        outputCostUsdPerM = outputCost,
        cacheReadCostUsdPerM = cacheReadCost,
        source = ModelMetadata.Source.MODELS_DEV
    )

    @Test
    fun customPrices_overrideAutoPrices() {
        val auto = meta(inputCost = 5.0, outputCost = 25.0, cacheReadCost = 0.5)
        val custom = meta(inputCost = 1.0, outputCost = 10.0, cacheReadCost = 0.1)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(1.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(10.0, merged.outputCostUsdPerM!!, 0.0)
        assertEquals(0.1, merged.cacheReadCostUsdPerM!!, 0.0)
    }

    @Test
    fun nullCustomPrices_fallBackToAuto() {
        val auto = meta(inputCost = 5.0, outputCost = 25.0, cacheReadCost = 0.5)
        val custom = meta() // 价格全 null

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(5.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(25.0, merged.outputCostUsdPerM!!, 0.0)
        assertEquals(0.5, merged.cacheReadCostUsdPerM!!, 0.0)
    }

    @Test
    fun partialCustomPrices_mergePerField() {
        val auto = meta(inputCost = 5.0, outputCost = 25.0, cacheReadCost = 0.5)
        // 只自定义输入价，输出/缓存回退自动
        val custom = meta(inputCost = 2.0)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(2.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(25.0, merged.outputCostUsdPerM!!, 0.0)
        assertEquals(0.5, merged.cacheReadCostUsdPerM!!, 0.0)
    }

    @Test
    fun customOnlyPrices_usedWhenAutoHasNone() {
        val auto = meta() // 价格全 null
        val custom = meta(inputCost = 3.0, outputCost = 15.0, cacheReadCost = 0.3)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(3.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(15.0, merged.outputCostUsdPerM!!, 0.0)
        assertEquals(0.3, merged.cacheReadCostUsdPerM!!, 0.0)
    }

    @Test
    fun zeroCustomPrice_isKeptNotTreatedAsNull() {
        // 免费模型：价格为 0 是合法值，不能被 null 合并逻辑吞掉
        val auto = meta(inputCost = 5.0, outputCost = 25.0)
        val custom = meta(inputCost = 0.0, outputCost = 0.0)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(0.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(0.0, merged.outputCostUsdPerM!!, 0.0)
    }

    @Test
    fun noCustom_returnsAutoUnchanged() {
        val auto = meta(inputCost = 5.0, outputCost = 25.0, cacheReadCost = 0.5)

        val merged = mergeModelMetadata("gpt-test", auto, null)

        assertEquals(auto, merged)
    }

    @Test
    fun priceOnlyEdit_preservesDistinctWindows() {
        val auto = meta(contextTokens = 200_000, inputTokens = 160_000, outputTokens = 32_000)
        val custom = auto.copy(inputCostUsdPerM = 1.0)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(200_000, merged.contextTokens)
        assertEquals(160_000, merged.inputTokens)
        assertEquals(32_000, merged.outputTokens)
        assertEquals(1.0, merged.inputCostUsdPerM!!, 0.0)
    }

    @Test
    fun priceOnlyCustomWithBlankWindows_preservesAutoWindows() {
        val auto = meta(contextTokens = 200_000, inputTokens = 160_000, outputTokens = 32_000)
        val custom = meta(contextTokens = 0, inputCost = 1.0)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(200_000, merged.contextTokens)
        assertEquals(160_000, merged.inputTokens)
        assertEquals(32_000, merged.outputTokens)
    }

    @Test
    fun customInputWindow_doesNotReplaceTotalContextWindow() {
        val auto = meta(contextTokens = 200_000, inputTokens = 160_000)
        val custom = meta(contextTokens = 0, inputTokens = 120_000)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(200_000, merged.contextTokens)
        assertEquals(120_000, merged.inputTokens)
    }

    @Test
    fun customTotalContextWindow_doesNotReplaceInputWindow() {
        val auto = meta(contextTokens = 200_000, inputTokens = 160_000)
        val custom = meta(contextTokens = 180_000)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(180_000, merged.contextTokens)
        assertEquals(160_000, merged.inputTokens)
    }

    @Test
    fun priceOnlyEdit_preservesAbsentInputWindow() {
        val auto = meta(contextTokens = 200_000, outputTokens = 32_000)
        val custom = auto.copy(inputCostUsdPerM = 1.0)

        val merged = mergeModelMetadata("gpt-test", auto, custom)

        assertEquals(200_000, merged.contextTokens)
        assertNull(merged.inputTokens)
        assertEquals(32_000, merged.outputTokens)
    }

    @Test
    fun customWithoutAuto_fallsBackToDefaultsWithNullPrices() {
        val custom = meta(inputCost = 1.0, outputCost = 2.0)

        val merged = mergeModelMetadata("gpt-test", null, custom)

        assertNull(merged.cacheReadCostUsdPerM)
        assertEquals(1.0, merged.inputCostUsdPerM!!, 0.0)
        assertEquals(2.0, merged.outputCostUsdPerM!!, 0.0)
        // 能力/窗口合并不受影响
        assertEquals(128_000, merged.contextTokens)
    }
}
