package com.aharou.feature.agent.domain.subagent

import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AgentTeamParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    /** 解析器契约里的路径是容器路径（POSIX 分隔符），Windows 上要先归一化，理由同 AgentDefinitionParserTest。 */
    private fun parseFile(file: File): AgentTeam? =
        AgentTeamParser.parse(provider, file.absolutePath.replace(File.separatorChar, '/'))

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_singleAndParallelStages() {
        val file = write(
            "delivery.md",
            """
            ---
            name: 交付团队
            description: 拆解 → 实现 → 审查
            stages:
              - title: 拆解
                agent: Senior Project Manager
                prompt: 拆成子任务
              - title: 实现
                tasks:
                  - agent: Mobile App Builder
                    prompt: 实现界面
                    writePaths: [app/src/main/java/ui]
                  - department: engineering
                    agentQuery: api platform
                    prompt: 实现接口
            ---
            团队公约：不扩大范围。
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals("交付团队", team.name)
        assertEquals("拆解 → 实现 → 审查", team.description)
        assertEquals("团队公约：不扩大范围。", team.charter)
        assertEquals(2, team.stages.size)
        assertEquals(3, team.taskCount)

        val first = team.stages[0]
        assertEquals("拆解", first.title)
        assertEquals("Senior Project Manager", first.tasks.single().agent)

        val parallel = team.stages[1]
        assertEquals(2, parallel.tasks.size)
        assertEquals(listOf("app/src/main/java/ui"), parallel.tasks[0].writePaths)
        assertEquals("engineering", parallel.tasks[1].department)
        assertEquals("api platform", parallel.tasks[1].agentQuery)
    }

    @Test
    fun parse_dropsTaskWithoutPrompt() {
        val file = write(
            "partial.md",
            """
            ---
            stages:
              - title: 实现
                tasks:
                  - agent: Code Reviewer
                    prompt: 审查改动
                  - agent: Docs Writer
                    prompt: "   "
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        val tasks = team.stages.single().tasks
        assertEquals(1, tasks.size)
        assertEquals("Code Reviewer", tasks.single().agent)
    }

    @Test
    fun parse_dropsStageWithoutValidTasks() {
        val file = write(
            "stage-drop.md",
            """
            ---
            stages:
              - title: 空阶段
                tasks:
                  - agent: Code Reviewer
                    prompt: 审查
              - title: 没人负责
                prompt: 没有 agent 也没有 department
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals(1, team.stages.size)
        assertEquals("空阶段", team.stages.single().title)
    }

    @Test
    fun parse_returnsNullWithoutStages() {
        val file = write(
            "empty.md",
            """
            ---
            name: 没有阶段
            description: 只有描述
            ---
            团队公约。
            """.trimIndent()
        )

        assertNull(parseFile(file))
    }

    @Test
    fun parse_fallsBackToFileStemAsName() {
        val file = write(
            "no-name.md",
            """
            ---
            stages:
              - agent: Explore
                prompt: 查现状
            ---
            """.trimIndent()
        )

        val team = parseFile(file)!!

        assertEquals("no-name", team.name)
        assertTrue(team.charter.isEmpty())
    }
}
