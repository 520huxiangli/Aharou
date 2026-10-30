package com.aharou.feature.agent.domain.provider

/**
 * 单次流式响应允许累积的增量字符上限。
 *
 * 日志缓冲 `rawSse` 已有 512K 封顶，但**业务侧**的累积器（正文 / 思考 / 工具入参）此前没有任何上限：
 * provider 的 baseUrl 用户可自定义，异常或被劫持的上游可以无限下发 delta，客户端照单全收会撑爆堆。
 */
const val MAX_STREAM_CHARS = 4 * 1024 * 1024

/**
 * 统计一次流式响应累积的增量字符数，超过 [maxChars] 即抛 [StreamApiException] 中断本次请求。
 *
 * 选择中断而非静默截断：工具入参被截断会变成半截 JSON 被当作参数执行，正文被截断会让用户拿到残缺回答，
 * 都不如直接报错。4M 字符比任何模型的正常输出高两个数量级，正常使用不会触发。
 */
class StreamBudget(private val maxChars: Int = MAX_STREAM_CHARS) {
    private var total = 0L

    fun add(delta: String) {
        if (delta.isEmpty()) return
        total += delta.length
        if (total > maxChars) {
            throw StreamApiException(
                code = "response_too_large",
                message = "上游返回内容异常（单次响应超过 ${maxChars / 1024}KB），已中断"
            )
        }
    }
}
