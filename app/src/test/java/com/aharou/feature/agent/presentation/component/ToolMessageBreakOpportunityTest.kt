package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具结果的「无断点长串」补断点逻辑。
 *
 * 背景：工具输出常是压缩 JSON 或长路径（整条既无换行也无空格），Android 断行器在这种文本上
 * 要扫到行尾才能定断点，开销接近 O(n²)；补上零宽空格后断行可就近完成。
 */
class ToolMessageBreakOpportunityTest {

    @Test
    fun `long ascii run gets zero-width break points`() {
        val raw = "x".repeat(500)
        val out = withBreakOpportunities(raw)

        assertTrue("长串里应插入断点", out.contains('\u200B'))
        assertEquals("除断点外内容不变", raw, out.replace("\u200B", ""))
        assertTrue("没有连续超长片段存留", out.split('\u200B').all { it.length <= 48 })
    }

    @Test
    fun `short text is untouched`() {
        val raw = "done"
        assertEquals(raw, withBreakOpportunities(raw))
    }

    @Test
    fun `whitespace breaks the run so nothing is inserted`() {
        val spaced = "word word word word word word"
        assertEquals(spaced, withBreakOpportunities(spaced))
    }

    @Test
    fun `cjk text needs no break points`() {
        val cjk = "这是一段没有空格的中文文本，用来确认中文逐字可断、不需要补零宽空格。".repeat(6)
        assertFalse("中文不该被插断点", withBreakOpportunities(cjk).contains('\u200B'))
    }

    @Test
    fun `is idempotent`() {
        val raw = "a".repeat(300)
        val once = withBreakOpportunities(raw)
        assertEquals(once, withBreakOpportunities(once))
    }
}
