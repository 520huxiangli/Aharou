package com.aharou.feature.agent.domain.skill

import com.aharou.core.watch.FileChangeHub
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.workspace.domain.FileAccessProvider
import com.aharou.feature.workspace.domain.ProjectAharouRoot
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SkillConfigRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var repository: SkillConfigRepository
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var localGlobalRoot: File
    private lateinit var activeGlobalRoot: File
    private lateinit var projectRoot: File

    @Before
    fun setUp() {
        localGlobalRoot = tempFolder.newFolder("local-global")
        activeGlobalRoot = tempFolder.newFolder("remote-global")
        projectRoot = tempFolder.newFolder("project-config")
        val installer = mockk<ContainerInstaller>()
        every { installer.aharouDir } returns localGlobalRoot
        val projectAharouRoot = mockk<ProjectAharouRoot>()
        every { projectAharouRoot.current() } returns projectRoot
        val hub = mockk<FileChangeHub>()
        every { hub.watchAicode(any(), any(), any(), any(), any()) } returns emptyFlow()
        every { hub.watchWorkspace(any(), any(), any(), any(), any()) } returns emptyFlow()
        fileAccess = mockk()
        every { fileAccess.isFile("~/.aharou/skills.json") } answers {
            File(activeGlobalRoot, "skills.json").isFile
        }
        every { fileAccess.readFile("~/.aharou/skills.json") } answers {
            File(activeGlobalRoot, "skills.json").readText()
        }
        every { fileAccess.writeFile("~/.aharou/skills.json", any(), any(), any()) } answers {
            File(activeGlobalRoot, "skills.json").writeText(secondArg())
        }
        repository = SkillConfigRepository(installer, projectAharouRoot, hub, fileAccess)
    }

    @Test
    fun disabledNames_followsExecutionEnvironment_withoutMigratingLocalConfig() {
        File(localGlobalRoot, "skills.json").writeText("""{"disabled":["Local"]}""")
        File(activeGlobalRoot, "skills.json").writeText("""{"disabled":["Remote"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["Project"]}""")

        assertEquals(setOf("remote", "project"), repository.disabledNames())
        activeGlobalRoot = localGlobalRoot
        assertEquals(setOf("local", "project"), repository.disabledNames())
    }

    @Test
    fun setDisabled_global_readsAndWritesActiveBackend_only() {
        val localFile = File(localGlobalRoot, "skills.json")
        localFile.writeText("""{"disabled":["local"]}""")
        File(activeGlobalRoot, "skills.json").writeText("""{"disabled":["existing"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["project"]}""")

        repository.setDisabled("new", true, SkillScope.GLOBAL)
        assertEquals(setOf("existing", "new", "project"), repository.disabledNames())
        repository.setDisabled("existing", false, SkillScope.GLOBAL)
        assertEquals(setOf("new", "project"), repository.disabledNames())
        assertEquals(setOf("local"), SkillConfigRepository.readDisabled(localFile))
        assertEquals(setOf("project"), SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")))
        verify(exactly = 2) { fileAccess.writeFile("~/.aharou/skills.json", any(), any(), any()) }
    }

    @Test
    fun setDisabled_project_keepsLocalProjectRoot_withoutBackendAccess() {
        repository.setDisabled("project", true, SkillScope.PROJECT)
        assertEquals(setOf("project"), SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")))
        repository.setDisabled("project", false, SkillScope.PROJECT)
        assertTrue(SkillConfigRepository.readDisabled(File(projectRoot, "skills.json")).isEmpty())
        verify { fileAccess wasNot Called }
    }

    @Test
    fun disabledNames_missingOrCorruptedRemoteConfig_doesNotFallBackToLocal() {
        File(localGlobalRoot, "skills.json").writeText("""{"disabled":["local"]}""")
        File(projectRoot, "skills.json").writeText("""{"disabled":["project"]}""")
        assertEquals(setOf("project"), repository.disabledNames())
        File(activeGlobalRoot, "skills.json").writeText("{broken")
        assertEquals(setOf("project"), repository.disabledNames())
    }

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
