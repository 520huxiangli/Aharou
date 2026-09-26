package com.aharou.core.util

/**
 * 日志媒体脱敏：落盘前把超大 base64（图片等）替换为省略标记，防止单行几 MB 撑爆日志文件。
 *
 * 三层覆盖：
 * 1. `data:<mime>;base64,...` 内联 data URL（生图/截图工具结果常见）；
 * 2. 已知字段名（`data` / `base64Data` / `image_data`）下的 base64 值（Gson pretty 或 SSE compact 均命中）；
 * 3. 兜底：任意位置 ≥1024 的连续 base64 字符——不管字段叫什么都能截住，代价是超长纯字母数字文本也会被省略（罕见，可接受）。
 *
 * 阈值取 256/1024：真实图片 base64 至少几百字符，正常文本几乎不会连续 256 个 base64 字符。
 */
object MediaRedactor {

    private val DATA_URL_REGEX =
        Regex("data:[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+;base64,([A-Za-z0-9+/=_-]{256,})")
    private val BASE64_FIELD_REGEX =
        Regex("\"(base64Data|data|image_data)\"\\s*:\\s*\"([A-Za-z0-9+/=_-]{256,})\"")
    private val LONG_BASE64_REGEX = Regex("[A-Za-z0-9+/=_-]{1024,}")

    fun redact(text: String): String {
        if (text.isBlank()) return text
        val afterDataUrl = DATA_URL_REGEX.replace(text) { m ->
            "data:[base64 omitted: ${m.groupValues[1].length} chars]"
        }
        val afterField = BASE64_FIELD_REGEX.replace(afterDataUrl) { m ->
            "\"${m.groupValues[1]}\": \"[base64 omitted: ${m.groupValues[2].length} chars]\""
        }
        return LONG_BASE64_REGEX.replace(afterField) { m ->
            "[base64 omitted: ${m.value.length} chars]"
        }
    }
}
