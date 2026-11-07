package com.aharou.feature.agent.domain.tool.config

import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.remote.ModelApiService
import com.aharou.feature.settings.data.remote.ModelTestResult
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigModelTestActionTest {
    private val providers = mockk<AIProviderRepository>(relaxed = true)
    private val service = mockk<ModelApiService>()
    private val action = ConfigModelTestAction(providers, service)

    @Test fun missingProviderAndArgumentsDoNotSendRequests() = runTest {
        assertTrue(action.execute(emptyMap()) is ToolResult.Error)
        coEvery { providers.getProviderById("missing") } returns null
        assertTrue(action.execute(mapOf("provider_id" to JsonPrimitive("missing"), "model" to JsonPrimitive("model"))) is ToolResult.Error)
        coVerify(exactly = 0) { service.testModel(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test fun resultDoesNotReflectCredentialsOrRawResponseAndNeverSaves() = runTest {
        val provider = mockk<AIProviderConfig>(relaxed = true)
        io.mockk.every { provider.baseUrl } returns "https://example.invalid"
        io.mockk.every { provider.firstUsableApiKey } returns "fixture-secret"
        io.mockk.every { provider.type } returns ProviderType.OPENAI
        coEvery { providers.getProviderById("provider") } returns provider
        coEvery { service.testModel(any(), any(), any(), any(), any(), any(), any()) } returns ModelTestResult(
            false, 12, "fixture-secret", responseCode = 200, responseBody = "fixture-secret")
        val result = action.execute(mapOf("provider_id" to JsonPrimitive("provider"), "model" to JsonPrimitive("model")))
        assertTrue(result is ToolResult.Success)
        assertFalse(result.toString().contains("fixture-secret"))
        coVerify(exactly = 0) { providers.saveProvider(any()) }
    }
}
