package com.aharou.feature.agent.domain.prompt

import com.aharou.feature.agent.domain.memory.Memory
import com.aharou.feature.agent.domain.memory.MemoryScope
import com.aharou.feature.agent.domain.memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptRendererTest {
    @Test
    fun emptyIndexIsEmpty() {
        assertEquals("", MemoryPromptRenderer.render(emptyList()))
    }

    @Test
    fun smallIndexPreservesGroupingAndInputOrder() {
        val memories = listOf(
            memory("project-first", MemoryScope.PROJECT),
            memory("global-first"),
            memory("project-second", MemoryScope.PROJECT),
            memory("global-second")
        )

        val output = MemoryPromptRenderer.render(memories)

        assertTrue(output.indexOf("全局记忆") < output.indexOf("项目记忆"))
        assertTrue(output.indexOf("- global-first:") < output.indexOf("- global-second:"))
        assertTrue(output.indexOf("- project-first:") < output.indexOf("- project-second:"))
        assertTrue(output.contains("scope=global"))
        assertTrue(output.contains("scope=project"))
        assertFalse(output.contains("总预算"))
        assertFalse(output.contains("private body"))
    }

    @Test
    fun exactlyAtBudgetDoesNotOmit() {
        val entry = memory("boundary", description = "x")
        val overhead = MemoryPromptRenderer.render(listOf(entry)).length - 1
        val exact = entry.copy(description = "x".repeat(MemoryPromptRenderer.MAX_INDEX_CHARS - overhead))

        val output = MemoryPromptRenderer.render(listOf(exact))

        assertEquals(MemoryPromptRenderer.MAX_INDEX_CHARS, output.length)
        assertTrue(output.endsWith(exact.description))
        assertFalse(output.contains("总预算"))
    }

    @Test
    fun combinedScopesIncludeHeadersAndOmissionWithinBudget() {
        val memories = (0 until 60).map {
            memory(
                "entry-$it",
                if (it % 2 == 0) MemoryScope.GLOBAL else MemoryScope.PROJECT,
                "x".repeat(500)
            )
        }

        val output = MemoryPromptRenderer.render(memories)

        assertTrue(output.length <= MemoryPromptRenderer.MAX_INDEX_CHARS)
        assertTrue(output.contains("全局记忆"))
        assertTrue(output.contains("项目记忆"))
        assertTrue(output.contains("8000 字符总预算"))
        assertTrue(output.contains("memory(action=list)"))
        assertTrue(output.contains("memory(action=read"))
        assertTrue(output.contains("x".repeat(500)))
        assertFalse(output.contains("- entry-59:"))
        assertEquals(output, MemoryPromptRenderer.render(memories))
    }

    @Test
    fun coreEntriesHavePriorityWithoutReorderingVisibleEntries() {
        val regular = (0 until 20).map { memory("regular-$it", description = "x".repeat(500)) }
        val core = memory("CORE", MemoryScope.PROJECT, "core index")
        val identity = memory("identity-note", description = "identity index", type = MemoryType.IDENTITY)
        val output = MemoryPromptRenderer.render(regular + core + identity)

        assertTrue(output.contains("- CORE: core index"))
        // identity 类走常驻正文段（名字、描述、正文都在），不再占索引条目。
        assertTrue(output.contains("identity index"))
        assertFalse(output.contains("- identity-note:"))
        assertTrue(output.indexOf("- regular-0:") < output.indexOf("- CORE:"))
        // 以下断的是索引预算：普通条目被吃满后末尾被省略，核心档案不受影响。
        // 整体长度不在这里断——render 返回的是「常驻正文段 + 摘要索引」，内联段另有预算
        // （MAX_INLINE_CHARS 为 private），索引部分由 exactlyAtBudgetDoesNotOmit 盯着。
        assertFalse(output.contains("- regular-19:"))
    }

    @Test
    fun pendingEntryIsLabeledAndItsBodyIsNotInlined() {
        // 待确认条目是「同一件事的两种说法并存、等用户拍板」的中间态：名字要留在清单里
        // （静默隐藏等于把分歧抹掉），但不能内联正文——那是已确认事实才有的待遇。
        val pendingNote = memory("pending-note", type = MemoryType.IDENTITY, pending = true)
        val output = MemoryPromptRenderer.render(listOf(pendingNote, memory("normal")))

        assertTrue(output.contains("- pending-note: summary"))
        assertTrue(output.contains("待确认"))
        assertTrue(output.contains("不得当作既定事实"))
        assertFalse(output.contains("private body"))
        assertTrue(output.contains("- normal: summary"))
    }

    @Test
    fun pendingEntryDoesNotSuppressOtherInlineBodies() {
        val pendingNote = memory("pending-note", type = MemoryType.IDENTITY, pending = true)
        val confirmed = memory("confirmed-note", type = MemoryType.IDENTITY, description = "confirmed index")

        val output = MemoryPromptRenderer.render(listOf(pendingNote, confirmed))

        assertTrue(output.contains("confirmed index"))
        assertTrue(output.contains("private body"))
        assertTrue(output.contains("- pending-note: summary（待确认"))
    }

    @Test
    fun longBodyFallsBackToIndexWholeInsteadOfBeingTruncated() {
        // 截断出来的半截正文模型读不懂，白占常驻预算；整条退回索引并在行里点明全文可取。
        val body = "x".repeat(1_500)
        val long = Memory("long-note", "long index", MemoryScope.PROJECT, content = body, type = MemoryType.IDENTITY)
        val short = memory("short-note", type = MemoryType.IDENTITY)

        val output = MemoryPromptRenderer.render(listOf(long, short))

        assertFalse(output.contains(body))
        assertFalse(output.contains("x".repeat(900)))
        assertTrue(output.contains("- long-note: long index（正文较长未内联"))
        assertTrue(output.contains("memory(action=read)"))
        // 超长条目不能两边都丢：进索引，且不挡后面的条目内联。
        assertTrue(output.contains("### short-note"))
        assertTrue(output.contains("private body"))
    }

    @Test
    fun lowercaseNamesAreNotMistakenForCoreArchives() {
        val regular = (0 until 20).map { memory("regular-$it", description = "x".repeat(500)) }
        val output = MemoryPromptRenderer.render(regular + memory("lowercase_name", description = "x".repeat(500)))

        assertTrue(output.contains("- regular-0:"))
        assertFalse(output.contains("- lowercase_name:"))
    }

    @Test
    fun oversizedEntryIsOmittedWholeAndDoesNotBlockLaterEntries() {
        val oversized = memory("oversized-" + "n".repeat(8_000), description = "whole description")
        val output = MemoryPromptRenderer.render(listOf(oversized, memory("small")))

        assertFalse(output.contains("oversized-"))
        assertFalse(output.contains("whole description"))
        assertTrue(output.contains("- small: summary"))
        assertTrue(output.contains("总预算"))
        assertTrue(output.length <= MemoryPromptRenderer.MAX_INDEX_CHARS)
    }

    @Test
    fun noEntryFitsStillIncludesCompleteOmissionNotice() {
        val output = MemoryPromptRenderer.render(listOf(memory("n".repeat(8_000))))

        assertFalse(output.contains("- "))
        assertTrue(output.contains("memory(action=list)"))
        assertTrue(output.contains("scope=..."))
        assertTrue(output.length <= MemoryPromptRenderer.MAX_INDEX_CHARS)
    }

    @Test
    fun blankDescriptionKeepsExistingFallback() {
        val output = MemoryPromptRenderer.render(listOf(memory("CORE", description = "")))

        assertTrue(output.contains("- CORE: 无"))
    }

    @Test
    fun repeatedVariablesExpandIndexOnlyOnce() {
        val variable = "{{AICODE_MEMORY}}"
        val index = MemoryPromptRenderer.render(listOf(memory("once")))

        val output = MemoryPromptRenderer.expandOnce("before $variable middle $variable after", variable, index)

        assertEquals("before $index middle  after", output)
        assertFalse(output.contains(variable))
        assertEquals("unrelated {{INSTRUCTION}}", MemoryPromptRenderer.expandOnce("unrelated {{INSTRUCTION}}", variable, index))
        assertEquals("ab", MemoryPromptRenderer.expandOnce("a${variable}b$variable", variable, null))
    }

    @Test
    fun indexAndInlineNameGoThroughMasking() {
        // name 来自 frontmatter 自由文本（容器内可写），与正文同属不可信输入，口径必须一致
        val leaking = Memory(
            "leak-sk-ant-api03-abcdefghijklmnop",
            "摘要",
            MemoryScope.GLOBAL,
            content = "正文",
            type = MemoryType.IDENTITY
        )

        val output = MemoryPromptRenderer.render(listOf(leaking))

        assertFalse(output.contains("sk-ant-api03-abcdefghijklmnop"))
        assertTrue(output.contains("[已脱敏: sk-a…"))
    }

    @Test
    fun inlineBudgetSkipDoesNotDropLaterShortEntries() {
        // 攒满内联预算后遇到撑不下的条目应该跳过它继续试，不能把后面塞得下的短条目一起牺牲
        val fat = (1..4).map {
            Memory("fat-$it", "摘要", MemoryScope.GLOBAL, content = "A".repeat(880), type = MemoryType.IDENTITY)
        }
        val slim = Memory("slim", "摘要", MemoryScope.GLOBAL, content = "SHORT-BODY", type = MemoryType.IDENTITY)

        val output = MemoryPromptRenderer.render(fat + slim)

        assertTrue(output.contains("SHORT-BODY"))
    }

    private fun memory(
        name: String,
        scope: MemoryScope = MemoryScope.GLOBAL,
        description: String = "summary",
        type: MemoryType = MemoryType.FACT,
        pending: Boolean = false
    ) = Memory(name, description, scope, content = "private body", type = type, pending = pending)
}
