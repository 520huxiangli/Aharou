package com.aharou.feature.agent.domain.tool.file

import com.aharou.core.util.AILogger
import com.aharou.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aharou.feature.agent.data.remote.openai.ImageGenerationResponse
import com.aharou.feature.agent.data.remote.openai.ImageGenerationResult
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.data.remote.openai.OpenAIChatMessage
import com.aharou.feature.agent.domain.provider.EnrichedHttpException
import com.aharou.feature.agent.domain.provider.enrichWithHttpErrorBody
import com.aharou.feature.agent.domain.provider.joinUrl
import com.aharou.feature.settings.domain.model.AIProviderConfig
import kotlinx.coroutines.CancellationException
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「Chat Completions 出图」兜底通道。
 *
 * 不少中转站 / 自建网关不实现 OpenAI 的 Images 端点（POST /v1/images/generations 一律 404），
 * 但会在 Chat Completions 的响应里回图：`message.images` 的 `image_url`，或 content 多模态
 * parts 里的图片项。生图工具在 Images 端点缺失时回落到这里，把 chat 响应解析成
 * [ImageGenerationResponse]，落盘与展示完全复用生图工具既有链路。
 *
 * 出图请求不携带 images 端点特有的参数（size / quality / n 等），这些参数在该通道下不生效。
 */
@Singleton
class ChatImageFallback @Inject constructor(
    private val openAIApi: OpenAIApi
) {

    suspend fun generate(
        provider: AIProviderConfig,
        apiKey: String,
        extraHeaders: Map<String, String>,
        prompt: String,
        sessionId: String?
    ): ImageGenerationResponse {
        val model = provider.effectiveModel
        val url = joinUrl(provider.baseUrl, "v1/chat/completions")
        val request = ChatCompletionRequest(
            model = model,
            messages = listOf(OpenAIChatMessage(role = "user", content = prompt)),
            stream = false
        )
        val seq = AILogger.logRequest(sessionId, provider.id, model, "POST", url, request)
        val response = try {
            openAIApi.createChatCompletion(
                url = url,
                authorization = "Bearer $apiKey",
                extraHeaders = extraHeaders,
                request = request
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val enriched = e.enrichWithHttpErrorBody()
            AILogger.logError(sessionId, provider.id, enriched, seq)
            throw enriched
        }
        AILogger.logResponse(sessionId, provider.id, response, seq)

        val data = response.choices.firstOrNull()?.message?.let { extractImageResults(it) }.orEmpty()
        if (data.isEmpty()) {
            throw IOException("$model 未在 Chat 响应里返回图片：该模型或该供应商不支持 chat 出图。")
        }
        return ImageGenerationResponse(created = response.created, data = data)
    }

    private fun extractImageResults(message: OpenAIChatMessage): List<ImageGenerationResult> {
        val results = mutableListOf<ImageGenerationResult>()
        val seen = HashSet<String>()
        // 同一个 data URL 可能同时出现在 images 数组与 content parts 里，去重避免重复落盘。
        fun add(rawUrl: String?) {
            val url = rawUrl?.trim().orEmpty()
            if (url.isEmpty() || !seen.add(url)) return
            val base64 = dataUrlBase64(url)
            when {
                base64 != null -> results.add(ImageGenerationResult(b64_json = base64))
                url.startsWith("http", ignoreCase = true) -> results.add(ImageGenerationResult(url = url))
            }
        }

        message.images?.forEach { add(it.image_url?.url) }

        when (val content = message.content) {
            is List<*> -> content.forEach { part ->
                val map = part as? Map<*, *> ?: return@forEach
                add(
                    when (val imageUrl = map["image_url"] ?: map["image"]) {
                        is String -> imageUrl
                        is Map<*, *> -> imageUrl["url"] as? String
                        else -> null
                    }
                )
            }
            is String -> {
                DATA_URL_REGEX.findAll(content).forEach { add(it.value) }
                MARKDOWN_IMAGE_REGEX.findAll(content).forEach { add(it.groupValues[1]) }
            }
        }
        return results
    }

    /** `data:<mime>;base64,<数据>` → 数据段；非 data URL 或缺少 base64 标记时返回 null。 */
    private fun dataUrlBase64(url: String): String? {
        if (!url.startsWith("data:", ignoreCase = true)) return null
        val marker = url.indexOf(";base64,", ignoreCase = true)
        if (marker < 0) return null
        return url.substring(marker + ";base64,".length).takeIf { it.isNotBlank() }
    }

    private companion object {
        val DATA_URL_REGEX = Regex("data:image/[\\w.+-]+;base64,[A-Za-z0-9+/=]+", RegexOption.IGNORE_CASE)
        val MARKDOWN_IMAGE_REGEX = Regex("!\\[[^\\]]*\\]\\((https?://[^\\s)]+)\\)")
    }
}

/** Images 端点未实现时的状态码：路由不存在（404）、方法不允许（405）、未实现（501）。 */
private val IMAGES_ENDPOINT_MISSING_CODES = setOf(404, 405, 501)

/**
 * 判断生图失败是否属于「该供应商根本没有 Images 端点」——只有这类失败才回落到 chat 出图。
 * 鉴权、限流、超时、参数错误等回落到 chat 同样无益，仍按原错误上报。
 */
internal fun Throwable.isImagesEndpointUnavailable(): Boolean {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is EnrichedHttpException && current.statusCode in IMAGES_ENDPOINT_MISSING_CODES) return true
        current = current.cause
        depth++
    }
    val text = message?.lowercase().orEmpty()
    return IMAGES_ENDPOINT_MISSING_CODES.any { text.contains("http $it") } || text.contains("not found")
}

private const val MAX_CAUSE_DEPTH = 5
