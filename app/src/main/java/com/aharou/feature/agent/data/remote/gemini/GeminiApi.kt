package com.aharou.feature.agent.data.remote.gemini

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GeminiApi {
    @POST
    suspend fun generateContent(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    @Streaming
    @POST
    suspend fun streamGenerateContent(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): ResponseBody

    /** Interactions API（`v1beta/interactions`）的非流式请求。 */
    @POST
    suspend fun createInteraction(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    /**
     * Interactions API 的流式请求：与非流式**同一个端点**，靠请求体的 `stream: true`
     * 与 `?alt=sse` 切到 SSE，不再有 generateContent 那种独立的 `:streamGenerateContent`。
     */
    @Streaming
    @POST
    suspend fun streamInteraction(
        @Url url: String,
        @Header("x-goog-api-key") apiKey: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): ResponseBody
}
