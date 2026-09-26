package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 思考折叠行的单行预览：思考进行中取最后一行（跟着滚动），结束后取第一行；
 * 两种情况下都清掉行首的 Markdown 记号，内容为空时返回空串（调用方回落「思考过程」文案）。
 */
class ReasoningPreviewTest {

    @Test
    fun live_takesLastLine() {
        val raw = "先看目录结构\n再确认入口文件\n正在读 ChatMessageStyle.kt"
        assertEquals("正在读 ChatMessageStyle.kt", reasoningPreviewLine(raw, live = true))
    }

    @Test
    fun live_ignoresTrailingNewline() {
        // 模型刚换行、还没写下一个字：仍然显示上一行，而不是空
        assertEquals("第二行", reasoningPreviewLine("第一行\n第二行\n", live = true))
    }

    @Test
    fun finished_takesFirstNonBlankLine() {
        val raw = "\n## 分析\n后面还有很多内容"
        assertEquals("分析", reasoningPreviewLine(raw, live = false))
    }

    @Test
    fun stripsLeadingMarkdownMarkers() {
        assertEquals("标题", reasoningPreviewLine("# 标题", live = false))
        assertEquals("要点", reasoningPreviewLine("- **要点**", live = false))
        assertEquals("引用", reasoningPreviewLine("> 引用", live = false))
        assertEquals("代码", reasoningPreviewLine("`代码`", live = false))
    }

    @Test
    fun blankText_returnsEmpty() {
        assertEquals("", reasoningPreviewLine("", live = true))
        assertEquals("", reasoningPreviewLine("   \n  ", live = false))
    }
}
