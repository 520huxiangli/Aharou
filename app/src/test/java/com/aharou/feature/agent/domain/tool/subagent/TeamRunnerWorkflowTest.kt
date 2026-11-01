package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow
import com.aharou.feature.agent.domain.teamworkflow.WorkflowOrchestrator
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamRunnerWorkflowTest {

    private val workflow = TeamWorkflow(
        name = "研发工作流",
        description = "",
        teams = listOf(
            WorkflowTeamRef(teamName = "需求分析团队"),
            WorkflowTeamRef(teamName = "架构设计团队", lead = "系统架构师"),
            WorkflowTeamRef(teamName = "实现团队", acceptsReject = false)
        ),
        charter = "跨团队只认已落盘的事实。",
        maxRejectRounds = 2
    )

    private val enabled = listOf(
        AgentDefinition(name = "需求分析师", description = "需求", prompt = "p"),
        AgentDefinition(name = "系统架构师", description = "架构", prompt = "p"),
        AgentDefinition(name = "后端工程专家", description = "实现", prompt = "p")
    )

    private fun team(
        name: String,
        agent: String,
        stageTitle: String = "阶段",
        taskPrompt: String = "做这件事"
    ) = AgentTeam(
        name = name,
        description = "",
        stages = listOf(TeamStage(stageTitle, listOf(TeamTask(agent = agent, prompt = taskPrompt)))),
        charter = ""
    )

    private fun JsonArray.objects(): List<JsonObject> = map { it as JsonObject }

    private fun JsonObject.text(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.stringList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().map { (it as JsonPrimitive).content }

    @Test
    fun buildWorkflowLayer_appendsJudgeTaskAfterMembers() {
        val plan = TeamRunner.buildWorkflowLayer(
            workflow = workflow,
            layerIndex = 1,
            ref = workflow.teams[0],
            team = team("需求分析团队", "需求分析师"),
            goal = "把登录改造完",
            upstreamBrief = "（无上游）",
            enabled = enabled,
            maxRunning = 5
        ) as TeamRunner.Plan.Ready

        val tasks = plan.pipeline.objects()
        assertEquals(2, tasks.size)
        assertEquals(2, plan.layers)

        val member = tasks.first { it.text("id") == "w1-s1-b1-t1" }
        assertEquals("需求分析师", member.text("agent"))
        assertTrue(member.text("prompt").contains("（无上游）"))
        assertTrue(member.text("prompt").contains("把登录改造完"))

        // 首层也要有协调者判定：它决定这一层处理完就收尾，还是继续往下。
        val judge = tasks.first { it.text("id") == TeamRunner.judgeId(1) }
        assertTrue(judge.text("prompt").contains(WorkflowOrchestrator.JUDGE_MARKER + " HANDLE"))
        assertTrue(judge.text("prompt").contains("（无上游）"))
        assertTrue(judge.stringList("dependsOn").contains("w1-s1-b1-t1"))
    }

    @Test
    fun buildWorkflowLayer_usesWorkflowLeadAsJudge() {
        val plan = TeamRunner.buildWorkflowLayer(
            workflow = workflow,
            layerIndex = 2,
            ref = workflow.teams[1],
            team = team("架构设计团队", "系统架构师"),
            goal = "把登录改造完",
            upstreamBrief = "需求拆解完毕",
            enabled = enabled,
            maxRunning = 5
        ) as TeamRunner.Plan.Ready

        val judge = plan.pipeline.objects().first { it.text("id") == TeamRunner.judgeId(2) }
        assertEquals("系统架构师", judge.text("agent"))
        // 第二层有上游，允许驳回，模板要出现。
        assertTrue(judge.text("prompt").contains("REJECT | 上游团队·上游专家"))
    }

    @Test
    fun buildWorkflowLayer_rejectsUnknownMemberRole() {
        val plan = TeamRunner.buildWorkflowLayer(
            workflow = workflow,
            layerIndex = 1,
            ref = workflow.teams[0],
            team = team("需求分析团队", "不存在的角色"),
            goal = "g",
            upstreamBrief = "",
            enabled = enabled,
            maxRunning = 5
        )

        val rejected = plan as TeamRunner.Plan.Rejected
        assertEquals("AGENT_NOT_FOUND", rejected.code)
        assertTrue(rejected.message.contains("不存在的角色"))
    }

    @Test
    fun buildWorkflowLayer_rejectsUnknownLead() {
        val plan = TeamRunner.buildWorkflowLayer(
            workflow = workflow,
            layerIndex = 2,
            ref = WorkflowTeamRef("架构设计团队", lead = "不存在的主持人"),
            team = team("架构设计团队", "系统架构师"),
            goal = "g",
            upstreamBrief = "",
            enabled = enabled,
            maxRunning = 5
        )

        val rejected = plan as TeamRunner.Plan.Rejected
        assertEquals("AGENT_NOT_FOUND", rejected.code)
        assertTrue(rejected.message.contains("协调者 → 不存在的主持人"))
    }

    @Test
    fun buildReworkTask_targetsNamedExpertWithRound() {
        val reject = WorkflowOrchestrator.Verdict.Reject(
            targetTeam = "需求分析团队",
            targetExpert = "需求分析师",
            deliverable = "接口契约",
            reason = "缺分页约定"
        )
        val plan = TeamRunner.buildReworkTask(
            workflow = workflow,
            targetLayer = 1,
            targetTeam = "需求分析团队",
            targetExpert = "需求分析师",
            reject = reject,
            goal = "把登录改造完",
            round = 1,
            upstreamBrief = "已有接口契约 v1",
            enabled = enabled
        ) as TeamRunner.Plan.Ready

        val tasks = plan.pipeline.objects()
        assertEquals(1, tasks.size)
        val rework = tasks.single()
        assertEquals(TeamRunner.reworkId(1), rework.text("id"))
        assertEquals("需求分析师", rework.text("agent"))
        assertTrue(rework.text("prompt").contains("已有交付"))
        assertTrue(rework.text("prompt").contains("缺分页约定"))
        // 返工只授任务、不授写路径：范围由下游在理由里说清。
        assertTrue(rework.stringList("writePaths").isEmpty())
    }

    @Test
    fun buildReworkTask_rejectsUnknownExpert() {
        val plan = TeamRunner.buildReworkTask(
            workflow = workflow,
            targetLayer = 1,
            targetTeam = "需求分析团队",
            targetExpert = "查无此人",
            reject = WorkflowOrchestrator.Verdict.Reject(null, "查无此人", "接口契约", "理由"),
            goal = "g",
            round = 1,
            upstreamBrief = "",
            enabled = enabled
        )

        val rejected = plan as TeamRunner.Plan.Rejected
        assertEquals("AGENT_NOT_FOUND", rejected.code)
        assertTrue(rejected.message.contains("驳回必须指名到具体专家"))
    }
}
