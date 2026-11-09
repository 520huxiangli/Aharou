package com.aharou.feature.agent.domain.provider

import com.aharou.feature.agent.data.remote.anthropic.AnthropicApi
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessageRequest
import com.aharou.feature.agent.data.remote.anthropic.AnthropicMessageResponse
import com.aharou.feature.agent.domain.model.AgentMessage
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
 * Anthropic 流式断流：`message_stop` 是唯一的正常收尾标记。
 *
 * 只验证 adapter 的解析与异常语义。「断流后已累积文本是否作为快照落库」在
 * `StatefulAgentWorkflow` 的 finally 里，不属于本通道的职责，故这里不覆盖。
 */
class AnthropicStreamInterruptionTest {

    /** 只提供流式响应；非流式入口一旦被调到就立刻失败，避免测试走错路径还显示通过。 */
    private class FakeApi(private val stream: () -> Call<ResponseBody>) : AnthropicApi {

        override suspend fun createMessage(
            url: String,
            apiKey: String?,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): AnthropicMessageResponse = unsupportedNonStreaming()

        override fun streamMessage(
            url: String,
            apiKey: String?,
            version: String,
            extraHeaders: Map<String, String>,
            request: AnthropicMessageRequest
        ): Call<ResponseBody> = stream()
    }

    private fun adapterFor(body: () -> ResponseBody): AnthropicAdapter =
        AnthropicAdapter(FakeApi { FakeStreamCall(body) }).apply {
            apiKey = "k"
            // 断流是「可重试的网络错误」，默认 6 次重试会先指数退避再抛；
            // 这里要验的是单次流被截断时的行为，故关掉重试让异常立刻冒出来。
            maxNetworkRetries = 0
        }

    private val messages = listOf(AgentMessage.UserMessage(content = "hi"))

    /** 收 chunk 并返回收集到的异常：正常完成时为 null，断流时为对应 Throwable。 */
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

    private val messageStart =
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-sonnet-20241022","content":[],"stop_reason":null,"stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":1}}}"""

    private val blockStart = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""

    private val blockStop = """{"type":"content_block_stop","index":0}"""

    private val messageDelta =
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":2}}"""

    private val messageStop = """{"type":"message_stop"}"""

    private fun textDelta(text: String) =
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"$text"}}"""

    private fun fullStreamBody() = sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop, messageDelta, messageStop)

    @Test
    fun message_stop_present_completes_with_final() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) { fullStreamBody() }

        assertNull("收到 message_stop 的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        val response = chunks.singleFinal().response
        assertEquals("你好", response.content)
        assertEquals("end_turn", response.stopReason)
    }

    @Test
    fun missing_message_stop_is_reported_as_interrupted() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // 故意去掉 message_stop 与 message_delta：连接正常关闭但没有任何终止信号，
        // 等价于线上「AI 说到一半突然没了」。
        val error = collectInto(chunks) {
            sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop)
        }

        assertTrue("缺终止事件必须抛 IOException，实际：$error", error is IOException)
        assertTrue(
            "断流文案应由 adapter 统一给出，实际：${error?.message}",
            error!!.message.orEmpty().startsWith(FakeSseStream.INTERRUPTED_PREFIX)
        )
        // 不断言「已收到的增量被收集到」：流以异常结束时 channel 随之关闭/取消，
        // 已 send 但尚未被收集端取走的增量可能丢失，这是 flow 的语义，不是本通道可保证的契约。
        // 「在收到部分 delta 之后才断」由假流本身保证：数据行全部投递完毕后才轮到 EOF。
        assertTrue(
            "断流的流不许产出 Final，否则上层会把残缺内容当完整回复",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }

    @Test
    fun stop_reason_without_message_stop_completes() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        // 个别中转在 message_delta 给出 stop_reason 后直接关流。此时本轮生成已完整，
        // 按正常结束收尾，不能判成断流去整轮重试。
        val error = collectInto(chunks) {
            sse(messageStart, blockStart, textDelta("你"), textDelta("好"), blockStop, messageDelta)
        }

        assertNull("stop_reason 已到的流不该报错，实际：$error", error)
        assertEquals("你好", textOf(chunks))
        assertEquals("end_turn", chunks.singleFinal().response.stopReason)
    }

    @Test
    fun io_failure_midway_propagates_without_being_swallowed() = runTest {
        val chunks = mutableListOf<AIStreamChunk>()
        val error = collectInto(chunks) {
            FakeSseStream.interruptingBody(listOf(messageStart, blockStart, textDelta("你"), textDelta("好")))
        }

        assertTrue("底层读取失败必须以 IOException 形式冒给上层，实际：$error", error is IOException)
        // 只在 EOF 时由 adapter 自己造统一文案；底层异常必须保留原始 message，
        // 否则用户与日志都看不到「连接被重置」这类真实原因。
        assertEquals("模拟连接被重置", error!!.message)
        // 「读到一半」是假流保证的：先逐行投递 delta，全部投递完才在 read 里抛错。
        // 不在此断言收集到的增量——流异常结束时缓存中的增量可能尚未被收集端取走。
        assertTrue(
            "读取失败的流同样不许产出 Final",
            chunks.filterIsInstance<AIStreamChunk.Final>().isEmpty()
        )
    }
}
