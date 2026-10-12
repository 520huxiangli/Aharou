package com.aharou.feature.browser

import android.webkit.ConsoleMessage
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.WeakHashMap

/** 一条页面控制台日志，来自 [android.webkit.WebChromeClient.onConsoleMessage]。 */
data class BrowserConsoleEntry(
    val level: String,
    val message: String,
    val line: Int,
    val source: String,
)

/**
 * 每标签页一份的控制台日志环形缓冲：页面可以无限打日志，全留会把内存吃光，
 * 所以固定容量、满了丢最旧的（保留最近 [maxEntries] 条）。
 */
class BrowserConsoleBuffer(private val maxEntries: Int = MAX_ENTRIES) {
    private val entries = ArrayDeque<BrowserConsoleEntry>()

    fun record(entry: BrowserConsoleEntry) {
        if (entries.size >= maxEntries) entries.removeFirst()
        entries.addLast(entry)
    }

    fun snapshot(level: String?): List<BrowserConsoleEntry> =
        if (level.isNullOrBlank()) entries.toList()
        else entries.filter { it.level.equals(level, ignoreCase = true) }

    fun clear() = entries.clear()

    companion object {
        const val MAX_ENTRIES = 200
    }
}

/**
 * 按 WebView 身份持有控制台缓冲：写入发生在页面线程的 onConsoleMessage，
 * 读取发生在 console 动作里，两者必须共享同一份数据，所以挂在 WebView 上；
 * WeakHashMap 保证标签页被回收后缓冲随之释放。
 */
object BrowserConsoleStore {
    private val buffers = WeakHashMap<WebView, BrowserConsoleBuffer>()

    fun bufferFor(webView: WebView): BrowserConsoleBuffer =
        buffers.getOrPut(webView) { BrowserConsoleBuffer() }

    fun record(webView: WebView, message: ConsoleMessage) {
        bufferFor(webView).record(
            BrowserConsoleEntry(
                level = message.messageLevel().toLevelName(),
                message = message.message().orEmpty(),
                line = message.lineNumber(),
                source = message.sourceId().orEmpty(),
            ),
        )
    }

    /**
     * 取缓冲快照并按级别过滤后格式化为可读文本；[clear] 为 true 时取完即清空。
     * 缓冲写入发生在 WebView 主线程，这里回到主线程读写，避免与 onConsoleMessage 竞争。
     */
    suspend fun drain(webView: WebView, level: String?, clear: Boolean): String =
        withContext(Dispatchers.Main) {
            val buffer = bufferFor(webView)
            val entries = buffer.snapshot(level)
            if (clear) buffer.clear()
            buildString {
                append("Console: ${entries.size} message(s)")
                if (!level.isNullOrBlank()) append(" (level=$level)")
                append('\n')
                for (e in entries) {
                    val loc = if (e.source.isNotBlank()) " (${e.source}:${e.line})" else ""
                    append("  [${e.level}] ${e.message}$loc\n")
                }
            }.trimEnd()
        }

    private fun ConsoleMessage.MessageLevel.toLevelName(): String = when (this) {
        ConsoleMessage.MessageLevel.ERROR -> "error"
        ConsoleMessage.MessageLevel.WARNING -> "warning"
        ConsoleMessage.MessageLevel.DEBUG -> "debug"
        else -> "log"
    }
}
