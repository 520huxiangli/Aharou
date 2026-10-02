package com.aharou.feature.agent.domain.subagent

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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentDefinitionTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var configRepository: AgentDefinitionConfigRepository
    private lateinit var fileAccess: FileAccessProvider
    private lateinit var localGlobalRoot: File
    private lateinit var activeGlobalRoot: File
    private lateinit var projectRoot: File

    @Before
    fun setUpConfigRepository() {
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
        every { fileAccess.isFile("~/.aharou/agents.json") } answers {
            File(activeGlobalRoot, "agents.json").isFile
        }
        every { fileAccess.readFile("~/.aharou/agents.json") } answers {
            File(activeGlobalRoot, "agents.json").readText()
        }
        every { fileAccess.writeFile("~/.aharou/agents.json", any(), any(), any()) } answers {
            File(activeGlobalRoot, "agents.json").writeText(secondArg())
        }
        configRepository = AgentDefinitionConfigRepository(installer, projectAharouRoot, hub, fileAccess)
    }

    @Test
    fun disabledNames_followsExecutionEnvironment_withoutMigratingLocalConfig() {
        File(localGlobalRoot, "agents.json").writeText("""{"disabled":["Local"]}""")
        File(activeGlobalRoot, "agents.json").writeText("""{"disabled":["Remote"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["Project"]}""")

        assertEquals(setOf("remote", "project"), configRepository.disabledNames())
        activeGlobalRoot = localGlobalRoot
        assertEquals(setOf("local", "project"), configRepository.disabledNames())
    }

    @Test
    fun setDisabled_global_readsAndWritesActiveBackend_only() {
        val localFile = File(localGlobalRoot, "agents.json")
        localFile.writeText("""{"disabled":["local"]}""")
        File(activeGlobalRoot, "agents.json").writeText("""{"disabled":["Existing"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["project"]}""")

        configRepository.setDisabled("new", true, AgentDefinitionScope.GLOBAL)
        assertEquals(setOf("existing", "new", "project"), configRepository.disabledNames())
        configRepository.setDisabled("existing", false, AgentDefinitionScope.GLOBAL)
        assertEquals(setOf("new", "project"), configRepository.disabledNames())
        assertEquals(setOf("local"), AgentDefinitionConfigRepository.parseDisabled(localFile.readText()))
        assertEquals(
            setOf("project"),
            AgentDefinitionConfigRepository.parseDisabled(File(projectRoot, "agents.json").readText())
        )
        verify(exactly = 2) { fileAccess.writeFile("~/.aharou/agents.json", any(), any(), any()) }
    }

    @Test
    fun setDisabled_project_keepsLocalProjectRoot_withoutBackendAccess() {
        val file = File(projectRoot, "agents.json")
        configRepository.setDisabled("Project", true, AgentDefinitionScope.PROJECT)
        assertEquals(setOf("Project"), AgentDefinitionConfigRepository.parseDisabled(file.readText()))
        configRepository.setDisabled("project", false, AgentDefinitionScope.PROJECT)
        assertTrue(AgentDefinitionConfigRepository.parseDisabled(file.readText()).isEmpty())
        verify { fileAccess wasNot Called }
    }

    @Test
    fun disabledNames_missingOrCorruptedRemoteConfig_doesNotFallBackToLocal() {
        File(localGlobalRoot, "agents.json").writeText("""{"disabled":["local"]}""")
        File(projectRoot, "agents.json").writeText("""{"disabled":["project"]}""")
        assertEquals(setOf("project"), configRepository.disabledNames())
        File(activeGlobalRoot, "agents.json").writeText("{broken")
        assertEquals(setOf("project"), configRepository.disabledNames())
    }

    private val allTools = listOf(
        "readFile", "writeFile", "editFile", "Bash", "terminal", "search",
        "task", "mcp__ctx7__query", "mcp__ctx7__resolve", "mcp__mt__open"
    )

    private fun def(
        allowed: List<String> = emptyList(),
        disallowed: List<String> = emptyList()
    ) = AgentDefinition(
        name = "a",
        description = "",
        allowedTools = allowed,
        disallowedTools = disallowed,
        prompt = "p"
    )

    /** 省略两个名单即继承全量工具，但 task 永远剔除（子代理不能嵌套派发）。 */
    @Test
    fun filter_emptyListsInheritAllExceptTask() {
        assertEquals(allTools - "task", def().filterToolNames(allTools))
    }

    @Test
    fun filter_allowlistKeepsOnlyListed() {
        assertEquals(
            listOf("readFile", "search"),
            def(allowed = listOf("readFile", "search")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_denylistRemovesListed() {
        val result = def(disallowed = listOf("Bash", "terminal")).filterToolNames(allTools)

        assertEquals(allTools - "task" - "Bash" - "terminal", result)
    }

    /** 黑名单先生效，两名单都命中的工具最终被移除。 */
    @Test
    fun filter_denyWinsOverAllow() {
        val result = def(
            allowed = listOf("readFile", "Bash"),
            disallowed = listOf("Bash")
        ).filterToolNames(allTools)

        assertEquals(listOf("readFile"), result)
    }

    @Test
    fun filter_wildcardMatchesMcpServerPrefix() {
        val result = def(disallowed = listOf("mcp__ctx7__*")).filterToolNames(allTools)

        assertEquals(
            listOf("readFile", "writeFile", "editFile", "Bash", "terminal", "search", "mcp__mt__open"),
            result
        )
    }

    @Test
    fun filter_allowlistCannotReintroduceTask() {
        assertEquals(
            listOf("readFile"),
            def(allowed = listOf("readFile", "task")).filterToolNames(allTools)
        )
    }

    @Test
    fun filter_toolNameMatchIsCaseInsensitive() {
        assertEquals(listOf("Bash"), def(allowed = listOf("bash")).filterToolNames(allTools))
    }

    /** 同名定义项目级覆盖全局，结果按名称排序。 */
    @Test
    fun mergeAll_projectOverridesGlobal() {
        val global = listOf(
            AgentDefinition(name = "researcher", description = "global", prompt = "g"),
            AgentDefinition(name = "coder", description = "global", prompt = "g")
        )
        val project = listOf(
            AgentDefinition(name = "researcher", description = "project", prompt = "p")
        )

        val merged = AgentDefinitionRepository.mergeAll(global, project)

        assertEquals(listOf("coder", "researcher"), merged.map { it.definition.name })
        val researcher = merged.first { it.definition.name == "researcher" }
        assertEquals(AgentDefinitionScope.PROJECT, researcher.scope)
        assertEquals("project", researcher.definition.description)
        assertEquals(
            AgentDefinitionScope.GLOBAL,
            merged.first { it.definition.name == "coder" }.scope
        )
    }
}
