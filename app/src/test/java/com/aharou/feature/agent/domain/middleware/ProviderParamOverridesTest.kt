package com.aharou.feature.agent.domain.middleware

import com.aharou.feature.agent.domain.provider.AIProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class ProviderParamOverridesTest {

    private val provider = mockk<AIProvider>(relaxed = true)

    private fun raw(json: String) = json

    @Test
    fun temperatureOverride_isApplied() {
        applyProviderParamOverrides(
            raw("""{"paramOverrides":[{"path":"temperature","op":"SET","value":0.2}]}"""),
            "provider", "OPENAI", "model", provider
        )
        verify { provider.temperature = 0.2f }
    }

    @Test
    fun maxTokensAliases_areApplied() {
        applyProviderParamOverrides(
            raw("""{"paramOverrides":[{"path":"max_tokens","op":"SET","value":1024}]}"""),
            "provider", "ANTHROPIC", "model", provider
        )
        verify { provider.maxOutputTokens = 1024 }
    }

    @Test
    fun removeOperation_clearsField() {
        applyProviderParamOverrides(
            raw("""{"paramOverrides":[{"path":"max_tokens","op":"REMOVE"}]}"""),
            "provider", "GEMINI", "model", provider
        )
        verify { provider.maxOutputTokens = null }
    }

    @Test
    fun unsupportedPath_isIgnored() {
        applyProviderParamOverrides(
            raw("""{"paramOverrides":[{"path":"top_p","op":"SET","value":0.9}]}"""),
            "provider", "OPENAI", "model", provider
        )
        verify(exactly = 0) { provider.temperature = any() }
        verify(exactly = 0) { provider.maxOutputTokens = any() }
    }

    @Test
    fun disabledConfig_doesNothing() {
        applyProviderParamOverrides(
            raw("""{"enabled":false,"paramOverrides":[{"path":"temperature","op":"SET","value":0.5}]}"""),
            "provider", "OPENAI", "model", provider
        )
        verify(exactly = 0) { provider.temperature = any() }
    }

    @Test
    fun whenModelCondition_filtersRules() {
        every { provider.temperature } returns null
        applyProviderParamOverrides(
            raw("""{"paramOverrides":[{"path":"temperature","op":"SET","value":0.7,"whenModel":"other-model"}]}"""),
            "provider", "OPENAI", "model", provider
        )
        verify(exactly = 0) { provider.temperature = any() }
    }
}
