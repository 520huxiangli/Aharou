package com.aharou.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日志媒体脱敏（[MediaRedactor]）：超大 base64（图片等）必须在落盘前被替换为省略标记，
 * 否则单行几 MB 的图片数据会撑爆会话/按天日志文件。
 */
class MediaRedactorTest {

    private fun b64(char: Char, count: Int): String = char.toString().repeat(count)

    @Test
    fun data_url_image_is_redacted() {
        val input = "看图 data:image/png;base64,${b64('A', 1000)} 结尾"
        val out = MediaRedactor.redact(input)
        assertTrue(out.contains("[base64 omitted: 1000 chars]"))
        assertTrue(out.startsWith("看图 data:"))
        assertTrue(out.endsWith("结尾"))
    }

    @Test
    fun data_field_with_long_base64_is_redacted_pretty_and_compact() {
        val value = b64('B', 600)
        // Gson pretty 打印（带空格）
        val pretty = MediaRedactor.redact("""{"type":"image","mime_type":"image/png","data": "$value"}""")
        assertTrue(pretty.contains("\"data\": \"[base64 omitted: 600 chars]\""))
        assertTrue(!pretty.contains(value))
        // SSE 紧凑格式（无空格）
        val compact = MediaRedactor.redact("""{"type":"image","data":"$value"}""")
        assertTrue(compact.contains("[base64 omitted: 600 chars]"))
    }

    @Test
    fun base64_data_key_is_redacted() {
        val value = b64('C', 500)
        val out = MediaRedactor.redact("""{"base64Data": "$value"}""")
        assertTrue(out.contains("\"base64Data\": \"[base64 omitted: 500 chars]\""))
    }

    @Test
    fun long_base64_under_unknown_key_is_caught_by_fallback() {
        // 兜底正则：任意字段名下的超长 base64 也截住
        val value = b64('D', 2000)
        val out = MediaRedactor.redact("""{"weird_key": "$value"}""")
        assertTrue(out.contains("[base64 omitted: 2000 chars]"))
        assertTrue(!out.contains(value))
    }

    @Test
    fun short_base64_below_threshold_is_kept() {
        val value = b64('E', 100)
        val out = MediaRedactor.redact("""{"data": "$value"}""")
        assertTrue(out.contains(value))
        assertTrue(!out.contains("omitted"))
    }

    @Test
    fun normal_text_is_untouched() {
        val input = "正常日志：文件读取失败，请检查路径 ~/workspace/app/src/Main.kt"
        assertEquals(input, MediaRedactor.redact(input))
    }

    @Test
    fun already_redacted_text_does_not_blow_up() {
        val out = MediaRedactor.redact("数据: [base64 omitted: 1629780 chars]")
        assertEquals("数据: [base64 omitted: 1629780 chars]", out)
    }
}
