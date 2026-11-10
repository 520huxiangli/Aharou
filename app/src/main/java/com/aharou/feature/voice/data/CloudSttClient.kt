package com.aharou.feature.voice.data

import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.aharou.feature.settings.data.repository.VoiceSttSettingsRepository
import com.aharou.feature.settings.domain.repository.AIProviderRepository
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 云端语音识别：走 OpenAI 兼容的 `/audio/transcriptions`。
 *
 * 设置里填了「语音识别模型」才走这里；没填就返回 null，调用方继续用本机模型。
 * 只做整段识别——流式的实时字幕仍由端上识别器给，不然每说几个字就要发一次请求。
 */
@Singleton
internal class CloudSttClient @Inject constructor(
    private val sttSettings: VoiceSttSettingsRepository,
    private val providerRepository: AIProviderRepository,
) {

    private val client by lazy {
        OkHttpClient.Builder()
            .proxyAuthenticator(AppProxy.okHttpAuthenticator)
            .connectTimeout(20, TimeUnit.SECONDS)
            // 整段音频上传 + 服务端推理，留够时间；调用方通常还等着它回结果
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    /** 用户配了云端识别模型没有。没配时调用方连试都不用试。 */
    suspend fun configured(): Boolean = runCatching {
        sttSettings.getSttProviderId().isNotBlank() && sttSettings.getSttModel().isNotBlank()
    }.getOrDefault(false)

    /**
     * 整段录音转文字；没配置、没 Key、请求失败都返回 null，由调用方回退本机识别。
     *
     * @param samples 16 kHz 单声道、[-1, 1] 的样本
     */
    suspend fun transcribe(samples: FloatArray): String? {
        if (samples.isEmpty()) return null
        val providerId = sttSettings.getSttProviderId()
        val model = sttSettings.getSttModel()
        if (providerId.isBlank() || model.isBlank()) return null
        val provider = runCatching { providerRepository.getProviderById(providerId) }.getOrNull() ?: return null
        val key = provider.firstUsableApiKey
        if (key.isBlank()) return null
        val wav = Wav.file(samples)
        return withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("file", "speech.wav", wav.toRequestBody(WAV))
                .build()
            val request = Request.Builder()
                .url(endpoint(provider.baseUrl))
                .header("Authorization", "Bearer $key")
                .post(body)
                .build()
            runCatching {
                client.newCall(request).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw IOException("识别失败 HTTP ${resp.code}：${text.take(200)}")
                    }
                    JSONObject(text).optString("text").trim().ifBlank { null }
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                FileLogger.w(TAG, "云端识别失败，回退本机", e)
            }.getOrNull()
        }
    }

    /**
     * 拼接端点：baseUrl 可能已含 `/v1`，也可能是填到 `/audio/` 的完整路径。
     */
    private fun endpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/audio/transcriptions")) return base
        if (base.contains("/audio/")) return base
        if (!base.endsWith("/v1")) base += "/v1"
        return "$base/audio/transcriptions"
    }

    private companion object {
        const val TAG = "CloudSttClient"
        val WAV = "audio/wav".toMediaType()
    }
}
