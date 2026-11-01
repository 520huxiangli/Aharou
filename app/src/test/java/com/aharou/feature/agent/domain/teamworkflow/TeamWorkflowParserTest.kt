package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.testutil.TestFileAccessProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TeamWorkflowParserTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val provider = TestFileAccessProvider()

    /** 解析器契约里的路径是容器路径（POSIX 分隔符），Windows 上要先归一化，理由同 AgentTeamParserTest。 */
    private fun parseFile(file: File): TeamWorkflow? =
        TeamWorkflowParser.parse(provider, file.absolutePath.replace(File.separatorChar, '/'))

    private fun write(name: String, content: String): File =
        File(tempFolder.root, name).apply { writeText(content) }

    @Test
    fun parse_teamChainWithShorthandAndFullForm() {
        val file = write(
            "delivery-flow.md",
            """
            ---
            name: 研发工作流
            description: 需求 → 架构 → 实现
            maxRejectRounds: 3
            teams:
              - 需求分析团队
              - team: 架构设计团队
                acceptsReject: true
                lead: 系统架构师
              - team: 实现团队
                acceptsReject: false
            ---
            工作流公约：跨团队只认已落盘的事实。
            """.trimIndent()
        )

        val workflow = parseFile(file)!!

        assertEquals("研发工作流", workflow.name)
        assertEquals("需求 → 架构 → 实现", workflow.description)
        assertEquals("工作流公约：跨团队只认已落盘的事实。", workflow.charter)
        assertEquals(3, workflow.layerCount)
        assertEquals(3, workflow.maxRejectRounds)

        assertEquals("需求分析团队", workflow.teams[0].teamName)
        assertTrue(workflow.teams[0].acceptsReject)
        assertNull(workflow.teams[0].lead)

        assertEquals("系统架构师", workflow.teams[1].lead)

        assertEquals("实现团队", workflow.teams[2].teamName)
        assertFalse(workflow.teams[2].acceptsReject)
    }

    @Test
    fun parse_singleTeamChainIsRejected() {
        val file = write(
            "solo.md",
            """
            ---
            name: 单团队
            teams:
              - 需求分析团队
            ---
            """.trimIndent()
        )

        assertNull(parseFile(file))
    }

    @Test
    fun parse_skipsDuplicateAndBlankLayers() {
        val file = write(
            "dup.md",
            """
            ---
            name: 重复链
            teams:
              - 需求分析团队
              - team: 需求分析团队
                lead: 重复的
              - team: 架构设计团队
              - ""
            ---
            """.trimIndent()
        )

        val workflow = parseFile(file)!!

        assertEquals(2, workflow.layerCount)
        assertEquals("需求分析团队", workflow.teams[0].teamName)
        assertNull(workflow.teams[0].lead)
        assertEquals("架构设计团队", workflow.teams[1].teamName)
    }

    @Test
    fun parse_clampsRejectRounds() {
        fun roundsOf(value: String): Int = parseFile(
            write(
                "rounds-$value.md",
                """
                ---
                name: 轮次
                maxRejectRounds: $value
                teams:
                  - 甲团队
                  - 乙团队
                ---
                """.trimIndent()
            )
        )!!.maxRejectRounds

        assertEquals(TeamWorkflow.MAX_REJECT_ROUNDS_CAP, roundsOf("99"))
        assertEquals(1, roundsOf("0"))
        assertEquals(TeamWorkflow.DEFAULT_MAX_REJECT_ROUNDS, roundsOf("abc"))
    }

    @Test
    fun serialize_roundTripsThroughParse() {
        val form = TeamWorkflowForm(
            name = "研发工作流",
            description = "需求 → 架构",
            charter = "工作流公约：不猜，缺信息先问。",
            maxRejectRounds = "4",
            teams = listOf(
                WorkflowTeamRefForm(team = "需求分析团队"),
                WorkflowTeamRefForm(team = "架构设计团队", acceptsReject = false, lead = "系统架构师")
            )
        )

        val text = TeamWorkflowParser.serialize(form)
        val parsed = parseFile(write("roundtrip.md", text))!!

        assertEquals("研发工作流", parsed.name)
        assertEquals(2, parsed.layerCount)
        assertEquals(4, parsed.maxRejectRounds)
        assertEquals("工作流公约：不猜，缺信息先问。", parsed.charter)
        assertEquals("系统架构师", parsed.teams[1].lead)
        assertFalse(parsed.teams[1].acceptsReject)

        // 回填表单要与原表单一致，否则编辑一次就悄悄改掉用户的设置。
        assertEquals(form, parsed.toForm(AgentDefinitionScope.GLOBAL))
    }
}
