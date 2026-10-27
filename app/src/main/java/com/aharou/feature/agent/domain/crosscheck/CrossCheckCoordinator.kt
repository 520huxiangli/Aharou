package com.aharou.feature.agent.domain.crosscheck

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.privacy.maskIfNeeded
import com.aharou.feature.agent.domain.privacy.outboundMaskerOrNull
import com.aharou.feature.agent.domain.privacy.restoreIfNeeded
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.agent.domain.provider.OpenAIAdapter
import com.aharou.feature.agent.domain.provider.fixedTemperature
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.ProviderKeyRotator
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

// 复审结果：Done 带审稿人署名与意见，其余两种是「没有可用的第二个模型」与请求本身失败。
sealed interface CrossCheckOutcome {
    data class Done(val reviewer: String, val review: String) : CrossCheckOutcome
    data object NoOtherModel : CrossCheckOutcome
    data class Failed(val message: String) : CrossCheckOutcome
}

// 让另一个模型复审一段正文。不复用会话链路的一次性调用：completeOnce 永远取当前生效模型，
// 这里要的是「当前模型之外」的那一个，只能自己解析配置并另建一个独立的 provider 实例。
@Singleton
class CrossCheckCoordinator @Inject constructor(
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val sessionUseCase: SessionUseCase,
    private val providerKeyRotator: ProviderKeyRotator,
    private val modelMetadataService: ModelMetadataService,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi
) {

    suspend fun review(sessionId: String?, content: String): CrossCheckOutcome {
        val body = content.trim()
        if (body.isEmpty()) return CrossCheckOutcome.Failed("empty message")
        val reviewer = pickReviewer(sessionId) ?: return CrossCheckOutcome.NoOtherModel
        val masker = outboundMaskerOrNull(generalSettingsRepository.privacyMaskingEnabled())
        val label = "${reviewer.config.name} / ${reviewer.model}"
        return try {
            val provider = buildProvider(reviewer.config, reviewer.model, sessionId)
            val answer = withTimeoutOrNull(REVIEW_TIMEOUT_MS) {
                provider.complete(
                    systemPrompt = SYSTEM_PROMPT,
                    messages = listOf(
                        AgentMessage.UserMessage(
                            content = body.take(MAX_CONTENT_CHARS).maskIfNeeded(masker)
                        )
                    )
                )
            } ?: return CrossCheckOutcome.Failed("timeout")
            val text = answer.content.restoreIfNeeded(masker).trim()
            if (text.isEmpty()) CrossCheckOutcome.Failed("empty response") else CrossCheckOutcome.Done(label, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "复审请求失败：${e.message}", e)
            CrossCheckOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    // 优先换一家供应商；只有一家时退而求其次换个模型——同家同模型不叫「另一个模型」。
    private suspend fun pickReviewer(sessionId: String?): Reviewer? {
        val current = currentConfig(sessionId)
        val usable = runCatching { aiProviderRepository.getAllProviders().first() }
            .getOrDefault(emptyList())
            .filter { it.isEnabled && it.hasUsableApiKey && it.effectiveModel.isNotBlank() }
        usable.firstOrNull { it.id != current?.id }?.let { return Reviewer(it, it.effectiveModel) }
        val same = usable.firstOrNull { it.id == current?.id } ?: return null
        val alternate = same.models.firstOrNull { it != current?.effectiveModel } ?: return null
        return Reviewer(same, alternate)
    }

    // 会话绑定的模型才算「当前模型」；没绑定就按主页记下的默认模型，与聊天链路解析顺序一致。
    private suspend fun currentConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = runCatching { sessionUseCase.getSessionById(sessionId) }.getOrNull()
            val boundId = session?.providerId
            if (!boundId.isNullOrBlank()) {
                val bound = aiProviderRepository.getProviderById(boundId)
                if (bound != null && bound.isEnabled && bound.hasUsableApiKey) {
                    val boundModel = session?.model
                    return if (boundModel.isNullOrBlank()) bound else bound.copy(selectedModel = boundModel)
                }
            }
        }
        val providerId = defaultModelSettingsRepository.getDefaultProviderId()
        val model = defaultModelSettingsRepository.getDefaultModel()
        if (providerId.isBlank() || model.isBlank()) return null
        return aiProviderRepository.getProviderById(providerId)?.copy(selectedModel = model)
    }

    // 独立实例：复审与主对话并发跑，共用 provider 单例会互相改写 apiKey/model。
    private suspend fun buildProvider(config: AIProviderConfig, model: String, sessionId: String?): AIProvider {
        val target = config.copy(selectedModel = model)
        val provider: AIProvider = when (target.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = target.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also { it.chatCacheKeyEnabled = target.openaiChatCacheKey }
        }
        // 免鉴权的本地网关没有 Key，此时 activeKey 为 null，退回空串照发（与主链路一致）。
        provider.apiKey = providerKeyRotator.activeKey(target, sessionId) ?: target.firstUsableApiKey
        provider.baseUrl = target.baseUrl
        provider.model = target.effectiveModel
        provider.useFullUrl = target.useFullUrl
        provider.useResponseApi = target.useResponseApi
        provider.providerId = target.id
        provider.logSessionId = sessionId
        provider.customHeaders = target.customHeaders
        val metadata = modelMetadataService.resolve(target.id, target.type, target.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(target.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    private data class Reviewer(val config: AIProviderConfig, val model: String)

    companion object {
        private const val TAG = "CrossCheck"

        // 复审是用户主动等结果的旁路调用，超时比主链路的单次请求短一些。
        private const val REVIEW_TIMEOUT_MS = 120_000L

        // 单条消息可能很长，截断到与后台一次性调用同量级，避免把整段长文再发一遍。
        private const val MAX_CONTENT_CHARS = 12_000

        private val SYSTEM_PROMPT = """
            你是一位独立的审稿人。用户会给你一段由另一个 AI 模型（或用户本人）写出的正文，请你复核它。
            要求：
            1. 不要改写、不要润色、不要输出替代的全文。
            2. 只指出三类问题：事实或逻辑上的错误、明显缺失的关键内容、表述不严谨或你无法确定的地方。
            3. 按严重程度从高到低分点列出；每点先引用出问题的原句或片段，再说明原因。
            4. 确实没有发现问题时就明确说没有发现明显问题，不要为了凑数编造问题。
            5. 用与原文相同的语言回答。
        """.trimIndent()
    }
}
