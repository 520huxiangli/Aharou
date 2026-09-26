package com.aharou.feature.agent.domain.skill

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillConfigRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun serialize_parse_roundtrip() {
        val json = SkillConfigRepository.serializeDisabled(setOf("b-skill", "a-skill"))
        val parsed = SkillConfigRepository.parseDisabled(json)

        assertEquals(setOf("a-skill", "b-skill"), parsed)
    }

    @Test
    fun parseDisabled_emptyJson_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("""{"disabled":[]}""").isEmpty())
        assertTrue(SkillConfigRepository.parseDisabled("").isEmpty())
    }

    @Test
    fun parseDisabled_corruptedJson_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("{not valid json!!").isEmpty())
    }

    @Test
    fun parseDisabled_missingField_returnsEmpty() {
        assertTrue(SkillConfigRepository.parseDisabled("""{"other":1}""").isEmpty())
    }

    @Test
    fun readDisabled_missingFile_returnsEmpty() {
        assertTrue(SkillConfigRepository.readDisabled(File(tempFolder.root, "not-exists.json")).isEmpty())
    }

    @Test
    fun writeAndReadDisabled_persists() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("plotrail", "prototype"))
        val read = SkillConfigRepository.readDisabled(file)

        assertEquals(setOf("plotrail", "prototype"), read)
    }

    @Test
    fun writeDisabled_overwritesPrevious() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("a"))
        SkillConfigRepository.writeDisabled(file, setOf("b"))

        assertEquals(setOf("b"), SkillConfigRepository.readDisabled(file))
    }

    @Test
    fun writeDisabled_noTempLeftover() {
        val file = File(tempFolder.root, "skills.json")

        SkillConfigRepository.writeDisabled(file, setOf("a"))

        assertTrue(File(tempFolder.root, "skills.json.tmp").let { !it.exists() })
    }
}
