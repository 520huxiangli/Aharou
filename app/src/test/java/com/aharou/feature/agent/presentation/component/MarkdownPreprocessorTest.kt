package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownPreprocessorTest {

    private fun process(s: String) = MarkdownPreprocessor.process(s)

    @Test
    fun `plain text unchanged`() {
        val t = "普通文本，没有特殊标记。"
        assertEquals(t, process(t))
    }

    @Test
    fun `inline math becomes math image link`() {
        val out = process("质能方程 \$E = mc^2\$ 很有名")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
        val link = Regex("""!\[]\((aicode-math-inline://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("E = mc^2", latex)
        assertFalse(block)
    }

    @Test
    fun `block math becomes block math image link`() {
        val out = process("公式：\n\$\$\\int_a^b f(x)dx\$\$\n完")
        val link = Regex("""!\[]\((aicode-math-block://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("\\int_a^b f(x)dx", latex)
        assertTrue(block)
    }

    @Test
    fun `math inside code fence is not extracted`() {
        val src = "```\ncost = \$5 and \$10\n```"
        assertEquals(src, process(src))
    }

    @Test
    fun `math inside inline code is not extracted`() {
        val out = process("行内 `\$x\$` 不处理")
        assertTrue(out.contains("`\$x\$`"))
        assertFalse(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
    }

    @Test
    fun `latex paren inline math becomes math image link`() {
        val out = process("""令 \(S=a+b+c\)。算法只需枚举较小的因子 \(b\)：""")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_INLINE_SCHEME))
        val matches = Regex("""!\[]\((aicode-math-inline://[^)]+)\)""").findAll(out).toList()
        assertEquals(2, matches.size)
        val first = MarkdownPreprocessor.decodeMathLink(matches[0].groupValues[1])!!
        assertEquals("S=a+b+c", first.first)
        assertFalse(first.second)
        val second = MarkdownPreprocessor.decodeMathLink(matches[1].groupValues[1])!!
        assertEquals("b", second.first)
        assertFalse(second.second)
    }

    @Test
    fun `latex bracket block math becomes block math image link`() {
        val out = process("""公式：\[\sum_{i=1}^n i = \frac{n(n+1)}{2}\]完""")
        assertTrue(out.contains(MarkdownPreprocessor.MATH_BLOCK_SCHEME))
        val link = Regex("""!\[]\((aicode-math-block://[^)]+)\)""").find(out)!!.groupValues[1]
        val (latex, block) = MarkdownPreprocessor.decodeMathLink(link)!!
        assertEquals("""\sum_{i=1}^n i = \frac{n(n+1)}{2}""", latex)
        assertTrue(block)
    }

    @Test
    fun `html tags are kept as literal text`() {
        assertEquals("<\u200Bb>粗<\u200B/b>", process("<b>粗</b>"))
        assertEquals("<\u200Bi>斜<\u200B/i>", process("<i>斜</i>"))
        assertEquals("<\u200Bstrong>强<\u200B/strong>", process("<strong>强</strong>"))
    }

    @Test
    fun `br tag is kept as literal text`() {
        assertEquals("第一行<\u200Bbr>第二行", process("第一行<br>第二行"))
    }

    @Test
    fun `sub and sup tags are kept as literal text`() {
        assertEquals("H<\u200Bsub>2<\u200B/sub>O", process("H<sub>2</sub>O"))
        assertEquals("mv<\u200Bsup>2<\u200B/sup>/2", process("mv<sup>2</sup>/2"))
    }

    @Test
    fun `inline tags are kept as literal text`() {
        assertEquals(
            "<\u200Bspan style=\"color:red\">红色文字<\u200B/span>",
            process("""<span style="color:red">红色文字</span>"""),
        )
    }

    @Test
    fun `block tags are kept as literal text`() {
        assertEquals("<\u200Bhr>", process("<hr>"))
        assertEquals("<\u200Bul><\u200Bli>一<\u200B/li><\u200Bli>二<\u200B/li><\u200B/ul>", process("<ul><li>一</li><li>二</li></ul>"))
        assertEquals("<\u200Bblockquote>引用内容<\u200B/blockquote>", process("<blockquote>引用内容</blockquote>"))
        assertEquals("<\u200Bimg src=\"test.png\" alt=\"测试图\">", process("""<img src="test.png" alt="测试图">"""))
        assertEquals("<\u200Ba href=\"https://x.com\">点这<\u200B/a>", process("""<a href="https://x.com">点这</a>"""))
    }

    @Test
    fun `html entities are decoded`() {
        // `&lt;` 先被解码为 `<`，再被 neutralizeHtmlOpenBracket 插入零宽空格
        assertEquals("a <\u200B b & c > d", process("a &lt; b &amp; c &gt; d"))
    }

    @Test
    fun `html tags inside code fence are untouched`() {
        val src = "```html\n<b>not bold</b>\n```"
        assertEquals(src, process(src))
    }

    @Test
    fun `streaming preprocessing does not add cache entries`() {
        val before = MarkdownPreprocessor.cachedEntryCount()
        repeat(32) { index ->
            assertEquals("stream-$index & done", MarkdownPreprocessor.process("stream-$index &amp; done", cacheEnabled = false))
        }
        assertEquals(before, MarkdownPreprocessor.cachedEntryCount())
    }

    @Test
    fun `oversized static preprocessing still returns complete content`() {
        val raw = "<" + "x".repeat(MarkdownPreprocessor.CACHE_MAX_ITEM_WEIGHT) + " &amp; end"
        val before = MarkdownPreprocessor.cachedEntryCount()
        val result = process(raw)
        assertEquals("<\u200B" + "x".repeat(MarkdownPreprocessor.CACHE_MAX_ITEM_WEIGHT) + " & end", result)
        assertEquals(before, MarkdownPreprocessor.cachedEntryCount())
    }

    @Test
    fun `cache entry limit evicts least recently used item`() {
        val cache = WeightedLruCache<String, String>(2, 100, 100) { key, value -> key.length + value.length }
        cache.put("a", "one")
        cache.put("b", "two")
        assertEquals("one", cache.get("a"))
        cache.put("c", "three")
        assertNull(cache.get("b"))
        assertEquals("one", cache.get("a"))
        assertEquals("three", cache.get("c"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `cache accepts exact weight boundary then evicts on overflow`() {
        val cache = WeightedLruCache<String, String>(10, 10, 6) { key, value -> key.length + value.length }
        cache.put("a", "12345")
        cache.put("b", "123")
        assertEquals(2, cache.size())
        cache.put("c", "x")
        assertNull(cache.get("a"))
        assertEquals("123", cache.get("b"))
        assertEquals("x", cache.get("c"))
    }

    @Test
    fun `cache rejects oversized items without evicting useful content`() {
        val cache = WeightedLruCache<String, String>(10, 10, 6) { key, value -> key.length + value.length }
        cache.put("a", "12345")
        cache.put("b", "123456")
        assertEquals(1, cache.size())
        assertNull(cache.get("b"))
        assertEquals("12345", cache.get("a"))
    }

    @Test
    fun `cache replacement subtracts previous weight`() {
        val cache = WeightedLruCache<String, String>(10, 10, 10) { key, value -> key.length + value.length }
        cache.put("a", "12345678")
        cache.put("a", "x")
        cache.put("b", "1234567")
        assertEquals(2, cache.size())
        assertEquals("x", cache.get("a"))
        assertEquals("1234567", cache.get("b"))
    }

    @Test
    fun `rolling preview refreshes at fixed character limit`() {
        val old = "a".repeat(STREAMING_PREVIEW_CHARS)
        assertTrue(shouldRefreshStreamingText(old, old.length, old.hashCode()))
        assertTrue(shouldRefreshStreamingText(old.drop(1) + "b", old.length, old.hashCode()))
        assertTrue(shouldRefreshStreamingText(old + "b", old.length, old.hashCode()))
    }

    @Test
    fun `short stream appends animate while replacements refresh`() {
        val old = "first"
        assertFalse(shouldRefreshStreamingText("first second", old.length, old.hashCode()))
        assertTrue(shouldRefreshStreamingText("other second", old.length, old.hashCode()))
        assertTrue(shouldRefreshStreamingText("fi", old.length, old.hashCode()))
        assertFalse(shouldRefreshStreamingText("first", 0, 0))
    }
}
