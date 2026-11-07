package com.aharou.feature.agent.domain.tool.config

import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.remote.ModelApiService
import com.aharou.feature.settings.data.remote.ModelTestResponseValidator
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class ConfigModelTestAction @Inject constructor(
    private val providers: AIProviderRepository,
    private val service: ModelApiService,
) {
    suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val id = (args["provider_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val model = (args["model"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (id.isBlank() || model.isBlank()) return ToolResult.Error("test_model 需要 provider_id 和 model", "INVALID_ARGS")
        val provider = providers.getProviderById(id) ?: return ToolResult.Error("供应商不存在", "PROVIDER_NOT_FOUND")
        return try {
            val result = service.testModel(provider.baseUrl, provider.firstUsableApiKey, provider.type,
                provider.useFullUrl, provider.useResponseApi, model, provider.customHeaders)
            ToolResult.Success(buildJsonObject {
                put("success", result.success)
                put("provider_id", id)
                put("model", model)
                put("latency_ms", result.latencyMs)
                put("http_code", result.responseCode)
                put("protocol", ModelTestResponseValidator.protocol(provider.type, provider.useResponseApi, model, result.requestUrl).name)
                // 服务端响应可能反射请求中的凭据，工具结果不携带原始诊断内容。
                put("message", when {
                    result.success -> "模型测试通过"
                    result.responseCode in 200..299 -> "HTTP成功，但响应协议或业务结果无效"
                    result.responseCode > 0 -> "模型测试失败，HTTP ${result.responseCode}"
                    else -> "模型测试请求失败，请检查地址和网络"
                })
            })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ToolResult.Error("模型测试请求失败，请检查供应商配置与网络", "MODEL_TEST_FAILED")
        }
    }
}
