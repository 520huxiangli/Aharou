package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ProviderKeyRotator
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 造一个用完即弃的 provider：不做工具循环、不带会话状态的一次性调用（旁问、规则整理这类）。
 *
 * 与主链路（StatefulAgentWorkflow）的区别只有「不共享实例」：多 Key 轮换、缓存开关、
 * 超时与重试这些配置口径必须一致，否则同一次任务里两条路的失败表现会不一样。
 */
@Singleton
class OneShotProviderFactory @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val providerKeyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val anthropicApi: AnthropicApi,
    private val openAIApi: OpenAIApi,
    private val geminiApi: GeminiApi,
) {

    /** 按会话绑定的供应商造实例；[sessionId] 为空或绑定不可用时回退默认模型。 */
    suspend fun create(sessionId: String?): AIProvider? {
        val config = resolveConfig(sessionId) ?: return null
        if (config.effectiveModel.isBlank()) return null
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also { it.chatCacheKeyEnabled = config.openaiChatCacheKey }
        }
        provider.apiKey = providerKeyRotator.activeKey(config, sessionId) ?: return null
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId?.takeIf { it.isNotBlank() }
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    private suspend fun resolveConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = runCatching { sessionUseCase.getSessionById(sessionId) }.getOrNull()
            val boundProvider = session?.providerId
            if (!boundProvider.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProvider)
                if (config != null && config.isEnabled && config.hasUsableApiKey) {
                    val boundModel = session?.model
                    if (!boundModel.isNullOrBlank() && boundModel in config.models) {
                        return config.copy(selectedModel = boundModel)
                    }
                    if (boundModel.isNullOrBlank()) return config
                }
            }
        }
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.hasUsableApiKey && defaultModel in config.models) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }
}
