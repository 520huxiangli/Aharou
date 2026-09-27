package com.aharou.feature.agent.presentation.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LongMessageSplitTest {

    @Test
    fun `code fence longer than budget remains intact and is not sliced`() {
        val longCode = buildString {
            appendLine("```html")
            repeat(100) { i ->
                appendLine("  <div class=\"item-$i\">content line $i</div>")
            }
            appendLine("```")
        }
        assertTrue(longCode.length > 2000)

        val fullText = "下面是代码：\n\n$longCode\n\n请查收。"
        val slices = splitLongContent(fullText)

        // 验证代码块被作为一个完整单元，绝不会被切碎成不带围栏的碎片
        val codeSlice = slices.firstOrNull { it.trim().startsWith("```html") }
        assertTrue("必须包含以 ```html 开头的 chunk", codeSlice != null)
        assertTrue("该 chunk 必须以 ``` 闭合", codeSlice!!.trimEnd().endsWith("```"))
        assertTrue("该 chunk 必须包含完整的全部 100 行", codeSlice.contains("item-0") && codeSlice.contains("item-99"))
    }

    @Test
    fun `tilde code fence is also preserved intact`() {
        val longCode = buildString {
            appendLine("~~~python")
            repeat(80) { i ->
                appendLine("def func_$i(): pass")
            }
            appendLine("~~~")
        }
        val slices = splitLongContent(longCode)
        assertEquals(1, slices.size)
        assertTrue(slices[0].trim().startsWith("~~~python"))
        assertTrue(slices[0].trimEnd().endsWith("~~~"))
    }

    @Test
    fun `huge normal text block is sliced by lines to stay bounded`() {
        val hugeParagraph = buildString {
            repeat(50) { i ->
                appendLine("这是普通段落的第 $i 行，内容较长用于测试预算切分。")
            }
        }
        val slices = splitLongContent(hugeParagraph)
        assertTrue("超长段落应被切分为多个 chunks", slices.size > 1)
    }
}
