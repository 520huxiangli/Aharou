package com.aharou.feature.plugin.runtime

import com.aharou.feature.plugin.domain.PluginHostException
import com.aharou.feature.plugin.domain.PluginHostPaths
import com.aharou.feature.plugin.domain.PluginManager
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** multipart 里的一个普通字段。 */
internal data class PluginMultipartField(val name: String, val value: String)

/** multipart 里的一个文件；[bytes] 已是解码后的原始字节。 */
internal data class PluginMultipartFile(
    val name: String,
    val fileName: String,
    val mimeType: String?,
    val bytes: ByteArray
)

/** 校验后的 multipart 描述；只为 [PluginHttpService] 使用。 */
internal data class PluginMultipart(
    val fields: List<PluginMultipartField>,
    val files: List<PluginMultipartFile>
)

/** 校验后的请求描述；只为 [PluginHttpService] 与单测使用。 */
internal data class PluginHttpRequest(
    val url: HttpUrl,
    val method: String,
    val headers: Map<String, String>,
    val body: String?,
    val timeoutSeconds: Long,
    val bodyBytes: ByteArray? = null,
    val multipart: PluginMultipart? = null,
    val followRedirects: Boolean = false,
    val maxBytes: Long = DEFAULT_MAX_RESPONSE_BYTES,
    val ignoreSsl: Boolean = false,
    val responseBodyBase64: Boolean = false
)

/** 响应体默认上限；扩展后仍是 1 MiB，只有显式传 maxBytes 才会变。 */
private const val DEFAULT_MAX_RESPONSE_BYTES = 1024L * 1024

/**
 * 插件的通用 HTTP 请求能力：只允许 http/https、默认不跟随重定向、不注入任何宿主凭据。
 *
 * 每次调用都用注入的 [OkHttpClient] 克隆一个独立 client（按本次参数决定是否跟随重定向、套用超时），
 * 请求在 IO 线程执行。有 body 且未显式设置 Content-Type 时按 `application/json` 发送；
 * 响应体默认最多读 1 MiB，超出截断并置 `truncated`。参数问题抛 `PARAM_MISSING` /
 * `PARAM_INVALID`，网络失败统一抛 `HTTP_FAILED`。
 *
 * 扩展参数全部可选，默认值与扩展前完全一致（不跟随重定向、1 MiB、文本收发）：
 * `followRedirects` / `maxBytes` / `bodyBase64` / `responseBodyBase64` / `multipart` / `ignoreSsl`。
 * 其中 `ignoreSsl` 只认显式 true——它只为连自签证书的局域网服务存在，不能因为解析歧义被打开。
 */
@Singleton
class PluginHttpService @Inject constructor(
    private val pluginManager: PluginManager,
    private val client: OkHttpClient
) {

    private val plainClient: OkHttpClient = OkHttpClient.Builder()
        // 复用连接池与调度器，但不复用 provider 拦截器：插件 HTTP 不应触发 agent.http.before，
        // 否则插件自己的 hook 调 host.http.request 会递归。
        .dispatcher(client.dispatcher)
        .connectionPool(client.connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun request(pluginId: String, params: JsonObject): JsonElement {
        ensureActive(pluginId)
        val request = parseRequest(params)
        return withContext(Dispatchers.IO) { execute(request) }
    }

    /** 校验并规范化参数；与网络执行分离，便于单测覆盖各字段边界。 */
    internal fun parseRequest(params: JsonObject): PluginHttpRequest {
        val rawUrl = PluginHostPaths.requireString(params, "url").trim()
        if (rawUrl.length > MAX_URL_CHARS) throw paramInvalid("url too long")
        if (!rawUrl.startsWith("http://", ignoreCase = true) &&
            !rawUrl.startsWith("https://", ignoreCase = true)
        ) {
            throw paramInvalid("url must use http or https")
        }
        val url = rawUrl.toHttpUrlOrNull() ?: throw paramInvalid("url is malformed")

        val method = (optionalString(params, "method")?.trim()?.uppercase(Locale.ROOT)) ?: "GET"
        if (method !in METHODS) throw paramInvalid("unsupported method: $method")

        val body = optionalString(params, "body")
        val bodyBase64 = optionalString(params, "bodyBase64")
        val multipart = parseMultipart(params["multipart"])
        val bodySources = listOfNotNull(body?.let { "body" }, bodyBase64?.let { "bodyBase64" }, multipart?.let { "multipart" })
        if (bodySources.size > 1) {
            throw paramInvalid("only one of body / bodyBase64 / multipart is allowed")
        }
        if (body != null) {
            if (method == "GET" || method == "HEAD") throw paramInvalid("$method does not accept a body")
            if (body.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw paramInvalid("body too large")
        }
        val bodyBytes = bodyBase64?.let { encoded ->
            if (method == "GET" || method == "HEAD") throw paramInvalid("$method does not accept a body")
            decodeBase64(encoded, "bodyBase64")
        }
        if (multipart != null) {
            if (method == "GET" || method == "HEAD") throw paramInvalid("$method does not accept a body")
            val total = multipart.fields.sumOf { it.value.toByteArray(Charsets.UTF_8).size } +
                multipart.files.sumOf { it.bytes.size }
            if (total > MAX_MULTIPART_BYTES) throw paramInvalid("multipart body too large")
        }

        val headers = when (val rawHeaders = params["headers"]) {
            null, JsonNull -> emptyMap<String, String>()
            is JsonObject -> parseHeaders(rawHeaders)
            else -> throw paramInvalid("headers must be an object")
        }

        val timeoutSeconds = when (val rawTimeout = params["timeout"]) {
            null, JsonNull -> DEFAULT_TIMEOUT_SECONDS
            is JsonPrimitive -> rawTimeout.longOrNull
                ?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS }
                ?: throw paramInvalid("timeout must be $MIN_TIMEOUT_SECONDS..$MAX_TIMEOUT_SECONDS seconds")
            else -> throw paramInvalid("timeout must be a number")
        }

        val followRedirects = optionalBoolean(params, "followRedirects") ?: false
        val ignoreSsl = optionalBoolean(params, "ignoreSsl") ?: false
        val responseBodyBase64 = optionalBoolean(params, "responseBodyBase64") ?: false
        val maxBytes = when (val rawMax = params["maxBytes"]) {
            null, JsonNull -> DEFAULT_MAX_BYTES
            is JsonPrimitive -> rawMax.longOrNull
                ?.takeIf { it in MIN_MAX_BYTES..MAX_MAX_BYTES }
                ?: throw paramInvalid("maxBytes must be $MIN_MAX_BYTES..$MAX_MAX_BYTES")
            else -> throw paramInvalid("maxBytes must be a number")
        }

        return PluginHttpRequest(
            url = url,
            method = method,
            headers = headers,
            body = body,
            timeoutSeconds = timeoutSeconds,
            bodyBytes = bodyBytes,
            multipart = multipart,
            followRedirects = followRedirects,
            maxBytes = maxBytes,
            ignoreSsl = ignoreSsl,
            responseBodyBase64 = responseBodyBase64
        )
    }

    /**
     * 把状态码、响应头与已读取的字节装成 JSON；超过上限的响应体按字节截断。
     * [includeBase64] 为 true 时额外给出 `bodyBase64`，二进制响应不必经过 UTF-8 文本这一层。
     */
    internal fun buildResponse(
        status: Int,
        headers: Map<String, String>,
        body: ByteArray,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        includeBase64: Boolean = false
    ): JsonObject {
        val truncated = body.size > maxBytes
        val bytes = if (truncated) body.copyOf(maxBytes.toInt()) else body
        return buildJsonObject {
            put("status", status)
            put("headers", buildJsonObject { headers.forEach { (name, value) -> put(name, value) } })
            put("body", String(bytes, Charsets.UTF_8))
            if (includeBase64) {
                put("bodyBase64", Base64.getEncoder().encodeToString(bytes))
            }
            put("truncated", truncated)
        }
    }

    private fun execute(request: PluginHttpRequest): JsonElement {
        val builder = plainClient.newBuilder()
            .followRedirects(request.followRedirects)
            .followSslRedirects(request.followRedirects)
            .callTimeout(request.timeoutSeconds, TimeUnit.SECONDS)
        if (request.ignoreSsl) {
            val (factory, trustManager) = insecureTls()
            builder.sslSocketFactory(factory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }
        val httpClient = builder.build()

        val requestBuilder = Request.Builder().url(request.url)
        val headers = request.headers.filterKeys { header ->
            // multipart 的 Content-Type 带 boundary，由 OkHttp 自己写；用户传的会把它顶掉。
            !(request.multipart != null && header.equals("Content-Type", ignoreCase = true))
        }
        if (headers.isNotEmpty()) {
            val headerBuilder = Headers.Builder()
            headers.forEach { (name, value) -> headerBuilder.add(name, value) }
            requestBuilder.headers(headerBuilder.build())
        }
        requestBuilder.method(request.method, requestBody(request))
        return try {
            httpClient.newCall(requestBuilder.build()).execute().use { response ->
                val responseHeaders = response.headers.toMultimap()
                    .mapValues { (_, values) -> values.joinToString(", ") }
                buildResponse(
                    status = response.code,
                    headers = responseHeaders,
                    body = response.peekBody(request.maxBytes + 1L).bytes(),
                    maxBytes = request.maxBytes,
                    includeBase64 = request.responseBodyBase64
                )
            }
        } catch (e: IOException) {
            throw PluginHostException("HTTP_FAILED", e.message ?: e.javaClass.simpleName)
        }
    }

    private fun requestBody(request: PluginHttpRequest): RequestBody? {
        request.multipart?.let { multipart ->
            val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            multipart.fields.forEach { field -> builder.addFormDataPart(field.name, field.value) }
            multipart.files.forEach { file ->
                val media = (file.mimeType ?: DEFAULT_BINARY_MIME).toMediaType()
                builder.addFormDataPart(file.name, file.fileName, file.bytes.toRequestBody(media))
            }
            return builder.build()
        }
        request.bodyBytes?.let { bytes ->
            val hasContentType = request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }
            return bytes.toRequestBody(if (hasContentType) null else BINARY_MEDIA_TYPE)
        }
        val text = request.body
        if (text == null) {
            // OkHttp 要求 POST/PUT/PATCH 必须带请求体；无 body 时发空体，避免直接抛异常。
            return if (request.method in BODY_REQUIRED_METHODS) EMPTY_BODY else null
        }
        val hasContentType = request.headers.keys.any { it.equals("Content-Type", ignoreCase = true) }
        return text.toRequestBody(if (hasContentType) null else JSON_MEDIA_TYPE)
    }

    private fun parseMultipart(raw: JsonElement?): PluginMultipart? {
        if (raw == null || raw is JsonNull) return null
        val obj = raw as? JsonObject ?: throw paramInvalid("multipart must be an object")
        val fields = mutableListOf<PluginMultipartField>()
        (obj["fields"] as? JsonObject)?.forEach { (name, value) ->
            if (!FIELD_NAME.matches(name)) throw paramInvalid("invalid multipart field name: $name")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw paramInvalid("multipart field value must be a string: $name")
            if (text.length > MAX_FIELD_CHARS) throw paramInvalid("multipart field too large: $name")
            fields += PluginMultipartField(name, text)
        }
        val files = mutableListOf<PluginMultipartFile>()
        when (val rawFiles = obj["files"]) {
            null, JsonNull -> Unit
            is JsonArray -> {
                if (rawFiles.size > MAX_MULTIPART_FILES) throw paramInvalid("too many multipart files")
                rawFiles.forEach { item ->
                    val file = item as? JsonObject ?: throw paramInvalid("multipart file must be an object")
                    val name = (file["name"] as? JsonPrimitive)?.contentOrNull
                        ?: throw paramInvalid("multipart file needs name")
                    if (!FIELD_NAME.matches(name)) throw paramInvalid("invalid multipart file name: $name")
                    val fileName = (file["fileName"] as? JsonPrimitive)?.contentOrNull
                        ?: (file["filename"] as? JsonPrimitive)?.contentOrNull
                        ?: name
                    if (fileName.any { it.isISOControl() } || fileName.length > MAX_FILENAME_CHARS) {
                        throw paramInvalid("invalid multipart fileName: $fileName")
                    }
                    val base64 = (file["base64"] as? JsonPrimitive)?.contentOrNull
                        ?: throw paramInvalid("multipart file needs base64: $name")
                    val mimeType = (file["mimeType"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                    files += PluginMultipartFile(name, fileName, mimeType, decodeBase64(base64, "multipart file $name"))
                }
            }
            else -> throw paramInvalid("multipart files must be an array")
        }
        if (fields.isEmpty() && files.isEmpty()) throw paramInvalid("multipart needs fields or files")
        return PluginMultipart(fields, files)
    }

    private fun decodeBase64(encoded: String, label: String): ByteArray {
        val bytes = try {
            Base64.getDecoder().decode(encoded.trim())
        } catch (e: IllegalArgumentException) {
            throw paramInvalid("invalid base64 in $label")
        }
        if (bytes.size > MAX_MULTIPART_BYTES) throw paramInvalid("$label too large")
        return bytes
    }

    /** 只给显式 `ignoreSsl=true` 用：自签证书的局域网服务靠它才连得上。 */
    private fun insecureTls(): Pair<SSLSocketFactory, X509TrustManager> {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return context.socketFactory to trustManager
    }

    private fun parseHeaders(raw: JsonObject): Map<String, String> {
        if (raw.size > MAX_HEADERS) throw paramInvalid("too many headers")
        val headers = LinkedHashMap<String, String>()
        raw.forEach { (name, value) ->
            if (!HEADER_NAME.matches(name)) throw paramInvalid("invalid header name: $name")
            if (name.lowercase(Locale.ROOT) in FORBIDDEN_HEADERS) throw paramInvalid("forbidden header: $name")
            val text = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                ?: throw paramInvalid("header value must be a string: $name")
            if (text.length > MAX_HEADER_VALUE_CHARS) throw paramInvalid("header value too long: $name")
            // 与 OkHttp 可写字符集对齐：只允许可见 ASCII 与制表符，避免换行注入或运行期抛错。
            if (!text.all { it == '\t' || it in ' '..'~' }) throw paramInvalid("invalid header value: $name")
            headers[name] = text
        }
        return headers
    }

    private fun optionalString(params: JsonObject, key: String): String? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw paramInvalid("$key must be a string")
    }

    private fun optionalBoolean(params: JsonObject, key: String): Boolean? {
        val raw = params[key] ?: return null
        return (raw as? JsonPrimitive)?.booleanOrNull ?: throw paramInvalid("$key must be a boolean")
    }

    private fun ensureActive(pluginId: String) {
        pluginManager.ensureLoaded()
        pluginManager.plugins.value.firstOrNull { it.id == pluginId && it.isActive }
            ?: throw PluginHostException("PLUGIN_INACTIVE", "plugin is not active: $pluginId")
    }

    private fun paramInvalid(message: String): PluginHostException =
        PluginHostException("PARAM_INVALID", message)

    private companion object {
        const val MAX_URL_CHARS = 4_096
        const val MAX_HEADERS = 64
        const val MAX_HEADER_VALUE_CHARS = 8_192
        const val MAX_BODY_BYTES = 1024 * 1024
        const val MAX_MULTIPART_BYTES = 8 * 1024 * 1024
        const val MAX_MULTIPART_FILES = 20
        const val MAX_FIELD_CHARS = 8_192
        const val MAX_FILENAME_CHARS = 255

        /** 响应体默认上限；扩展后仍是 1 MiB，只有显式传 maxBytes 才会变。 */
        const val DEFAULT_MAX_BYTES = DEFAULT_MAX_RESPONSE_BYTES
        const val MIN_MAX_BYTES = 1L
        const val MAX_MAX_BYTES = 32L * 1024 * 1024
        const val DEFAULT_TIMEOUT_SECONDS = 30L
        const val MIN_TIMEOUT_SECONDS = 1L
        const val MAX_TIMEOUT_SECONDS = 60L
        val HEADER_NAME = Regex("^[A-Za-z0-9-]{1,64}$")
        val FIELD_NAME = Regex("^[A-Za-z0-9_.-]{1,64}$")
        val FORBIDDEN_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "expect", "upgrade")
        val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
        val BODY_REQUIRED_METHODS = setOf("POST", "PUT", "PATCH")
        val EMPTY_BODY: RequestBody = "".toRequestBody()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val DEFAULT_BINARY_MIME = "application/octet-stream"
    }
}
