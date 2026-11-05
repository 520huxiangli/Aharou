package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.openai.ChatCompletionRequest
import com.aharou.feature.agent.data.remote.openai.ChatCompletionResponse
import com.aharou.feature.agent.data.remote.openai.ImageGenerationRequest
import com.aharou.feature.agent.data.remote.openai.ImageGenerationResponse
import com.aharou.feature.agent.data.remote.openai.OpenAIApi
import com.aharou.feature.agent.domain.model.AgentMessage
import com.google.gson.JsonObject
import java.io.IOException
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Call

/**
 * OpenAI Responses 流式断流：官方不发 `[DONE]`，正常收尾只能靠 `response.completed`
 * （或 `response.incomplete`）这类语义终止事件。
 *
 * 本测试走 Responses 通道（`useResponseApi = true`）。Chat Completions 通道以 `[DONE]`
 * 收尾，判定条件不同，不在这里重复覆盖。
 */
class OpenAIResponsesStreamInterruptionTest {

    /** 只提供 Responses 流式响应；其余入口显式失败，确保测的是目标通道。 */
    private class FakeApi(private val stream: () -> Call<ResponseBody>) : OpenAIApi {

        override suspend fun createChatCompletion(
            url: String,
            authorization: String?,
            extraHeaders: Map<String, String>,
            request: ChatCompletionRequest
        ): ChatCompletionResponse = unsupportedNonStreaming()

        override fun streamChatCompletion(
            url: String,
            authorization: String?,
            extraHeaders: Map<String, String>,
            request: ChatCompletionRequest
        ): Call<ResponseBody> = unsupportedNonStreaming()

        override suspend fun createResponses(
            url: String,
            authorization: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject = unsupportedNonStreaming()

        override fun streamResponses(
            url: String,
            authorization: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): Call<ResponseBody> = stream()

        override suspend fun createImage(
            url: String,
            authorization: String?,
            extraHeaders: Map<String, String>,
            request: ImageGenerationRequest
        ): ImageGenerationResponse = unsupportedNonStreaming()
    }

    private fun adapterFor(body: () -> ResponseBody): OpenAIAdapter =
        OpenAIAdapter(FakeApi { FakeStreamCall(body) }).apply {
            apiKey = "k"
            useResponseApi = true
            // 断流属于可重试网络错误，默认 6 次重试会先退避；这里只验单次截断，故关掉重试。
            maxNetworkRetries = 0
        }

    private val messages = listOf(AgentMessage.UserMessage(content = "hi"))

    private suspend fun collectInto(
        sink: MutableList<AIStreamChunk>,
        body: () -> ResponseBody
    ): Throwable? = runCatching {
        adapterFor(body).completeStream("", messages).collect { sink += it }
    }.exceptionOrNull()

    private fun textOf(chunks: List<AIStreamChunk>): String =
        chunks.filterIsInstance<AIStreamChunk.TextDelta>().joinToString("") { it.text }

    private fun List<AIStreamChunk>.singleFinal(): AIStreamChunk.Final =
        filterIsInstance<AIStreamChunk.Final>().single()

    private fun sse(vararg args: String) = FakeSseStream.body(*args)

    private val created =
        """{"type":"response.created","response":{"id":"resp_1","status":"in_progress","output":[]}}"""

    private fun textDelta(text: String) =
        """{"type":"response.output_text.delta","item_id":"msg_1","output_index":0,"content_index":0,"delta":"$text"}"""

    private val completed =
        """{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":2}}}"""

    @Test
    fun response_completed_ends_the_stream_successfully() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) { sse(created, textDelta("你"), textDelta("好"), completed) }

        assertNull("带 response.completed 的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        val response = chunks.singleFinal().response
        assertEquals("你好", response.content)
        assertEquals("stop", response.stopReason)
    }

    @Test
    fun missing_response_completed_is_reported_as_interrupted() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // Responses 的循环条件是「未终止就继续读」，所以读到 EOF 必然意味着终止事件没来。
        val error = collectInto(chunks) { sse(created, textDelta("你"), textDelta("好")) }

        assertTrue("缺 response.completed 必须抛 IOException，实际：$error", error is IOException)
        assertTrue(
            "断流文案应由 adapter 统一给出，实际：${error?.message}",
            error!!.message.orEmpty().startsWith(FakeSseStream.INTERRUPTED_PREFIX)
        )
        // 不断言「已收到的增量被收集到」：流以异常结束时 channel 随之关闭/取消，
        // 已 send 但未被收集端取走的增量可能丢失，这是 flow 的语义，不是本通道可保证的契约。
        assertTrue(
            "断流的流不许产出 Final",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }

    @Test
    fun io_failure_midway_propagates_without_being_swallowed() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            FakeSseStream.interruptingBody(listOf(created, textDelta("你"), textDelta("好")))
        }

        assertTrue("底层读取失败必须以 IOException 形式冒给上层，实际：$error", error is IOException)
        assertEquals("模拟连接被重置", error!!.message)
        // 「读到一半」是假流保证的：先逐行投递 delta，全部投递完才在 read 里抛错。
        assertTrue(
            "读取失败的流同样不许产出 Final",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }
}
