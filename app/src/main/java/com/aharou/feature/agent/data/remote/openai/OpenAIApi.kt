package com.aharou.feature.agent.data.remote.openai

import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Streaming
import retrofit2.http.Url

interface OpenAIApi {
    @POST
    suspend fun createChatCompletion(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ChatCompletionRequest
    ): ChatCompletionResponse

    /** 流式（SSE）补全：返回原始响应体，由调用方逐行解析 event-stream。请求需带 stream=true。 */
    @Streaming
    @POST
    suspend fun streamChatCompletion(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ChatCompletionRequest
    ): ResponseBody
    
    @POST
    suspend fun createResponses(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): com.google.gson.JsonObject

    @Streaming
    @POST
    suspend fun streamResponses(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: Any
    ): ResponseBody

    /** Images API：生成图片（POST /v1/images/generations）。 */
    @POST
    suspend fun createImage(
        @Url url: String,
        @retrofit2.http.Header("Authorization") authorization: String,
        @HeaderMap extraHeaders: Map<String, String> = emptyMap(),
        @Body request: ImageGenerationRequest
    ): ImageGenerationResponse
}
