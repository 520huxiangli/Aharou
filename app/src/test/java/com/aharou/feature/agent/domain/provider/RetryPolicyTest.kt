package com.aharou.feature.agent.domain.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 触发重试的异常 → 用户可见错误摘要（[Throwable.toRetryErrorInfo]）的分类逻辑。
 */
class RetryPolicyTest {

    @Test
    fun timeout_covers_waiting_for_headers() = runTest {
        var cancelled = false
        try {
            withFirstContentTimeout(100) {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, cancelled)
        }
    }

    @Test
    fun timeout_closes_body_and_is_retriable() = runTest {
        var closed = false
        try {
            withFirstContentTimeout(100) { guard ->
                guard.attach { closed = true }
                awaitCancellation()
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, closed)
            assertEquals(true, isRetriableNetworkError(e))
        }
    }

    @Test
    fun first_content_allows_long_stream_and_preserves_flow_context() = runTest {
        val values = flow {
            withFirstContentTimeout(100) { guard ->
                delay(50)
                guard.receivedContent()
                emit(1)
                delay(1000)
                emit(2)
            }
        }.toList()
        assertEquals(listOf(1, 2), values)
    }

    @Test(timeout = 5000)
    fun timeout_unblocks_blocking_body_read() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val released = java.util.concurrent.CountDownLatch(1)
        try {
            withFirstContentTimeout(50) { guard ->
                guard.attach { released.countDown() }
                released.await()
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(0L, released.count)
        }
    }

    @Test(timeout = 10000)
    fun real_http1_body_read_is_cancelled_on_timeout() = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val release = java.util.concurrent.CountDownLatch(1)
        val serverThread = Thread {
            try {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
                        flush()
                    }
                    release.await()
                }
            } catch (_: java.io.IOException) {
            }
        }.apply { isDaemon = true; start() }
        val client = okhttp3.OkHttpClient.Builder().readTimeout(0, java.util.concurrent.TimeUnit.SECONDS).build()
        val api = retrofit2.Retrofit.Builder()
            .baseUrl("http://127.0.0.1:${server.localPort}/")
            .client(client)
            .addConverterFactory(retrofit2.converter.gson.GsonConverterFactory.create())
            .build()
            .create(com.aharou.feature.agent.data.remote.openai.OpenAIApi::class.java)
        val call = api.streamChatCompletion(
            "http://127.0.0.1:${server.localPort}/", "test", emptyMap(),
            com.aharou.feature.agent.data.remote.openai.ChatCompletionRequest(model = "test", messages = emptyList(), stream = true)
        )
        try {
            withFirstContentTimeout(500) { guard ->
                val body = guard.awaitBody(call)
                body.use {
                    guard.attach { body.close() }
                    body.charStream().buffered().readLine()
                }
            }
            org.junit.Assert.fail("Expected timeout")
        } catch (e: SocketTimeoutException) {
            assertEquals(true, call.isCanceled)
        } finally {
            release.countDown()
            server.close()
            serverThread.join(1000)
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun zero_timeout_does_not_limit_wait() = runTest {
        withFirstContentTimeout(0) { delay(1000) }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun user_cancellation_closes_body_without_timeout_conversion() = runTest {
        var closed = false
        var cancellation = false
        val job = launch {
            try {
                withFirstContentTimeout(1000) { guard ->
                    guard.attach { closed = true }
                    awaitCancellation()
                }
            } catch (e: CancellationException) {
                cancellation = true
                throw e
            }
        }
        runCurrent()
        advanceTimeBy(50)
        job.cancel()
        job.join()
        assertEquals(true, closed)
        assertEquals(true, cancellation)
    }

    @Test
    fun partialContentDisconnects_respectRetryLimit() = runTest {
        var requests = 0
        val retries = mutableListOf<Int>()
        try {
            streamWithStaircaseRetry(
                maxRetries = 2,
                attemptOnce = { onContent ->
                    requests++
                    onContent()
                    if (requests > 3) org.junit.Assert.fail("Retry limit exceeded")
                    throw IOException("Connection reset")
                },
                onRetry = { attempt, _, _ -> retries.add(attempt) }
            )
            org.junit.Assert.fail("Expected IOException")
        } catch (_: IOException) {
            assertEquals(3, requests)
            assertEquals(listOf(1, 2), retries)
        }
    }

    @Test
    fun partialContentDisconnect_withRetriesDisabled_doesNotRetry() = runTest {
        var requests = 0
        try {
            streamWithStaircaseRetry(
                maxRetries = 0,
                attemptOnce = { onContent ->
                    requests++
                    onContent()
                    throw IOException("Connection reset")
                }
            )
            org.junit.Assert.fail("Expected IOException")
        } catch (_: IOException) {
            assertEquals(1, requests)
        }
    }

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody(null)))

    @Test
    fun http_429_maps_to_rate_limit() {
        val info = httpError(429).toRetryErrorInfo()
        assertEquals(RetryErrorKind.RATE_LIMIT, info.kind)
        assertEquals(429, info.statusCode)
    }

    @Test
    fun http_5xx_maps_to_server_error() {
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(500).toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, httpError(503).toRetryErrorInfo().kind)
        assertEquals(503, httpError(503).toRetryErrorInfo().statusCode)
        assertEquals(RetryErrorKind.SERVER_ERROR, httpError(502).toRetryErrorInfo().kind)
    }

    @Test
    fun timeout_exceptions_map_to_timeout() {
        assertEquals(RetryErrorKind.TIMEOUT, SocketTimeoutException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.TIMEOUT, InterruptedIOException().toRetryErrorInfo().kind)
    }

    @Test
    fun network_exceptions_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.DNS_FAILED, UnknownHostException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_REFUSED, ConnectException().toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SSL_ERROR, SSLException("handshake failed").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("Connection reset by peer").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.CONNECTION_RESET, IOException("unexpected end of stream").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.NETWORK, IOException("SSE 流被中断").toRetryErrorInfo().kind)
    }

    @Test
    fun stream_api_codes_map_to_specific_kinds() {
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("rate_limit_exceeded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.RATE_LIMIT, StreamApiException("insufficient_quota", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_OVERLOADED, StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.SERVER_ERROR, StreamApiException("internal_error", "m").toRetryErrorInfo().kind)
        assertEquals(RetryErrorKind.UNKNOWN, StreamApiException("some_other", "m").toRetryErrorInfo().kind)
    }

    @Test
    fun retriable_network_error_classification() {
        // 429 归多 Key 切换，不再走网络重试
        assertEquals(false, isRetriableNetworkError(httpError(429)))
        // 408 与 5xx 仍视为瞬时故障
        assertEquals(true, isRetriableNetworkError(httpError(408)))
        assertEquals(true, isRetriableNetworkError(httpError(500)))
        assertEquals(true, isRetriableNetworkError(httpError(503)))
        // 鉴权/权限/计费类由多 Key 切换处理，不重试
        assertEquals(false, isRetriableNetworkError(httpError(401)))
        assertEquals(false, isRetriableNetworkError(httpError(402)))
        assertEquals(false, isRetriableNetworkError(httpError(403)))
        // 流内限流/额度码同样直接交给多 Key 切换
        assertEquals(false, isRetriableNetworkError(StreamApiException("rate_limit_exceeded", "m")))
        assertEquals(false, isRetriableNetworkError(StreamApiException("insufficient_quota", "m")))
        // 流内服务端故障仍可重试
        assertEquals(true, isRetriableNetworkError(StreamApiException("server_is_overloaded", "m")))
    }

    @Test
    fun status_code_null_for_non_http_errors() {
        assertNull(SocketTimeoutException().toRetryErrorInfo().statusCode)
        assertNull(IOException().toRetryErrorInfo().statusCode)
        assertNull(StreamApiException("server_is_overloaded", "m").toRetryErrorInfo().statusCode)
    }
}
