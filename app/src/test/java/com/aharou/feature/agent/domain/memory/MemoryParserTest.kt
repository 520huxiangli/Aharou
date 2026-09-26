package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
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
}
