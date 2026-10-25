package com.aharou.feature.settings.data.remote

import android.content.Context
import com.aharou.R
import com.aharou.feature.agent.domain.provider.joinUrl
import com.aharou.feature.settings.domain.model.ProviderType
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** 调试详情回显的请求体上限：够看清端点与参数形态，又不至于把整份上下文塞进界面。 */
private const val MAX_REQUEST_BODY_CHARS = 800

/** 网络错误重试前的退避时长（毫秒）。 */
private const val NETWORK_RETRY_DELAY_MS = 3_000L

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
            // 空 Key 也放行（免鉴权供应商）；真正需要 Key 的平台会由上游返回鉴权错误。

            val modelsPath = if (type == ProviderType.GEMINI) "v1beta/models" else "v1/models"
            val url = if (useFullUrl) baseUrl else joinUrl(baseUrl, modelsPath)
            failedUrl = url
            val request = Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .get()
                .build()

            val reqHeadersMap = maskedHeaders(request)
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

                // 有的网关用 HTTP 200 + 业务错误码回鉴权失败（如讯飞系返回 `data: {}`），
                // 直接 jsonArray 会抛序列化异常把真实原因盖掉，故先判定类型再取。
                val jsonObj = runCatching { json.parseToJsonElement(body) }.getOrNull() as? JsonObject
                val data = jsonObj?.let { obj ->
                    if (type == ProviderType.GEMINI) obj["models"] as? JsonArray else obj["data"] as? JsonArray
                } ?: run {
                    val serverError = jsonObj?.let { serverErrorMessage(it) }
                    val message = if (serverError != null) {
                        context.getString(R.string.provider_fetch_server_error, serverError)
                    } else {
                        context.getString(R.string.provider_fetch_missing_fields)
                    }
                    val errorDetail = if (serverError != null) {
                        context.getString(R.string.provider_fetch_server_error_detail, serverError, body)
                    } else {
                        context.getString(R.string.provider_fetch_missing_fields_detail, body)
                    }
                    val debug = ModelTestResult(
                        success = false,
                        latencyMs = latency,
                        message = message,
                        requestUrl = url,
                        requestHeaders = reqHeadersMap,
                        requestBody = "",
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = errorDetail
                    )
                    throw FetchModelsException(message, debug)
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
        val first = testModelOnce(start, baseUrl, apiKey, type, useFullUrl, useResponseApi, model, customHeaders)
        if (!first.retryable) return@withContext first.result
        // DNS 解析失败会被 libcore 缓存，紧接着重试必然重复失败；退避 3 秒再试一次（delay 不占线程）。
        delay(NETWORK_RETRY_DELAY_MS)
        testModelOnce(start, baseUrl, apiKey, type, useFullUrl, useResponseApi, model, customHeaders).result
    }

    /** 一次尝试；「是否值得重试」随结果带出，因为网络异常类型只在 catch 里看得到。 */
    private fun testModelOnce(
        start: Long,
        baseUrl: String,
        apiKey: String,
        type: ProviderType,
        useFullUrl: Boolean,
        useResponseApi: Boolean,
        model: String,
        customHeaders: Map<String, String>
    ): TestAttempt {
        // 空 Key 也放行（免鉴权供应商）；真正需要 Key 的平台会由上游返回鉴权错误。
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

        val request = runCatching {
            Request.Builder()
                .url(url)
                .applyAuth(apiKey, type)
                .applyCustomHeaders(customHeaders)
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
        }.getOrElse { e ->
            // 地址本身就非法（如少写协议头）：没有可发的请求，直接回失败结果。
            return TestAttempt(failure(e, url, emptyMap(), payload.take(MAX_REQUEST_BODY_CHARS), start))
        }

        // 失败时用户要能看清「发到哪、带了什么头、发了什么体」：不回填这三项就分不清
        // 是地址错、鉴权错还是网络错，故在请求构造完就固定下来。
        val requestUrl = request.url.toString()
        val requestHeaders = maskedHeaders(request)
        val requestBody = payload.take(MAX_REQUEST_BODY_CHARS)

        return try {
            client.newCall(request).execute().use { response ->
                val latency = elapsedMs(start)
                val body = response.body?.string().orEmpty()
                val respHeadersMap = response.headers.names().associateWith { response.header(it).orEmpty() }
                TestAttempt(
                    ModelTestResult(
                        success = response.isSuccessful,
                        latencyMs = latency,
                        message = if (response.isSuccessful) {
                            context.getString(R.string.provider_test_success, latency)
                        } else {
                            "HTTP ${response.code}: ${body.take(160)}"
                        },
                        requestUrl = requestUrl,
                        requestHeaders = requestHeaders,
                        requestBody = requestBody,
                        responseCode = response.code,
                        responseHeaders = respHeadersMap,
                        responseBody = body,
                        errorDetail = if (response.isSuccessful) "" else body
                    )
                )
            }
        } catch (e: Exception) {
            TestAttempt(
                failure(e, requestUrl, requestHeaders, requestBody, start),
                retryable = e.isRetryableNetworkError()
            )
        }
    }

    /** 失败结果：URL、打码后的请求头、截断的请求体必须带上，否则分不清错在哪一层。 */
    private fun failure(
        e: Throwable,
        requestUrl: String,
        requestHeaders: Map<String, String>,
        requestBody: String,
        start: Long
    ): ModelTestResult = ModelTestResult(
        success = false,
        latencyMs = elapsedMs(start),
        message = e.message ?: context.getString(R.string.chat_error_title),
        requestUrl = requestUrl,
        requestHeaders = requestHeaders,
        requestBody = requestBody,
        errorDetail = e.stackTraceToString()
    )

    /** 单次尝试的结果，附带是否值得退避重试。 */
    private data class TestAttempt(val result: ModelTestResult, val retryable: Boolean = false)

    private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

    /** DNS 解析失败 / 连接被拒 / 超时 才值得重试：换个解析结果或网络状态就可能成功。 */
    private fun Throwable.isRetryableNetworkError(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 4) {
            when (current) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException,
                is InterruptedIOException -> return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    /** 带凭据的请求头一律打码（保留前 7 位与后 4 位，便于核对是哪把 Key）：调试详情会展示给用户。 */
    private fun maskedHeaders(request: Request): Map<String, String> =
        request.headers.names().associateWith { name ->
            val raw = request.header(name).orEmpty()
            val header = name.lowercase()
            val sensitive = header == "authorization" || header.contains("key") ||
                header.contains("token") || header.contains("secret") || header.contains("cookie")
            if (sensitive) maskSecret(raw) else raw
        }

    private fun maskSecret(raw: String): String =
        if (raw.length > 12) raw.take(7) + "..." + raw.takeLast(4) else "***"

    /** 从错误响应里取出可读描述（如 `code` + `desc` / `message` / `error.message`）；取不到返回 null。 */
    private fun serverErrorMessage(obj: JsonObject): String? {
        fun text(element: JsonElement?): String? = (element as? JsonPrimitive)?.contentOrNull
        val error = obj["error"] as? JsonObject
        val desc = text(obj["desc"])
            ?: text(obj["message"])
            ?: text(obj["msg"])
            ?: text(error?.get("message"))
        val code = text(obj["code"]) ?: text(error?.get("code"))
        if (desc.isNullOrBlank() && code.isNullOrBlank()) return null
        return when {
            desc.isNullOrBlank() -> "code=$code"
            code.isNullOrBlank() -> desc
            else -> "$desc (code=$code)"
        }
    }

    private fun Request.Builder.applyAuth(apiKey: String, type: ProviderType): Request.Builder =
        if (apiKey.isBlank()) {
            // 免鉴权供应商（空 Key）：不带任何鉴权头，请求照常发出。
            this
        } else when (type) {
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
