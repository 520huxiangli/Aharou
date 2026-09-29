package com.aharou.feature.voice.data

import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 云端语音服务客户端：走 OpenAI 兼容的 `/audio/transcriptions`（识别）与 `/audio/speech`（合成）。
 *
 * 之所以只做这一套协议：它是事实标准，OpenAI、Groq、硅基流动、阿里百炼、各类中转站都兼容或近似兼容，
 * 用户填 baseUrl + key + 模型名即可，无需为每家写一套签名适配（讯飞/百度那种私有鉴权不在范围内）。
 */
@Singleton
internal class CloudSpeechClient @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS)
            // 识别要等整段音频上传 + 服务端推理，合成要等生成整段语音，都放宽到 120s
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 云端语音识别。
     *
     * @param baseUrl 服务商地址，可带或不带 `/v1`（自动补全）
     * @return 识别文本
     * @throws IOException 网络或服务端错误
     */
    suspend fun transcribe(
        baseUrl: String,
        apiKey: String,
        model: String,
        samples: FloatArray,
        sampleRate: Int,
        language: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val wav = WavEncoder.encode(samples, sampleRate)
        val builder = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart(
                "file", "audio.wav",
                wav.toRequestBody("audio/wav".toMediaType())
            )
            .addFormDataPart("model", model)
            .addFormDataPart("response_format", "json")
        language?.takeIf { it.isNotBlank() }?.let { builder.addFormDataPart("language", it) }

        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/transcriptions"))
            .header("Authorization", "Bearer $apiKey")
            .post(builder.build())
            .build()

        client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("转录失败 HTTP ${resp.code}：${body.take(200)}")
            }
            // 标准返回 {"text": "..."}；个别服务包一层 {"data":{"text":...}} 或直接给纯文本
            runCatching { JSONObject(body).optString("text") }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: runCatching { JSONObject(body).optJSONObject("data")?.optString("text") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                ?: body.trim().takeIf { !it.startsWith("{") }.orEmpty()
        }
    }

    /**
     * 云端语音合成，返回音频字节（MP3）。
     *
     * @param voice 音色名，留空则用服务端默认
     */
    suspend fun synthesize(
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        voice: String,
        responseFormat: String = "mp3",
    ): ByteArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", model)
            put("input", text)
            put("response_format", responseFormat)
            if (voice.isNotBlank()) put("voice", voice)
        }
        val request = Request.Builder()
            .url(endpoint(baseUrl, "audio/speech"))
            .header("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            if (!resp.isSuccessful) {
                // 失败时响应体是 JSON 错误信息，转成文本便于定位
                throw IOException("合成失败 HTTP ${resp.code}：${String(bytes).take(200)}")
            }
            if (bytes.isEmpty()) throw IOException("合成返回空音频")
            // 服务商故障时可能返回 JSON 错误页而不是音频，按内容类型挡一下
            val contentType = resp.header("Content-Type").orEmpty()
            if (contentType.startsWith("application/json") || contentType.startsWith("text/")) {
                throw IOException("合成返回的不是音频（$contentType）：${String(bytes).take(200)}")
            }
            bytes
        }
    }

    /**
     * 拼接端点：baseUrl 可能已含 `/v1`（多数服务商）也可能不含。
     * 含 `audio/` 时视为用户已填完整路径，直接用。
     */
    private fun endpoint(baseUrl: String, path: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/$path")) return base
        if (base.contains("/audio/")) return base
        if (!base.endsWith("/v1")) base += "/v1"
        return "$base/$path"
    }

    private companion object {
        const val TAG = "CloudSpeechClient"
    }
}
