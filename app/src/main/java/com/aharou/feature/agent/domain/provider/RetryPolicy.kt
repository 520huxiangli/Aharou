package com.aharou.feature.agent.domain.provider

import com.aharou.core.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import retrofit2.HttpException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLException
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlin.math.pow

/**
 * 参考 opencode 的网络请求重试策略：指数退避，仅针对瞬时故障。
 */
private const val TAG = "RetryPolicy"

// 对应 opencode 的 attempts = 3（最多 2 次重试）→ 提升至 6 次重试以应对不稳定网络
const val MAX_NETWORK_RETRIES = 6

/**
 * 流式请求首字节等待超时：超过此时间未收到首个内容块即关闭流，触发可重试的 IOException。
 *
 * OkHttp 的 readTimeout 已设为无限制，首字节之前若卡死只能靠此应用层 watchdog 兜底，
 * 故放宽到 5 分钟以容纳慢启动与长思考模型。
 */
const val FIRST_BYTE_TIMEOUT_MS = 300_000L

/**
 * 启动首字节超时 watchdog（作为当前协程的子协程）：在 [timeoutMs] 后
 * 若 [isFirstByteReceived] 仍为 false，则调用 [close]（通常是关闭 ResponseBody），
 * 强制读取抛出 IOException 以被重试机制捕获。
 *
 * [timeoutMs] <= 0 表示不限制，此时不启动计时。
 * 调用方应在收到首个内容块后取消返回的 [Job]。
 */
suspend fun launchFirstByteWatchdog(
    timeoutMs: Long,
    close: () -> Unit,
    isFirstByteReceived: () -> Boolean
): Job = CoroutineScope(coroutineContext[Job]!!).launch {
    if (timeoutMs <= 0) return@launch
    delay(timeoutMs)
    if (!isFirstByteReceived()) {
        runCatching { close() }
    }
}

/**
 * 流式响应「数据块间隔」watchdog：相邻两个数据块之间超过 [timeoutMs] 未到达即调用 [close]，
 * 使阻塞中的读取抛出 IOException，交给重试机制处理。
 *
 * 每收到一个数据块调用一次 [touch] 重新计时；流结束（正常或异常）时调用 [cancel]。
 * [timeoutMs] <= 0 表示不限制，此时两个方法均为空操作。
 */
class StreamIdleWatchdog(
    private val scope: CoroutineScope,
    private val timeoutMs: Long,
    private val close: () -> Unit
) {
    private var timer: Job? = null

    fun touch() {
        if (timeoutMs <= 0) return
        timer?.cancel()
        timer = scope.launch {
            delay(timeoutMs)
            runCatching { close() }
        }
    }

    fun cancel() {
        timer?.cancel()
        timer = null
    }
}

/** 在当前协程下创建 [StreamIdleWatchdog]；[timeoutMs] <= 0 时创建的实例不做任何事。 */
suspend fun launchStreamIdleWatchdog(timeoutMs: Long, close: () -> Unit): StreamIdleWatchdog =
    StreamIdleWatchdog(CoroutineScope(coroutineContext[Job]!!), timeoutMs, close)

private val TRANSIENT_MESSAGES = listOf(
    "load failed",
    "network connection was lost",
    "network request failed",
    "failed to fetch",
    "econnreset",
    "econnrefused",
    "etimedout",
    "socket hang up"
)

/**
 * 对应 opencode 的 delay=500, factor=2, maxDelay=10000
 */
fun exponentialDelayMillis(retryIndex: Int): Long {
    val delay = 500L
    val factor = 2.0
    val maxDelay = 10000L
    val wait = (delay * factor.pow(retryIndex)).toLong()
    return min(wait, maxDelay)
}

/**
 * SSE 流中收到的 error 事件，携带错误码用于重试判定。
 *
 * 对齐 Codex CLI 的错误分类：cyber_policy / invalid_request / context_window_exceeded /
 * quota_exceeded / usage_not_included 等为不可重试；server_error / server_is_overloaded 等默认可重试。
 */
class StreamApiException(
    val code: String?,
    message: String,
    val retryAfterMillis: Long? = null
) : Exception(message.ifBlank { code ?: "stream error" })

// 对齐 Codex CLI is_retryable()：这些错误码明确不可重试。
// 限流与额度类在此列，是因为它们应直接交给多 Key 自动切换，而不是在同一 Key 上退避重试。
private val NON_RETRYABLE_STREAM_CODES = setOf(
    "cyber_policy",
    "invalid_request_error",
    "invalid_request",
    "context_window_exceeded",
    "quota_exceeded",
    "usage_not_included",
    "usage_limit_reached",
    "insufficient_quota",
    "rate_limit_exceeded",
    "rate_limit_error",
    "invalid_image_request",
    "response_too_large"
)

/**
 * 对应 opencode 的 isTransientError，并兼容 Android 的网络异常类型。
 *
 * 除传统的 IOException 判定外，还支持 HTTP 状态码感知：
 * - 408（请求超时）、5xx（500/502/503/504 等）→ 可重试（服务端瞬时故障）
 * - 429（限流）→ 不重试，交给多 Key 自动切换（换 Key 比等待更有效）
 * - 其他 4xx → 不重试（客户端错误，重试无意义）
 */
fun isRetriableNetworkError(t: Throwable): Boolean {
    if (t is CancellationException) return false

    if (t is StreamApiException) {
        return !NON_RETRYABLE_STREAM_CODES.contains(t.code)
    }

    // 兼容原生网络异常
    if (t is SocketTimeoutException || t is InterruptedIOException ||
        t is java.net.UnknownHostException || t is java.net.ConnectException ||
        t is javax.net.ssl.SSLException || t is IOException) {
        return true
    }

    // HTTP 状态码感知：408/5xx 视为瞬时故障可重试；429 归多 Key 切换
    if (t is HttpException) {
        val code = t.code()
        return code == 408 || code >= 500
    }

    val message = t.message?.lowercase() ?: t.toString().lowercase()
    return TRANSIENT_MESSAGES.any { message.contains(it) }
}

/** 重试错误的用户可见类别，用于 UI 展示具体原因（而非笼统的「网络波动」）。 */
enum class RetryErrorKind {
    /** HTTP 429 / 服务端 rate limit 类错误。 */
    RATE_LIMIT,
    /** HTTP 503 服务过载（或流式错误码 server_is_overloaded / overloaded）。 */
    SERVER_OVERLOADED,
    /** HTTP 5xx（除 503）等服务端错误。 */
    SERVER_ERROR,
    /** 连接/读取超时（含首字节 watchdog 触发的断流）。 */
    TIMEOUT,
    /** 目标端口无服务，连接被拒绝。 */
    CONNECTION_REFUSED,
    /** DNS 解析失败（域名不存在或网络不可达）。 */
    DNS_FAILED,
    /** 连接建立后被对端/中间设备重置（流中断、unexpected end of stream 等）。 */
    CONNECTION_RESET,
    /** TLS/SSL 握手失败。 */
    SSL_ERROR,
    /** 其它网络层故障。 */
    NETWORK,
    /** 无法归类的其它错误。 */
    UNKNOWN
}

/**
 * 一次重试对应的错误摘要，随 Retrying 事件一路透传到 UI。
 * [statusCode] 为 HTTP 状态码（如 429/500），非 HTTP 错误为 null。
 */
data class RetryErrorInfo(
    val kind: RetryErrorKind,
    val statusCode: Int? = null
)

/** 把触发重试的异常归类为 UI 可展示的错误摘要。 */
fun Throwable.toRetryErrorInfo(): RetryErrorInfo = when {
    this is HttpException -> when {
        code() == 429 -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT, code())
        code() == 503 -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED, code())
        code() >= 500 -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR, code())
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN, code())
    }
    this is StreamApiException -> when (code) {
        "rate_limit_exceeded", "rate_limit_error", "insufficient_quota" -> RetryErrorInfo(RetryErrorKind.RATE_LIMIT)
        "server_is_overloaded", "overloaded" -> RetryErrorInfo(RetryErrorKind.SERVER_OVERLOADED)
        "server_error", "internal_error" -> RetryErrorInfo(RetryErrorKind.SERVER_ERROR)
        else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
    }
    this is SocketTimeoutException || this is InterruptedIOException -> RetryErrorInfo(RetryErrorKind.TIMEOUT)
    this is UnknownHostException -> RetryErrorInfo(RetryErrorKind.DNS_FAILED)
    this is ConnectException -> RetryErrorInfo(RetryErrorKind.CONNECTION_REFUSED)
    // SSLException / ConnectException / UnknownHostException 都是 IOException 子类，必须先于 IOException 匹配
    this is SSLException -> RetryErrorInfo(RetryErrorKind.SSL_ERROR)
    this is IOException -> if (isConnectionReset(this)) {
        RetryErrorInfo(RetryErrorKind.CONNECTION_RESET)
    } else {
        RetryErrorInfo(RetryErrorKind.NETWORK)
    }
    else -> RetryErrorInfo(RetryErrorKind.UNKNOWN)
}

/** 流被对端/中间设备中断的常见报错文案，命中则归类为 [RetryErrorKind.CONNECTION_RESET]。 */
private val RESET_MESSAGES = listOf(
    "connection reset",
    "reset by peer",
    "broken pipe",
    "unexpected end of stream",
    "connection closed",
    "socket hang up",
    "econnreset",
    "stream closed",
    "eof"
)

private fun isConnectionReset(e: IOException): Boolean {
    val message = e.message?.lowercase() ?: return false
    return RESET_MESSAGES.any { message.contains(it) }
}

/**
 * 从 [HttpException] 的响应头中解析 `Retry-After`，返回等待毫秒数。
 *
 * `Retry-After` 有两种格式：
 * 1. 秒数（如 `"30"`）→ 直接转为毫秒
 * 2. HTTP 日期（如 `"Fri, 29 Jun 2026 10:00:00 GMT"`）→ 计算距当前时间的差值
 *
 * 非 HttpException 或无 `Retry-After` 头部 → 返回 null。
 * 解析失败也返回 null（降级到指数退避）。
 */
fun extractRetryAfterMillis(t: Throwable): Long? {
    if (t !is HttpException) return null
    val header = t.response()?.headers()?.get("Retry-After") ?: return null

    // 格式 1：纯秒数
    header.toLongOrNull()?.let { seconds ->
        return seconds * 1000L
    }

    // 格式 2：HTTP 日期（RFC 1123）
    return runCatching {
        val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT")
        }
        val retryTime = sdf.parse(header)?.time ?: return null
        val delay = retryTime - System.currentTimeMillis()
        if (delay > 0) delay else null
    }.getOrNull()
}

/**
 * 计算重试等待时间：优先使用服务端 `Retry-After` 头部指定的延迟，
 * 否则回退到指数退避 [exponentialDelayMillis]。
 *
 * 对 429（速率限制）尤其重要——尊重服务端要求，避免频繁重试加剧限制。
 */
fun retryDelayMillis(retryIndex: Int, error: Throwable): Long {
    val serverDelay = extractRetryAfterMillis(error)
    if (serverDelay != null && serverDelay > 0) {
        return min(serverDelay, MAX_RETRY_AFTER_MILLIS)
    }
    return exponentialDelayMillis(retryIndex)
}

/** Retry-After 头部值的上限，防止服务端返回过大的值导致无限等待。 */
private const val MAX_RETRY_AFTER_MILLIS = 60_000L

/**
 * 在指数退避下重试 [block]（保持原方法名），用于非流式请求。
 *
 * @param onKeyFailure 多 Key 切换回调：在判定为「不可重试」的失败时先调用，入参为
 *        (触发失败, 是否允许重发)。返回 true 表示调用方已切 Key、可重置重试计数并重发；
 *        返回 false 则抛出原异常。网络类失败（408/5xx/超时等）不会触发该回调。
 * @param onRetry 重试前回调，参数为 (当前重试次数, 最大重试次数)；用于通知上层"正在重试"。
 *                置于 [block] 之前以保证 `retryStaircase { ... }` 的 trailing lambda 仍绑定到 [block]。
 * @param maxRetries 最大重试次数（不含首次请求），由调用方按「偏好设置 → 网络」传入；0 表示不重试。
 */
suspend fun <T> retryStaircase(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, true) == true) {
                    attempt = 0
                    continue
                }
                throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "网络请求失败，第 ${attempt + 1}/$maxRetries 次重试（等待 ${wait}ms）: ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}

/**
 * 流式请求的重试封装（保持原方法名）。
 *
 * @param onKeyFailure 多 Key 切换回调：在判定为「不可重试」的失败时先调用，入参为
 *        (触发失败, 是否允许重发)。仅当本次尝试尚未收到任何内容时才会传入允许重发；
 *        已吐字时仍可能切换（供后续请求用新 Key）但不会重发，避免用户看到重复内容。
 * @param onRetry 重试前回调，参数为 (当前重试次数, 最大重试次数)；用于通知上层"正在重试"。
 *                回调在 delay 之前调用，确保 UI 能立即展示重试状态。声明为 suspend 以便
 *                调用方在其中通过 Flow 的 emit() 推送重试事件。
 * @param onContent 流式读取中成功收到内容块时调用（通常在 emit TextDelta/ReasoningDelta 处）。
 *                  一旦收到过内容说明连接已恢复、请求已成功，此后若再断流应重置重试计数，
 *                  否则同一次请求内多次抖动会显示 1,2,3,4,5… 持续累加而不重新计数。
 * @param maxRetries 最大重试次数（不含首次请求），由调用方按「偏好设置 → 网络」传入；0 表示不重试。
 */
suspend fun streamWithStaircaseRetry(
    maxRetries: Int = MAX_NETWORK_RETRIES,
    onKeyFailure: (suspend (Throwable, Boolean) -> Boolean)? = null,
    attemptOnce: suspend (onContent: () -> Unit) -> Unit,
    onRetry: (suspend (attempt: Int, maxRetries: Int, error: RetryErrorInfo) -> Unit)? = null
) {
    var attempt = 0
    while (true) {
        var receivedContent = false
        try {
            attemptOnce { receivedContent = true }
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            coroutineContext.ensureActive()
            // 本次尝试已成功收到过内容：视为新一轮请求，重置重试计数
            if (receivedContent) attempt = 0
            if (!isRetriableNetworkError(e)) {
                if (onKeyFailure?.invoke(e, !receivedContent) == true) {
                    attempt = 0
                    continue
                }
                throw e
            }
            if (attempt >= maxRetries) throw e
            val wait = retryDelayMillis(attempt, e)
            FileLogger.w(TAG, "流式请求失败，第 ${attempt + 1}/$maxRetries 次重试（等待 ${wait}ms）: ${e.javaClass.simpleName} ${e.message}")
            onRetry?.invoke(attempt + 1, maxRetries, e.toRetryErrorInfo())
            attempt++
            if (wait > 0) delay(wait)
        }
    }
}
