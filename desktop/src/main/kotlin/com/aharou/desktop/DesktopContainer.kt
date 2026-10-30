package com.aharou.desktop

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.gemini.GeminiApi
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.AnthropicAdapter
import com.aharou.feature.agent.domain.provider.GeminiAdapter
import com.aharou.feature.agent.domain.provider.OpenAIAdapter
import com.aharou.feature.settings.domain.model.ProviderType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * 桌面端的手工装配。手机端这套走 Hilt，桌面没有 Android 组件，直接在这里 new。
 *
 * 超时口径与手机端 [AgentModule] 保持一致：流式请求的读超时是「相邻数据块之间」的等待上限，
 * 设 0 不限；首字节卡死由调用方自己兜底。
 */
object DesktopContainer {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private fun retrofit(baseUrl: String): Retrofit = Retrofit.Builder()
        .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
        .client(httpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    /** 按当前配置造一个可用的 provider；每次发消息都重新构造，配置改了立即生效。 */
    fun provider(settings: DesktopSettings): AIProvider {
        val retrofit = retrofit(settings.baseUrl)
        val provider: AIProvider = when (settings.providerType) {
            ProviderType.ANTHROPIC -> AnthropicAdapter(retrofit.create(AnthropicApi::class.java))
            ProviderType.GEMINI -> GeminiAdapter(retrofit.create(GeminiApi::class.java))
            ProviderType.OPENAI -> OpenAIAdapter(retrofit.create(OpenAIApi::class.java))
        }
        return provider.apply {
            apiKey = settings.apiKey
            baseUrl = settings.baseUrl
            model = settings.model
            useFullUrl = false
            useResponseApi = false
            providerId = "desktop"
            logSessionId = "desktop"
            customHeaders = emptyMap()
            keySwitcher = null
            maxOutputTokens = null
            temperature = null
            firstByteTimeoutMs = 300_000
            streamIdleTimeoutMs = 0
            maxNetworkRetries = 2
        }
    }
}
