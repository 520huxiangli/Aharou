package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.domain.provider.joinUrl
import com.aharou.feature.settings.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 拉取模型列表的结果，包含解析出的模型列表及完整的调试信息。 */
data class FetchModelsResult(
    val models: List<String>,
    val debugInfo: ModelTestResult
)

/** 拉取模型异常，附带调试信息。 */
class FetchModelsException(
    override val message: String,
    val debugInfo: ModelTestResult?
) : RuntimeException(message)

/** 测试模型连通性的结果，包含完整的请求/响应调试信息。 */
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
    val requestUrl: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val requestBody: String = "",
    val responseCode: Int = 0,
    val responseHeaders: Map<String, String> = emptyMap(),
    val responseBody: String = "",
    val errorDetail: String = ""
)

/**
 * 直接通过 OkHttp 调用提供商的 REST 接口来拉取模型列表与测试连通性，
 * 复用全局 OkHttpClient，独立于聊天用的 Retrofit 适配器。
 */
@Singleton
class ModelApiService @Inject constructor(
    private val client: OkHttpClient,
    @param:ApplicationContext private val context: Context
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** 拉取提供商可用模型列表（OpenAI 兼容 / Anthropic 均为 GET /v1/models，Gemini 为 GET /v1beta/models）。 */
    suspend fun fetchModels(
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean = false,
        customHeaders: Map<String, String> = emptyMap()
    ): Result<FetchModelsResult> = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        // recoverCatching 在外层作用域，看不到块内的 url 与请求头；
        // 排错时最需要的恰好是「请求发到哪了」，所以先带出来再兜底。
        var failedUrl = ""
        var failedHeaders: Map<String, String> = emptyMap()
        runCatching {
            if (apiKey.isBlank()) error(context.getString(R.string.provider_api_key_required))

            val modelsPath = if (type == ProviderType.GEMINI) "v1beta/models" else "v1/models"
            val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, modelsPath)
            failedUrl = url
            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .get()
                .build()

            val reqHeadersMap = request.headers.names().associateWith { name ->
                val raw = request.header(name).orEmpty()
                if (name.equals("Authorization", ignoreCase = true) || name.equals("x-api-key", ignoreCase = true)) {
                    if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"
                } else {
                    raw
                }
            }
            failedHeaders = reqHeadersMap

            client.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (!response.isSuccessful) {
                    val debug = ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = "",
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = body
                    )
                    throw FetchModelsException("HTTP ${response.code}: ${body.take(200)}", debug)
                }

                val jsonObj = json.parseToJsonElement(body).jsonObject
                val data = if (type == ProviderType.GEMINI) {
                    jsonObj["models"]?.jsonArray
                } else {
                    jsonObj["data"]?.jsonArray
                } ?: run {
                    val debug = ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = context.getString(R.string.provider_fetch_missing_fields),
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = "",
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = context.getString(R.string.provider_fetch_missing_fields_detail, body)
                    )
                    throw FetchModelsException(context.getString(R.string.provider_fetch_missing_fields), debug)
                }

                val modelList = data.mapNotNull { 
                        it.jsonObject[if (type == ProviderType.GEMINI) "name" else "id"]?.jsonPrimitive?.contentOrNull 
                    }
                    .map { if (type == ProviderType.GEMINI) it.removePrefix("models/") else it }
                    .filter { it.isNotBlank() }
                    .sorted()

                val debug = ModelTestResult(
                    success = true,
                    latencyMs = latency,
                    message = context.getString(R.string.provider_fetch_success, modelList.size, latency),
                    requestUrl = url,
                    requestHeaders = reqHeadersMap,
                    requestBody = "",
                    responseCode = response.code,
                    responseHeaders = respHeadersMap,
                    responseBody = body
                )
                FetchModelsResult(modelList, debug)
            }
        }.recoverCatching { e ->
            if (e is FetchModelsException) {
                throw e
            }
            val latency = (System.nanoTime() - start) / 1_000_000
            val fallback = context.getString(R.string.settings_models_fetch_failed)
            val debug = ModelTestResult(
                success = false,
                latencyMs = latency,
                message = e.message ?: fallback,
                requestUrl = failedUrl,
                requestHeaders = failedHeaders,
                errorDetail = e.stackTraceToString()
            )
            throw FetchModelsException(e.message ?: fallback, debug)
        }
    }

    /** 发送一条极短请求验证 Key + 模型 + 端点是否可用，返回耗时。 */
    suspend fun testModel(
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean,
        useResponseApi: Boolean,
        model: String,
        customHeaders: Map<String, String> = emptyMap()
    ): ModelTestResult = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        try {
            if (apiKey.isBlank()) error(context.getString(R.string.provider_api_key_required))

            val (url, payload) = when (type) {
                ProviderType.ANTHROPIC -> {
                    val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, "v1/messages")
                    u to """{"model":${model.jsonStr()},"max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                }
                ProviderType.GEMINI -> {
                    // Interactions 的请求体是 `input`（step 序列或纯字符串），必须同时切到
                    // v1beta/interactions 端点；仍打到 generateContent 会被以缺 `contents` 拒绝。
                    // 图像模型（Nano Banana 系）只支持 Interactions，无论开关是否开启都强制切。
                    if (useResponseApi || model.endsWith("-image", ignoreCase = true)) {
                        val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, "v1beta/interactions")
                        u to """{"model":${model.jsonStr()},"input":"hi","store":false}"""
                    } else {
                        val u = if (useFullUrl) {
                            baseUrl
                        } else {
                            val path = if (baseUrl.trimEnd('/').endsWith(model)) {
                                baseUrl.trimEnd('/') + ":generateContent"
                            } else {
                                joinUrl(baseUrl, "v1beta/models/$model:generateContent")
                            }
                            path
                        }
                        u to """{"contents":[{"role":"user","parts":[{"text":"hi"}]}]}"""
                    }
                }
                else -> {
                    // Responses API 的请求体是 `input` 格式，必须同时切到 v1/responses 端点；
                    // 仍打到 chat/completions 会被服务端以「missing field `messages`」拒绝。
                    val path = if (useResponseApi) "v1/responses" else "v1/chat/completions"
                    val u = if (useFullUrl) baseUrl else joinUrl(baseUrl, path)
                    if (useResponseApi) {
                        u to """{"model":${model.jsonStr()},"input":[{"role":"user","content":"hi"}]}"""
                    } else {
                        u to """{"model":${model.jsonStr()},"max_completion_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
                    }
                }
            }

            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()

            val reqHeadersMap = request.headers.names().associateWith { name ->
                val raw = request.header(name).orEmpty()
                if (name.equals("Authorization", ignoreCase = true) || name.equals("x-api-key", ignoreCase = true)) {
                    if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"
                } else {
                    raw
                }
            }

            client.newCall(request).execute().use { response ->
                val latency = (System.nanoTime() - start) / 1_000_000
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }

                if (response.isSuccessful) {
                    ModelTestResult(
                        success = true,
                        latencyMs = latency,
                        message = context.getString(R.string.provider_test_success, latency),
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = payload,
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body
                    )
                } else {
                    ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = "HTTP ${response.code}: ${body.take(160)}",
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = payload,
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = body
                    )
                }
            }
        } catch (e: Exception) {
            val latency = (System.nanoTime() - start) / 1_000_000
            ModelTestResult(
                success = false,
                latencyMs = latency,
                message = e.message ?: context.getString(R.string.chat_error_title),
                errorDetail = e.stackTraceToString()
            )
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String, type: ProviderType): Request.Builder =
        when (type) {
            ProviderType.ANTHROPIC -> this
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
            ProviderType.GEMINI -> this
                .header("x-goog-api-key", apiKey)
            else -> this.header("Authorization", "Bearer $apiKey")
        }

    private fun Request.Builder.applyCustomHeaders(customHeaders: Map<String, String>): Request.Builder =
        this.apply { customHeaders.forEach { (name, value) -> if (name.isNotBlank()) header(name, value) } }

    /** 转成安全的 JSON 字符串字面量（含引号、正确转义）。 */
    private fun String.jsonStr(): String = JsonPrimitive(this).toString()
}
