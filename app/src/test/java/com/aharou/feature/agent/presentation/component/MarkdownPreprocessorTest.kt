package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `bold italic strike tags map to markdown`() {
        assertEquals("**粗**", process("<b>粗</b>"))
        assertEquals("*斜*", process("<i>斜</i>"))
        assertEquals("~~删~~", process("<s>删</s>"))
        assertEquals("**强**", process("<strong>强</strong>"))
    }

    @Test
    fun `br maps to hard break`() {
        assertEquals("第一行  \n第二行", process("第一行<br>第二行"))
    }

    @Test
    fun `sub and sup map to unicode scripts`() {
        assertEquals("H\u2082O", process("H<sub>2</sub>O"))
        assertEquals("mv\u00B2/2", process("mv<sup>2</sup>/2"))
        assertEquals("x\u207F", process("x<sup>n</sup>"))
    }

    @Test
    fun `unmappable script content keeps raw text`() {
        assertEquals("xyz", process("<sub>xyz</sub>"))
    }

    @Test
    fun `unknown inline tags keep text content`() {
        assertEquals("红色文字", process("""<span style="color:red">红色文字</span>"""))
    }

    @Test
    fun `unordered html list maps to markdown list`() {
        val out = process("<ul><li>一</li><li>二</li></ul>")
        assertTrue(out.contains("- 一"))
        assertTrue(out.contains("- 二"))
    }

    @Test
    fun `ordered html list maps to numbered list`() {
        val out = process("<ol><li>甲</li><li>乙</li></ol>")
        assertTrue(out.contains("1. 甲"))
        assertTrue(out.contains("2. 乙"))
    }

    @Test
    fun `html table maps to gfm table`() {
        val out = process("<table><tr><th>名</th><th>龄</th></tr><tr><td>张</td><td>18</td></tr></table>")
        assertTrue(out.contains("| 名 | 龄 |"))
        assertTrue(out.contains("| --- | --- |"))
        assertTrue(out.contains("| 张 | 18 |"))
    }

    @Test
    fun `blockquote maps to markdown quote`() {
        val out = process("<blockquote>引用内容</blockquote>")
        assertTrue(out.contains("> 引用内容"))
    }

    @Test
    fun `hr maps to divider`() {
        assertTrue(process("<hr>").contains("---"))
    }

    @Test
    fun `img tag maps to markdown image`() {
        assertEquals("![测试图](test.png)", process("""<img src="test.png" alt="测试图">"""))
    }

    @Test
    fun `anchor maps to markdown link`() {
        assertEquals("[点这](https://x.com)", process("""<a href="https://x.com">点这</a>"""))
    }

    @Test
    fun `html entities are decoded`() {
        assertEquals("a < b & c > d", process("a &lt; b &amp; c &gt; d"))
    }

    @Test
    fun `html tags inside code fence are untouched`() {
        val src = "```html\n<b>not bold</b>\n```"
        assertEquals(src, process(src))
    }
}
