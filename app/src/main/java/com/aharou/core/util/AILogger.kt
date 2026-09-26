package com.aharou.core.util

import android.content.Context
import android.util.Log
import com.google.gson.GsonBuilder
import java.io.File

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * AI 提供商「完整请求 / 响应」日志：**每个会话(sessionId)一个文件**，逐次详细落盘每一次
 * 调用的 URL、请求体(body) 与响应(response)，便于在没有抓包工具时离线诊断模型交互问题。
 *
 * 与 [FileLogger]（按天分文件的通用应用日志）相互独立：本类按「会话」维度归档，体量更大、
 * 内容更全（含完整对话历史、工具定义、原始 SSE 流），因此单独成文件、单独清理。
 *
 * 文件落在外部私有目录 `getExternalFilesDir/ai-logs/session-<id>.log`（不可用时回退内部
 * `filesDir/ai-logs/`）。所有写入串行化到单线程后台执行，不阻塞调用方协程。
 * 请求体不含 API Key（密钥在 HTTP 头，本类只记录 URL 与 body），可安全留存。
 *
 * 使用前需在 [android.app.Application.onCreate] 调用一次 [init]。
 */
object AILogger {

    private const val TAG = "AILogger"
    private const val MAX_AGE_DAYS = 7
    private const val MAX_FILE_BYTES = 20 * 1024 * 1024 // 单会话文件上限 20MB（每轮重发完整历史，增长快）
    /** 原始 SSE 日志缓冲字符上限：流式响应体量无上限，整段累积再 toString 会在移动端把堆顶爆。 */
    private const val MAX_RAW_SSE_CHARS = 512 * 1024
    private const val TRUNCATED_SSE_MARKER = "\n...[raw SSE 已截断]\n"
    /** 请求/响应体写入日志的单段上限（字符）：长历史 / 大附件整段序列化再拼接会在移动端 OOM。 */
    private const val MAX_LOGGED_CHARS = 2 * 1024 * 1024
    private const val LOG_TRUNCATED_MARKER = "\n...[日志内容过长，已截断]"

    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ai-logger").apply { isDaemon = true }
    }
    private val timestampFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(java.time.ZoneId.systemDefault())
    // 与 Retrofit 的 GsonConverter 行为对齐（默认字段名、忽略 null），额外开启缩进便于阅读，
    // 关掉 HTML 转义避免把 prompt 里的 < > & 转成实体、影响可读性。
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()

    @Volatile
    private var logDir: File? = null

    /** 每会话的调用计数：用于把同一次交互的 REQUEST / RESPONSE 配上同一序号。 */
    private val counters = ConcurrentHashMap<String, AtomicInteger>()

    /** 初始化日志目录。重复调用安全。 */
    fun init(context: Context) {
        if (logDir != null) return
        // 优先外部私有目录，便于（root 或 adb 下）取出；不可用时回退内部存储。
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "ai-logs").apply { mkdirs() }
        logDir = dir
        ioExecutor.execute { cleanupOldLogs(dir) }
        FileLogger.i(TAG, "AILogger 初始化完成，AI 会话日志目录: ${dir.absolutePath}")
    }

    /**
     * 记录一次请求的 URL 与请求体，并把本会话计数 +1（作为本次交互的序号）。
     *
     * @return 本次分配的序号 `n`，调用方必须把它原样回传给对应的 [logResponse] /
     *   [logError] / [logResponseStream]，否则同会话内并发请求（如标题生成与主请求并行）
     *   会让 REQUEST 与 RESPONSE/ERROR 的编号错配——响应晚到时读到的是最新计数器值。
     */
    fun logRequest(sessionId: String?, provider: String, model: String, method: String, url: String, body: Any?): Int {
        val n = counter(sessionId).incrementAndGet()
        val text = buildString {
            append('\n').append("=".repeat(78)).append('\n')
            append(now()).append("  REQUEST #").append(n)
            append("   [").append(provider).append(" / ").append(model).append("]\n")
            append(method).append(' ').append(url).append('\n')
            append("--- request body ---\n")
            append(stringify(body)).append('\n')
        }
        write(sessionId, text)
        return n
    }

    /** 记录一次非流式响应对象（用 Gson 序列化为 JSON）。[seq] 必须来自对应 [logRequest] 的返回值。 */
    fun logResponse(sessionId: String?, provider: String, body: Any?, seq: Int) {
        val text = buildString {
            append(now()).append("  RESPONSE #").append(seq)
            append("   [").append(provider).append("]\n")
            append("--- response body ---\n")
            append(stringify(body)).append('\n')
        }
        write(sessionId, text)
    }

    /** 记录一次流式响应的原始 SSE 文本（由调用方按行累积后整体传入）。[seq] 必须来自对应 [logRequest] 的返回值。 */
    fun logResponseStream(sessionId: String?, provider: String, raw: String, seq: Int) {
        val text = buildString {
            append(now()).append("  RESPONSE #").append(seq)
            append("   [").append(provider).append(" / stream]\n")
            append("--- raw SSE ---\n")
            append(MediaRedactor.redact(raw).ifBlank { "(空响应)" })
            if (!raw.endsWith("\n")) append('\n')
        }
        write(sessionId, text)
    }

    /**
     * 把一行原始 SSE 追加到 [sb]，超过 [MAX_RAW_SSE_CHARS] 后停止累积。
     *
     * 原始 SSE 仅用于离线诊断；若整段无上限累积，最后 `toString()` 会在移动端 OOM（曾致 release 崩溃）。
     */
    fun appendRawSse(sb: StringBuilder, line: String) {
        if (sb.length >= MAX_RAW_SSE_CHARS) return
        val room = MAX_RAW_SSE_CHARS - sb.length
        if (line.length + 1 <= room) {
            sb.append(line).append('\n')
        } else {
            sb.append(line, 0, maxOf(room - TRUNCATED_SSE_MARKER.length, 0))
            sb.append(TRUNCATED_SSE_MARKER)
        }
    }

    /** 记录一次请求失败（取消不算失败，不应走到这里）。[seq] 必须来自对应 [logRequest] 的返回值。 */
    fun logError(sessionId: String?, provider: String, throwable: Throwable, seq: Int) {
        val text = buildString {
            append(now()).append("  ERROR #").append(seq)
            append("   [").append(provider).append("]\n")
            append(throwable.javaClass.name).append(": ").append(throwable.message ?: "").append('\n')
        }
        write(sessionId, text)
    }

    private fun counter(sessionId: String?): AtomicInteger =
        counters.getOrPut(sessionId ?: "unknown") { AtomicInteger(0) }

    /** 返回当前所有会话日志文件，按文件名排序，供占用统计与清理使用。 */
    fun listLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    /**
     * 删除全部会话日志，返回释放的字节数。
     *
     * 排到 [ioExecutor] 上执行，避免与排队中的追加写交错（本类每次写入都是 append 后即关，不持有句柄）。
     * 同时重置调用序号，清空后新日志从 #1 开始。
     */
    fun clearLogs(): Long {
        val dir = logDir ?: return 0L
        val freed = java.util.concurrent.atomic.AtomicLong(0)
        val latch = java.util.concurrent.CountDownLatch(1)
        ioExecutor.execute {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
                    val size = file.length()
                    if (file.delete()) freed.addAndGet(size)
                }
                counters.clear()
            }.onFailure { Log.e(TAG, "清空 AI 会话日志失败", it) }
            latch.countDown()
        }
        runCatching { latch.await(5, java.util.concurrent.TimeUnit.SECONDS) }
        return freed.get()
    }

    private fun now(): String = timestampFormat.format(java.time.Instant.now())

    /**
     * 序列化用于日志的对象。长度封顶 [MAX_LOGGED_CHARS]：大对象经有界 [LimitWriter] 序列化，
     * 避免超大请求/响应体（长历史、大附件）整段进内存、再被日志拼接复制而 OOM。
     */
    private fun stringify(body: Any?): String = when (body) {
        null -> MediaRedactor.redact("null")
        is String -> MediaRedactor.redact(body.truncateForLog())
        else -> {
            val writer = LimitWriter(MAX_LOGGED_CHARS)
            runCatching { gson.toJson(body, writer) }.fold(
                onSuccess = { MediaRedactor.redact(writer.result()) },
                onFailure = { MediaRedactor.redact(body.toString().truncateForLog()) }
            )
        }
    }

    /** 截断超长文本并追加提示，限定单段日志的内存占用。 */
    private fun String.truncateForLog(): String =
        if (length <= MAX_LOGGED_CHARS) this else take(MAX_LOGGED_CHARS) + LOG_TRUNCATED_MARKER

    /** 有界 [java.io.Writer]：写入超过 [limit] 字符后丢弃后续内容，避免超大对象序列化整段进内存。 */
    private class LimitWriter(private val limit: Int) : java.io.Writer() {
        private val sb = StringBuilder()
        private var truncated = false

        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (sb.length >= limit) {
                truncated = true
                return
            }
            val n = minOf(limit - sb.length, len)
            sb.append(cbuf, off, n)
            if (n < len) truncated = true
        }

        override fun flush() {}

        override fun close() {}

        fun result(): String = if (truncated) sb.toString() + LOG_TRUNCATED_MARKER else sb.toString()
    }

    private fun write(sessionId: String?, text: String) {
        val dir = logDir ?: return // 未初始化则直接丢弃，避免在无目录时报错刷屏
        val safeId = (sessionId ?: "unknown").replace(Regex("[^A-Za-z0-9_-]"), "_")
        ioExecutor.execute {
            runCatching {
                val file = File(dir, "session-$safeId.log")
                if (file.length() > MAX_FILE_BYTES) {
                    // 超上限则截断重开，避免单文件无限增长。
                    file.writeText("--- AI 会话日志超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 已重置 ---\n")
                }
                file.appendText(text)
            }.onFailure { Log.e(TAG, "写入 AI 会话日志失败", it) }
        }
    }

    /** 删除超过 [MAX_AGE_DAYS] 天未更新的会话日志文件。 */
    private fun cleanupOldLogs(dir: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        dir.listFiles { f -> f.isFile && f.name.startsWith("session-") }?.forEach { file ->
            if (file.lastModified() < cutoff) runCatching { file.delete() }
        }
    }
}
