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
        assertTrue(output.contains("- identity-note: identity index"))
        assertTrue(output.indexOf("- regular-0:") < output.indexOf("- identity-note:"))
        assertTrue(output.indexOf("- identity-note:") < output.indexOf("- CORE:"))
        assertFalse(output.contains("- regular-19:"))
        assertTrue(output.length <= MemoryPromptRenderer.MAX_INDEX_CHARS)
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

    private fun memory(
        name: String,
        scope: MemoryScope = MemoryScope.GLOBAL,
        description: String = "summary",
        type: MemoryType = MemoryType.FACT
    ) = Memory(name, description, scope, content = "private body", type = type)
}
