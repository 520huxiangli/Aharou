package com.aharou.feature.agent.domain.tool.file

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.LlmCallRecordDao
import com.aharou.feature.agent.data.local.entity.LlmCallRecordEntity
import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.ocr.TesseractOcrEngine
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AIResponse
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.fixedTemperature
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.agent.domain.provider.OpenAIAdapter
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.settings.data.remote.ModelMetadataService
import com.aharou.feature.settings.data.repository.DefaultModelSettingsRepository
import com.aharou.feature.settings.data.repository.GeneralSettingsRepository
import com.aharou.feature.settings.data.repository.VisionModelSettingsRepository
import com.aharou.feature.settings.domain.model.AIProviderConfig
import com.aharou.feature.settings.domain.model.ProviderType
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import com.aharou.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URLConnection
import java.util.UUID
import javax.inject.Inject

/**
 * 多轮会话式识图工具：第一次传 images（1~5 张）由识图模型一次性分析/对比，返回 vision_id 与文本结果；
 * 之后传 vision_id + prompt 在同一识图会话内继续追问（识图模型记得图片与之前的问答）。
 * 会话请求体落盘在 ~/.aharou/vision-sessions/（见 [VisionSessionStore]）。
 * 识图模型不校验视觉能力，调用失败时把错误信息原样作为工具结果返回。
 */
class ViewImageTool @Inject constructor(
    private val fileAccess: FileAccessProvider,
    private val visionSessionStore: VisionSessionStore,
    private val aiProviderRepository: AIProviderRepository,
    private val defaultModelSettingsRepository: DefaultModelSettingsRepository,
    private val generalSettingsRepository: GeneralSettingsRepository,
    private val visionModelSettingsRepository: VisionModelSettingsRepository,
    private val ocrEngine: TesseractOcrEngine,
    private val modelMetadataService: ModelMetadataService,
    private val sessionUseCase: SessionUseCase,
    private val llmCallRecordDao: LlmCallRecordDao,
    private val openAIApi: OpenAIApi,
    private val anthropicApi: AnthropicApi,
    private val geminiApi: GeminiApi
) : AbstractContextualTool() {
    override val name = "viewImage"
    override val description = "查看本地图片并交给识图模型分析，返回分析结果与 vision_id。传 images 发起识图（可多张对比），传 id 继续同一识图会话追问。"
    override val capabilities = setOf(ToolCapability.READ_WORKSPACE)
    override val parameters = mapOf(
        "images" to ToolParameter(
            name = "images",
            type = ParameterType.ARRAY,
            description = "图片路径列表，1~5 张；与 id 二选一，首次识图必传。路径为 ~/workspace/... 或容器绝对路径。",
            required = false,
            itemsSchema = mapOf("type" to "string")
        ),
        "id" to ToolParameter(
            name = "id",
            type = ParameterType.STRING,
            description = "识图会话 id，用于继续之前的识图会话追问；与 images 二选一。",
            required = false
        ),
        "prompt" to ToolParameter(
            name = "prompt",
            type = ParameterType.STRING,
            description = "可选提问或关注点；为空时默认描述图片内容。",
            required = false
        ),
        "detail" to ToolParameter(
            name = "detail",
            type = ParameterType.STRING,
            description = "清晰度：high（默认，大图压缩到最长边 1536）；original（原样直传，多张大图可能超限失败）；low（压缩到最长边 512 省 token）。",
            required = false,
            enum = listOf("low", "high", "original")
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        // 会话绑定的工作区：本会话的文件操作只落在自己的工作区，不跟着全局切换走
        val access = (fileAccess).forWorkspace(context.projectRoot)
        val images = parseImages(args)
        val id = args["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val prompt = args["prompt"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val detail = args["detail"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?.takeIf { it in SUPPORTED_DETAILS } ?: "high"

        if (images.isNotEmpty() && id.isNotEmpty()) {
            return ToolResult.Error("images 与 id 二选一：传 images 开启新的识图会话，传 id 继续之前的会话。", "INVALID_ARGS")
        }
        if (images.isEmpty() && id.isEmpty()) {
            return ToolResult.Error("缺少参数：首次识图需传 images（1~5 张图片路径），继续追问需传 id 与 prompt。", "MISSING_ARGS")
        }
        if (images.size > MAX_IMAGES) {
            return ToolResult.Error("一次最多传 $MAX_IMAGES 张图片，当前 ${images.size} 张。", "TOO_MANY_IMAGES")
        }
        val supportsVision = isCurrentChatModelSupportsVision(context.sessionId)
        return if (supportsVision && images.isNotEmpty()) {
            loadImagesDirectlyToContext(access, images, prompt, detail)
        } else if (images.isNotEmpty()) {
            createSession(access, images, prompt, detail, context.sessionId)
        } else {
            continueSession(id, prompt, context.sessionId)
        }
    }

    private fun loadImagesDirectlyToContext(
        access: FileAccessProvider,
        images: List<String>,
        prompt: String,
        detail: String
    ): ToolResult {
        val encoded = mutableListOf<AgentImage>()
        for (path in images) {
            when (val r = encodeImage(access, path, detail)) {
                is EncodeOutcome.Ok -> encoded.add(r.image)
                is EncodeOutcome.Fail -> return ToolResult.Error(r.message, r.code)
            }
        }
        val promptNotice = if (prompt.isNotBlank()) "（关注点/提问：$prompt）" else ""
        val content = "已将 ${encoded.size} 张图片加载至当前对话上下文$promptNotice，你可以直接查看并分析图片内容。"
        return ToolResult.Success(
            data = JsonObject(
                mapOf(
                    "status" to JsonPrimitive("loaded_to_context"),
                    "image_count" to JsonPrimitive(encoded.size),
                    "content" to JsonPrimitive(content),
                    "paths" to JsonArray(images.map { JsonPrimitive(it) })
                )
            ),
            images = encoded
        )
    }

    private suspend fun createSession(access: FileAccessProvider, images: List<String>, prompt: String, detail: String, sessionId: String?): ToolResult {
        // 按 detail 档位逐张编码：original 全部原样；high 小图原样、大图压缩；low 全部压缩。
        val encoded = mutableListOf<AgentImage>()
        for (path in images) {
            when (val r = encodeImage(access, path, detail)) {
                is EncodeOutcome.Ok -> encoded.add(r.image)
                is EncodeOutcome.Fail -> return ToolResult.Error(r.message, r.code)
            }
        }
        val id = UUID.randomUUID().toString().replace("-", "").take(8)
        val effectivePrompt = prompt.ifBlank { defaultPrompt(images.size) }
        val messages = listOf(
            AgentMessage.UserMessage(content = effectivePrompt, images = encoded)
        )
        return try {
            visionSessionStore.save(id, messages)
            // 未配识图模型时（=跟随聊天模型）先用本机 OCR 兜一下：聊天模型多半不能看图，
            // 让它空转一轮再报错，不如直接把字认出来。认出东西就收工，一张都没认到才走模型。
            if (ocrEnabled() && visionModelSettingsRepository.getVisionProviderId().isBlank()) {
                val localText = encoded.mapNotNull { ocrEngine.recognize(it) }
                    .joinToString("\n\n").trim()
                if (localText.isNotEmpty()) {
                    val content = "（本机 OCR 结果，未使用识图模型）\n\n$localText"
                    visionSessionStore.save(id, messages + AgentMessage.AssistantMessage(content = content))
                    return successResult(id, content)
                }
            }
            val provider = resolveVisionProvider(sessionId)
            val response = callVisionProvider(provider, messages, sessionId)
            val content = response.content.ifBlank { "（识图模型未返回内容）" }
            visionSessionStore.save(id, messages + AgentMessage.AssistantMessage(content = content))
            successResult(id, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            visionSessionStore.delete(id)
            FileLogger.e(TAG, "识图失败", e)
            ToolResult.Error(e.message ?: "识图调用失败", "VISION_CALL_FAILED")
        }
    }

    private suspend fun continueSession(id: String, prompt: String, sessionId: String?): ToolResult {
        if (prompt.isBlank()) {
            return ToolResult.Error("继续识图会话需要 prompt 提问内容。", "MISSING_PROMPT")
        }
        val history = visionSessionStore.load(id)
            ?: return ToolResult.Error("识图会话不存在或已过期: $id", "SESSION_NOT_FOUND")
        val messages = history + AgentMessage.UserMessage(content = prompt)
        return try {
            val provider = resolveVisionProvider(sessionId)
            val response = callVisionProvider(provider, messages, sessionId)
            val content = response.content.ifBlank { "（识图模型未返回内容）" }
            visionSessionStore.save(id, messages + AgentMessage.AssistantMessage(content = content))
            successResult(id, content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "识图失败", e)
            ToolResult.Error(e.message ?: "识图调用失败", "VISION_CALL_FAILED")
        }
    }

    private suspend fun callVisionProvider(
        provider: AIProvider,
        messages: List<AgentMessage>,
        sessionId: String?
    ): AIResponse {
        val callStartWall = System.currentTimeMillis()
        val callStartElapsed = android.os.SystemClock.elapsedRealtime()
        var callCompleted = false
        var callError: String? = null
        var usage: AIResponse? = null
        return try {
            val response = provider.complete("", messages, emptyList())
            usage = response
            callCompleted = true
            response
        } catch (e: CancellationException) {
            callError = "cancelled"
            throw e
        } catch (e: Exception) {
            callError = e.message ?: e.javaClass.simpleName
            throw e
        } finally {
            val durationMillis = (android.os.SystemClock.elapsedRealtime() - callStartElapsed).toInt()
            runCatching {
                llmCallRecordDao.insert(
                    LlmCallRecordEntity(
                        sessionId = sessionId,
                        providerId = provider.providerId.ifBlank { null },
                        model = provider.model,
                        kind = "vision",
                        inputTokens = usage?.inputTokens ?: 0,
                        outputTokens = usage?.outputTokens ?: 0,
                        cachedInputTokens = usage?.cachedInputTokens ?: 0,
                        cacheCreationTokens = usage?.cacheCreationTokens ?: 0,
                        ttfbMillis = null,
                        durationMillis = durationMillis,
                        status = if (callCompleted) "success" else "error",
                        errorMessage = callError,
                        stopReason = usage?.stopReason,
                        createdAt = callStartWall
                    )
                )
            }
        }
    }

    private fun successResult(id: String, content: String): ToolResult = ToolResult.Success(
        JsonObject(
            mapOf(
                "vision_id" to JsonPrimitive(id),
                "content" to JsonPrimitive(content)
            )
        )
    )

    private fun parseImages(args: Map<String, JsonElement>): List<String> {
        val array = args["images"] as? JsonArray ?: return emptyList()
        return array.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }.filter { it.isNotEmpty() }
    }

    private fun defaultPrompt(count: Int): String = if (count == 1) {
        "请详细描述这张图片的内容，包括其中出现的文字、元素、布局、颜色等关键信息。"
    } else {
        "请依次分析并对比这 $count 张图片：先分别描述每张图片的内容（包括文字、元素、布局、颜色等关键信息），再指出它们之间的相同点与差异。"
    }

    /**
     * 识图模型解析：配置了「识图模型」用专用模型，否则用当前聊天模型
     * （session 绑定 provider 优先，回退全局默认）。不校验模型的视觉能力。
     */
    private suspend fun isCurrentChatModelSupportsVision(sessionId: String?): Boolean {
        val config = resolveCurrentChatConfig(sessionId) ?: return false
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        return metadata.supportsVision
    }

    /** 本机 OCR 兜底开关；与发送前自动转换共用同一个偏好。 */
    private suspend fun ocrEnabled(): Boolean = generalSettingsRepository.ocrForTextOnlyModels()

    private suspend fun resolveVisionProvider(sessionId: String?): AIProvider {
        val visionProviderId = visionModelSettingsRepository.getVisionProviderId().trim()
        val visionModel = visionModelSettingsRepository.getVisionModel().trim()
        if (visionProviderId.isNotEmpty() && visionModel.isNotEmpty()) {
            val config = aiProviderRepository.getProviderById(visionProviderId)
            if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                return createStandaloneProvider(config.copy(selectedModel = visionModel), sessionId)
            }
        }
        val config = resolveCurrentChatConfig(sessionId)
            ?: throw IllegalStateException("尚未配置 AI 供应商，请到设置中添加并选择一个")
        if (config.apiKey.isBlank()) throw IllegalStateException("「${config.name}」未填写 API Key")
        if (config.effectiveModel.isBlank()) throw IllegalStateException("「${config.name}」未选择模型")
        return createStandaloneProvider(config, sessionId)
    }

    private suspend fun resolveCurrentChatConfig(sessionId: String?): AIProviderConfig? {
        if (sessionId != null) {
            val session = sessionUseCase.getSessionById(sessionId)
            val boundProviderId = session?.providerId
            val boundModel = session?.model
            if (!boundProviderId.isNullOrBlank()) {
                val config = aiProviderRepository.getProviderById(boundProviderId)
                if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                    return if (!boundModel.isNullOrBlank()) config.copy(selectedModel = boundModel) else config
                }
            }
        }
        val defaultProviderId = defaultModelSettingsRepository.getDefaultProviderId()
        val defaultModel = defaultModelSettingsRepository.getDefaultModel()
        if (defaultProviderId.isNotBlank() && defaultModel.isNotBlank()) {
            val config = aiProviderRepository.getProviderById(defaultProviderId)
            if (config != null && config.isEnabled && config.apiKey.isNotBlank()) {
                return config.copy(selectedModel = defaultModel)
            }
        }
        return null
    }

    private suspend fun createStandaloneProvider(config: AIProviderConfig, sessionId: String?): AIProvider {
        val provider: AIProvider = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(anthropicApi).also {
                it.cacheBreakpointsEnabled = config.anthropicCacheBreakpoints
            }
            ProviderType.GEMINI -> GeminiAdapter(geminiApi)
            else -> OpenAIAdapter(openAIApi).also {
                it.chatCacheKeyEnabled = config.openaiChatCacheKey
            }
        }
        provider.apiKey = config.apiKey
        provider.baseUrl = config.baseUrl
        provider.model = config.effectiveModel
        provider.useFullUrl = config.useFullUrl
        provider.useResponseApi = config.useResponseApi
        provider.providerId = config.id
        provider.logSessionId = sessionId
        provider.customHeaders = config.customHeaders
        val metadata = modelMetadataService.resolve(config.id, config.type, config.effectiveModel)
        provider.maxOutputTokens = metadata.outputTokens
        provider.temperature = if (metadata.supportsCustomTemperature) fixedTemperature(config.effectiveModel) else null
        provider.firstByteTimeoutMs = generalSettingsRepository.firstByteTimeoutMs()
        provider.streamIdleTimeoutMs = generalSettingsRepository.streamIdleTimeoutMs()
        provider.maxNetworkRetries = generalSettingsRepository.maxNetworkRetries()
        return provider
    }

    private fun encodeImage(access: FileAccessProvider, path: String, detail: String): EncodeOutcome {
        return try {
            val file = access.copyToLocal(path)
            FileLogger.d(TAG, "viewImage path=$path -> ${file.absolutePath}, detail=$detail")
            if (!access.exists(path)) return EncodeOutcome.Fail("文件不存在: $path", "FILE_NOT_FOUND")
            if (!access.isFile(path)) return EncodeOutcome.Fail("路径不是文件: $path", "NOT_A_FILE")
            val fileSize = access.fileSize(path)
            if (fileSize <= 0L) return EncodeOutcome.Fail("图片文件为空: $path", "EMPTY_FILE")

            val bounds = decodeBounds(file)
                ?: return EncodeOutcome.Fail("无法识别图片格式: $path", "UNSUPPORTED_IMAGE")
            val sourceMime = guessMimeType(file)
            if (!sourceMime.startsWith("image/")) {
                return EncodeOutcome.Fail("不是支持的图片文件: $path", "UNSUPPORTED_IMAGE")
            }

            val originalOk = sourceMime in ORIGINAL_MIME_TYPES
            val encoded = when (detail) {
                "low" -> encodePreview(file, bounds, LOW_MAX_EDGE, LOW_TARGET_BYTES, detail)
                else -> if (originalOk && fileSize <= MAX_ORIGINAL_BYTES) {
                    originalImage(access, path, bounds, sourceMime, fileSize)
                } else {
                    encodePreview(file, bounds, HIGH_MAX_EDGE, HIGH_TARGET_BYTES, detail)
                }
            }
            EncodeOutcome.Ok(
                AgentImage(
                    mimeType = encoded.mimeType,
                    base64Data = encoded.base64Data,
                    path = access.toDisplayPath(path)
                )
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "viewImage 编码异常: $path", e)
            EncodeOutcome.Fail(e.message ?: "读取图片失败", "READ_IMAGE_ERROR")
        }
    }

    private fun originalImage(access: FileAccessProvider, path: String, bounds: ImageBounds, mime: String, size: Long): EncodedImage =
        EncodedImage(
            mimeType = mime,
            base64Data = Base64.encodeToString(access.readBytes(path), Base64.NO_WRAP),
            width = bounds.width,
            height = bounds.height,
            detail = "original",
            encodedBytes = size
        )

    private fun decodeBounds(file: File): ImageBounds? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        val width = options.outWidth
        val height = options.outHeight
        return if (width > 0 && height > 0) ImageBounds(width, height) else null
    }

    private fun encodePreview(file: File, bounds: ImageBounds, maxEdge: Int, targetBytes: Int, detail: String): EncodedImage {
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.width, bounds.height, maxEdge)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalArgumentException("无法解码图片: ${file.name}")

        try {
            val scaled = scaleToMaxEdge(decoded, maxEdge)
            try {
                val encoded = compressJpeg(scaled, targetBytes)
                return EncodedImage(
                    mimeType = "image/jpeg",
                    base64Data = Base64.encodeToString(encoded, Base64.NO_WRAP),
                    width = scaled.width,
                    height = scaled.height,
                    detail = detail,
                    encodedBytes = encoded.size.toLong()
                )
            } finally {
                if (scaled !== decoded) scaled.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        var halfWidth = width / 2
        var halfHeight = height / 2
        while (halfWidth / sample >= maxEdge && halfHeight / sample >= maxEdge) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun compressJpeg(bitmap: Bitmap, targetBytes: Int): ByteArray {
        var best = ByteArray(0)
        for (quality in JPEG_QUALITIES) {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            best = bytes
            if (bytes.size <= targetBytes) break
        }
        return best
    }

    private fun guessMimeType(file: File): String {
        val extMime = when (file.extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "bmp" -> "image/bmp"
            else -> null
        }
        return extMime ?: URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
    }

    private sealed interface EncodeOutcome {
        data class Ok(val image: AgentImage) : EncodeOutcome
        data class Fail(val message: String, val code: String) : EncodeOutcome
    }

    private data class ImageBounds(val width: Int, val height: Int)

    private data class EncodedImage(
        val mimeType: String,
        val base64Data: String,
        val width: Int,
        val height: Int,
        val detail: String,
        val encodedBytes: Long
    )

    private companion object {
        const val TAG = "ImageTools"
        const val MAX_IMAGES = 5
        const val LOW_MAX_EDGE = 512
        const val HIGH_MAX_EDGE = 1536
        const val LOW_TARGET_BYTES = 96 * 1024
        const val HIGH_TARGET_BYTES = 512 * 1024
        const val MAX_ORIGINAL_BYTES = 4 * 1024 * 1024
        val JPEG_QUALITIES = listOf(90, 86, 78, 70, 62)
        val SUPPORTED_DETAILS = setOf("low", "high", "original")
        val ORIGINAL_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")
    }
}
