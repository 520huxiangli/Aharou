package com.aharou.feature.browser

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拼进 JS 源码的字符串转义。
 *
 * 这层如果漏了某个字符，表现是网页自动化「点了没反应」或整段 JS 语法错误，
 * 而且报错来自页面控制台、不在应用日志里。用逐字符断言把当前行为固定下来。
 */
class BrowserUseJsQuoteTest {

    @Test
    fun plainText_isUnchanged() {
        assertEquals("hello world", BrowserUseJS.jsQuote("hello world"))
    }

    @Test
    fun singleQuote_isEscaped() {
        assertEquals("it\\'s", BrowserUseJS.jsQuote("it's"))
    }

    @Test
    fun doubleQuote_isEscaped() {
        assertEquals("say \\\"hi\\\"", BrowserUseJS.jsQuote("say \"hi\""))
    }

    @Test
    fun backslash_isEscapedBeforeAnythingElse() {
        // 反斜杠必须自己先变成两个，否则 "\\n" 会被 JS 解释成换行
        assertEquals("a\\\\nb", BrowserUseJS.jsQuote("a\\nb"))
    }

    @Test
    fun lineBreaksBecomeEscapes() {
        assertEquals("a\\nb\\rc\\td", BrowserUseJS.jsQuote("a\nb\rc\td"))
    }

    @Test
    fun nonAsciiText_isLeftIntact() {
        assertEquals("中文 emoji 🦊", BrowserUseJS.jsQuote("中文 emoji 🦊"))
    }

    @Test
    fun emptyString_yieldsEmptyLiteral() {
        assertEquals("", BrowserUseJS.jsQuote(""))
    }

    /** 选择器里常见的组合：属性选择器带双引号 + 中文文本。 */
    @Test
    fun selectorStyleInput_isEscapedForEmbedding() {
        assertEquals(
            "[data-x=\\\"a\\'b\\\"] 中文",
            BrowserUseJS.jsQuote("[data-x=\"a'b\"] 中文")
        )
    }
}
