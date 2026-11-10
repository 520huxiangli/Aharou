package com.aharou.feature.agent.domain.provider

/**
 * 非 SSE 响应体的取样器。
 *
 * 中转服务拒绝请求时常常仍是 HTTP 200，body 却是 JSON 错误或一段 HTML；整段按 `data:` 行读到头
 * 也没有有效数据，于是被当成「流被中断」。把开头一截留下来，报错时才能看到上游原话。
 */
internal class SseRawHead(private val limitChars: Int = RAW_HEAD_LIMIT) {

    private val buffer = StringBuilder()

    /** 记一行非 `data:` 内容；返回 true 表示这行确实不是 SSE 数据行，调用方应跳过它。 */
    fun noteIfOther(line: String): Boolean {
        if (line.startsWith("data:")) return false
        if (buffer.length < limitChars) buffer.append(line).append('\n')
        return true
    }

    fun text(): String = buffer.toString()
}

/** 取样上限：够看到上游原话即可，超长错误页不必整个留下。 */
internal const val RAW_HEAD_LIMIT = 4096

/** 断流文案：把上游返回的非流式内容附在后面，替掉原来只有「疑似网络断开」的无信息提示。 */
internal fun interruptedMessage(reason: String, rawHead: String): String {
    val snippet = rawHead.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(" ")
        .take(300)
    val base = "SSE 流被中断：$reason（疑似网络断开）"
    return if (snippet.isEmpty()) base else "$base；上游返回：$snippet"
}
