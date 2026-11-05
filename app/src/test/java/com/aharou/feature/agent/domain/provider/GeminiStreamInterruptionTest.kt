package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.gemini.GeminiApi
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
 * Gemini generateContent 流式断流：正常收尾的唯一标记是 candidate 的 `finishReason`。
 *
 * 只覆盖 native generateContent（`streamGenerateContent?alt=sse`）这条通道；
 * Interactions 通道另有一套解析器（[GeminiInteractionsStreamAccumulator]），
 * 其流式入口依赖同样的 Call 但事件语义不同，不在本次断流复现范围内。
 */
class GeminiStreamInterruptionTest {

    /** 只提供流式响应；其余入口一律显式失败，防止测试误走非目标路径。 */
    private class FakeApi(private val stream: () -> Call<ResponseBody>) : GeminiApi {

        override suspend fun generateContent(
            url: String,
            apiKey: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject = unsupportedNonStreaming()

        override fun streamGenerateContent(
            url: String,
            apiKey: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): Call<ResponseBody> = stream()

        override suspend fun createInteraction(
            url: String,
            apiKey: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): JsonObject = unsupportedNonStreaming()

        override fun streamInteraction(
            url: String,
            apiKey: String?,
            extraHeaders: Map<String, String>,
            request: Any
        ): Call<ResponseBody> = unsupportedNonStreaming()
    }

    private fun adapterFor(body: () -> ResponseBody): GeminiAdapter =
        GeminiAdapter(FakeApi { FakeStreamCall(body) }).apply {
            apiKey = "k"
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

    private fun textChunk(text: String) =
        """{"candidates":[{"content":{"parts":[{"text":"$text"}],"role":"model"}}]}"""

    private val finishChunk =
        """{"candidates":[{"content":{"parts":[{"text":""}],"role":"model"},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":2}}"""

    @Test
    fun finish_reason_present_completes_with_final() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) { sse(textChunk("你"), textChunk("好"), finishChunk) }

        assertNull("带 finishReason 的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        val response = chunks.singleFinal().response
        assertEquals("你好", response.content)
        assertEquals("STOP", response.stopReason)
    }

    @Test
    fun missing_finish_reason_is_reported_as_interrupted() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // 只给文本、不给 finishReason：Gemini 的循环只在拿到 finishReason 后才 break，
        // 所以走到 EOF 就必然意味着「终止标记没来」。
        val error = collectInto(chunks) { sse(textChunk("你"), textChunk("好")) }

        assertTrue("缺 finishReason 必须抛 IOException，实际：$error", error is IOException)
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
            FakeSseStream.interruptingBody(listOf(textChunk("你"), textChunk("好")))
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
