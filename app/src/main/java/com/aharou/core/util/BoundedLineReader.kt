package com.aharou.core.util

import java.io.Closeable
import java.io.Reader

/**
 * 单行输出上限（字符）。`BufferedReader.readLine()` 遇到超长单行（命令把二进制/大文件 dump 到
 * stdout，如 `cat` 可执行文件、`base64` 大文件）会把整行拼进内存，设备上直接 OOM。
 * 超过上限即截断、丢弃该行余下内容，保证内存有界。
 */
internal const val MAX_STREAM_LINE_CHARS = 64 * 1024

/** 超长单行被截断后追加的提示行。 */
internal const val LINE_TRUNCATED_NOTE = "[该行输出过长，已截断]"

/** 一行输出；[truncated] 为 true 表示该行超过 [MAX_STREAM_LINE_CHARS] 被截断。 */
internal class StreamLine(val text: String, val truncated: Boolean)

/**
 * 逐行读取 [reader]，分行语义与 [java.io.BufferedReader.readLine] 一致（`\n` / `\r` / `\r\n`），
 * 但单行长度封顶 [maxChars]：达到上限后不再累积、继续消费该行余下内容并丢弃，避免超长单行 OOM。
 */
internal class BoundedLineReader(
    private val reader: Reader,
    private val maxChars: Int = MAX_STREAM_LINE_CHARS
) : Closeable {
    private val buf = CharArray(8192)
    private var pos = 0
    private var len = 0
    private var skipLf = false

    override fun close() = reader.close()

    fun readLine(): StreamLine? {
        val sb = StringBuilder()
        var truncated = false
        var sawContent = false
        while (true) {
            if (pos >= len) {
                if (len == -1) return if (sawContent) StreamLine(sb.toString(), truncated) else null
                len = reader.read(buf)
                pos = 0
                continue
            }
            val c = buf[pos++]
            if (skipLf) {
                skipLf = false
                if (c == '\n') continue
            }
            when (c) {
                '\n' -> return StreamLine(sb.toString(), truncated)
                '\r' -> {
                    skipLf = true
                    return StreamLine(sb.toString(), truncated)
                }
                else -> {
                    sawContent = true
                    if (sb.length < maxChars) sb.append(c) else truncated = true
                }
            }
        }
    }
}

/**
 * 惰性逐行读取：每次只物化当前一行（单行封顶 [MAX_STREAM_LINE_CHARS]），读到哪算哪，整文件不进内存。
 *
 * 每次迭代都会调用 [open] 重新打开 reader，因此序列可重复迭代；一次迭代被完整消费或抛错时自动关闭。
 */
internal fun boundedLines(open: () -> Reader): Sequence<String> = sequence {
    BoundedLineReader(open()).use { reader ->
        while (true) {
            val line = reader.readLine() ?: break
            // 截断必须让调用方（AI 工具/模型）看得见，否则会以为自己读到的是完整一行。
            yield(if (line.truncated) line.text + LINE_TRUNCATED_NOTE else line.text)
        }
    }
}