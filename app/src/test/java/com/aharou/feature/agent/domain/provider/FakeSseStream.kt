package com.aharou.feature.agent.domain.provider

import java.io.IOException
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response

/**
 * 流式 SSE 断流的可复用测试基建：把 `data: ...` 行变成 [ResponseBody]，全程不碰网络。
 *
 * 线上流被截断有三种形态，三种都要能复现：
 * 1. 正常收尾——收到本轮终止事件（message_stop / response.completed / finishReason）；
 * 2. 半截 EOF——连接被正常关闭，但终止事件从未到达，落在 adapter 的 `readLine() == null` 分支；
 * 3. 半截 IOException——读到一半底层抛错（TCP 重置 / readTimeout），异常从 `readLine()` 直接冒出。
 *
 * 2 与 3 必须分开构造：前者由 adapter 自己翻译成「SSE 流被中断」，后者必须原样上抛。
 */
internal object FakeSseStream {

    /** 各 adapter 断流文案的公共前缀，测试用它确认「走的是中断分支」而不是别的失败。 */
    const val INTERRUPTED_PREFIX = "SSE 流被中断"

    private val SSE_MEDIA_TYPE = "text/event-stream; charset=utf-8".toMediaType()

    /**
     * 把每条 [dataLines] 元素写成一行 `data: ...`，末尾即 EOF。
     * 正常流与半截 EOF 的差别只在内容里有没有终止事件，因此共用同一个入口，由调用方决定最后一行。
     */
    fun body(vararg dataLines: String): ResponseBody =
        dataLines.joinToString(separator = "") { "data: $it\n\n" }.toResponseBody(SSE_MEDIA_TYPE)

    /**
     * 已经是完整 SSE 行的场景（调用方自己带了 `data: ` 前缀、或混着 `event:` 行），
     * 按原样拼接，不再二次加前缀。
     */
    fun rawBody(vararg lines: String): ResponseBody =
        lines.joinToString(separator = "\n", postfix = "\n").toResponseBody(SSE_MEDIA_TYPE)

    /**
     * 先逐行投递 [dataLines]，全部读完后下一次 read 抛 [IOException]。
     *
     * 为什么不用「少写终止事件的 EOF」冒充异常：两者在 adapter 里是两条不同的代码路径，EOF 会被翻译成
     * 统一文案，底层异常则必须保留原始信息。若用 EOF 冒充，就测不出「异常没有被吞掉 / 没有被替换」。
     */
    fun interruptingBody(
        dataLines: List<String>,
        message: String = "模拟连接被重置"
    ): ResponseBody = ScriptedResponseBody(dataLines.map { "data: $it\n\n" }, message)

    /** 真实 SSE 响应同样不带 Content-Length（chunked 传输），故长度返回 -1。 */
    private class ScriptedResponseBody(
        private val chunks: List<String>,
        private val failureMessage: String
    ) : ResponseBody() {

        override fun contentType(): MediaType = SSE_MEDIA_TYPE

        override fun contentLength(): Long = -1L

        override fun source(): BufferedSource = ScriptedSource(chunks, failureMessage).buffer()
    }

    /**
     * 一次 read 只投递一整条事件。
     *
     * 必须按行分片而不是一次灌完：BufferedReader 的 fill() 会把底层异常与本次读取捆在一起，
     * 若内容一口气全塞进去、故障在同一次 fill 里抛出，前面那些行根本没机会交给解析器，
     * 被测路径就退化成「一上来就断」，复现不出「已累加部分文本后断流」。
     */
    private class ScriptedSource(
        private val chunks: List<String>,
        private val failureMessage: String
    ) : Source {

        private var index = 0

        override fun read(sink: Buffer, byteCount: Long): Long {
            if (index >= chunks.size) throw IOException(failureMessage)
            val bytes = chunks[index++].toByteArray(Charsets.UTF_8)
            sink.write(bytes)
            return bytes.size.toLong()
        }

        override fun timeout(): Timeout = Timeout.NONE

        override fun close() = Unit
    }
}

/**
 * 假 [Call]：`await()` 内部走 `enqueue`，这里同步回调 `onResponse` 即可，不需要线程池，也不会发请求。
 *
 * [bodyFactory] 每次调用重新生产响应体：真实重试场景下同一个 Call 会被重发，复用已消费的
 * ResponseBody 会读到空流，测试就变成假绿。
 */
internal class FakeStreamCall(
    private val bodyFactory: () -> ResponseBody
) : Call<ResponseBody> {

    @Volatile
    private var canceled = false

    override fun request(): Request = Request.Builder().url("https://fake-sse.invalid/stream").build()

    override fun execute(): Response<ResponseBody> = Response.success(bodyFactory())

    override fun enqueue(callback: Callback<ResponseBody>) {
        // retrofit 的 await() 只依赖这次同步回调，不关心线程，所以不必起线程池。
        callback.onResponse(this, Response.success(bodyFactory()))
    }

    /** 假 Call 只有一次同步投递，没有「已执行」状态可维护。 */
    override fun isExecuted(): Boolean = false

    override fun cancel() {
        canceled = true
    }

    override fun isCanceled(): Boolean = canceled

    override fun clone(): Call<ResponseBody> = FakeStreamCall(bodyFactory)

    override fun timeout(): Timeout = Timeout.NONE
}

/**
 * 只覆盖流式路径的测试里，把非流式入口做成显式失败：
 * 万一 adapter 走错分支，测试会立刻报「调了非流式接口」，而不是悄悄拿到一个无关的默认值。
 */
internal fun unsupportedNonStreaming(): Nothing =
    throw UnsupportedOperationException("本测试只覆盖流式路径")
