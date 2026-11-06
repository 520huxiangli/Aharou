package com.aharou.feature.voice.tts

import com.aharou.core.net.AppProxy
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
 * 把本地录好的参考音频传到云端，换回一个可复用的音色 ID。
 *
 * 目前按硅基流动的 `/uploads/audio/voice` 实现：它是 OpenAI 兼容生态里少数开放了
 * 「上传参考音频换 voice id」的服务，拿到 uri 之后合成仍走原来那套 `/audio/speech`，
 * 不需要为合成再写一份私有协议。
 */
@Singleton
class CloudVoiceClient @Inject constructor() {

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 上传参考音频，返回云端音色 ID（形如 `speech:名字:xxx:xxx`）。
     *
     * @param baseUrl 服务商地址（如 `https://api.siliconflow.cn/v1`）
     * @param samples 16k 单声道浮点采样
     * @param text 参考音频对应的逐字文本——服务端靠它对齐音色，错字会直接影响相似度
     */
    suspend fun uploadVoice(
        baseUrl: String,
        apiKey: String,
        model: String,
        customName: String,
        text: String,
        samples: FloatArray,
        sampleRate: Int,
    ): String = withContext(Dispatchers.IO) {
        if (samples.isEmpty()) throw IOException("参考音频为空")
        if (text.isBlank()) throw IOException("参考文本为空")
        val url = uploadEndpoint(baseUrl)
        val wav = toPcm16Wav(samples, sampleRate)
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("customName", customName)
            .addFormDataPart("text", text)
            .addFormDataPart(
                "file",
                "reference.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(body)
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("上传参考音频失败 HTTP ${resp.code}：${raw.take(200)}")
            }
            val uri = runCatching { JSONObject(raw).optString("uri") }.getOrDefault("")
            if (uri.isBlank()) throw IOException("上传成功但没有返回音色 ID：${raw.take(200)}")
            uri
        }
    }

    /**
     * 拉取服务端已建好的自定义音色，供用户在 App 内直接选。
     *
     * 只能拿到 uri / 名字 / 当初那段参考文字，**音频本身不返回**，
     * 所以这些音色只能走云端合成，无法在本地 ZipVoice 里使用。
     */
    suspend fun listVoices(baseUrl: String, apiKey: String): List<CloudVoice> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(listEndpoint(baseUrl))
            .header("Authorization", "Bearer $apiKey")
            .get()
            .build()
        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("拉取云端音色失败 HTTP ${resp.code}：${raw.take(200)}")
            }
            // 实际返回的键是 `result`（单数），文档里写的是 `results`；两种都认，别为文档的错让人看不到列表。
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: return@use emptyList()
            val results = root.optJSONArray("result") ?: root.optJSONArray("results")
                ?: return@use emptyList()
            buildList {
                for (index in 0 until results.length()) {
                    val item = results.optJSONObject(index) ?: continue
                    val uri = item.optString("uri")
                    if (uri.isBlank()) continue
                    add(
                        CloudVoice(
                            uri = uri,
                            name = item.optString("customName").ifBlank { uri.takeLast(12) },
                            referenceText = item.optString("text"),
                            model = item.optString("model"),
                        )
                    )
                }
            }
        }
    }

    /** baseUrl 可能已经带 `/v1`，拼路径时不能再补一遍。 */
    private fun uploadEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.contains("/uploads/audio/voice")) return base
        return if (base.endsWith("/v1")) "$base/uploads/audio/voice"
        else "$base/v1/uploads/audio/voice"
    }

    private fun listEndpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        if (base.contains("/audio/voice/list")) return base
        return if (base.endsWith("/v1")) "$base/audio/voice/list"
        else "$base/v1/audio/voice/list"
    }
}

/** 云端已建好的自定义音色。 */
data class CloudVoice(
    val uri: String,
    val name: String,
    val referenceText: String,
    val model: String,
)
