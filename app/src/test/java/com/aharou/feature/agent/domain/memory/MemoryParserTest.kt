package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MemoryParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun format_and_parse_roundtrip() {
        val name = "user_preference"
        val description = "User prefers dark mode and concise answers"
        val content = "# User Preferences\n- Dark mode: true\n- Conciseness: high"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("user_preference.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertTrue(memory != null)
        assertEquals("user_preference", memory?.name)
        assertEquals("User prefers dark mode and concise answers", memory?.description)
        assertEquals(MemoryScope.GLOBAL, memory?.scope)
        assertEquals(content.trim(), memory?.content)
    }

    @Test
    fun format_escapesSpecialCharactersInYaml() {
        val name = "special:key#name"
        val description = "Description with \"quotes\" and : colons"
        val content = "Memory body"

        val formatted = MemoryParser.format(name, description, content)
        val file = tempFolder.newFile("special.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.PROJECT)
        assertTrue(memory != null)
        assertEquals("special:key#name", memory?.name)
        assertEquals("Description with \"quotes\" and : colons", memory?.description)
        assertEquals("Memory body", memory?.content)
    }

    @Test
    fun format_then_parse_keeps_pending_flag() {
        val formatted = MemoryParser.format(
            name = "demo",
            description = "一句摘要",
            content = "正文内容",
            type = MemoryType.FACT,
            keywords = listOf("alpha", "beta"),
            pending = true
        )
        val file = tempFolder.newFile("pending.md")
        file.writeText(formatted)

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(memory)
        assertTrue(memory!!.pending)
        assertEquals("demo", memory.name)
        assertEquals("正文内容", memory.content)
    }

    @Test
    fun parse_defaults_pending_to_false_when_absent() {
        val file = tempFolder.newFile("plain.md")
        file.writeText(MemoryParser.format("plain", "摘要", "正文"))

        val memory = MemoryParser.parse(file, MemoryScope.GLOBAL)
        assertNotNull(memory)
        assertFalse(memory!!.pending)
    }

    @Test
    fun sanitizeName_turns_path_separators_into_flat_name() {
        assertEquals("a-b", MemorySource.sanitizeName("a/b"))
        assertFalse(MemorySource.sanitizeName("../escape").contains('/'))
    }
}
