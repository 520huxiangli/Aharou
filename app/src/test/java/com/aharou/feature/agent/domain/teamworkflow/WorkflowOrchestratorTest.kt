package com.aharou.feature.agent.domain.teamworkflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowOrchestratorTest {

    private val workflow = TeamWorkflow(
        name = "研发工作流",
        description = "需求 → 架构 → 实现",
        teams = listOf(
            WorkflowTeamRef(teamName = "需求分析团队"),
            WorkflowTeamRef(teamName = "架构设计团队", lead = "系统架构师"),
            WorkflowTeamRef(teamName = "实现团队", acceptsReject = false)
        ),
        charter = "跨团队只认已落盘的事实。",
        maxRejectRounds = 2
    )

    @Test
    fun parseVerdict_readsLastJudgeLine() {
        val output = """
            [[JUDGE]] HANDOFF
            实现完了，顺便给个结论。
            [[JUDGE]] HANDLE
        """.trimIndent()

        assertEquals(WorkflowOrchestrator.Verdict.Handle, WorkflowOrchestrator.parseVerdict(output))
    }

    @Test
    fun parseVerdict_toleratesFullWidthColonAndCase() {
        assertEquals(
            WorkflowOrchestrator.Verdict.Handoff,
            WorkflowOrchestrator.parseVerdict("[[judge]]：handoff")
        )
        assertEquals(
            WorkflowOrchestrator.Verdict.Handle,
            WorkflowOrchestrator.parseVerdict("  判定：  [[JUDGE]]   HANDLE  ")
        )
    }

    @Test
    fun parseVerdict_rejectWithAllFields() {
        val verdict = WorkflowOrchestrator.parseVerdict(
            "[[JUDGE]] REJECT | 需求分析团队·需求分析师 | 接口契约 | 缺分页约定 | 也没写超时"
        )

        val reject = verdict as WorkflowOrchestrator.Verdict.Reject
        assertEquals("需求分析团队", reject.targetTeam)
        assertEquals("需求分析师", reject.targetExpert)
        assertEquals("接口契约", reject.deliverable)
        // 理由里允许再出现竖线，不能被截断。
        assertEquals("缺分页约定 | 也没写超时", reject.reason)
    }

    @Test
    fun parseVerdict_rejectWithoutTeamFallsBackToExpertName() {
        val reject = WorkflowOrchestrator.parseVerdict(
            "[[JUDGE]] REJECT：需求分析师 / 索引设计 / 用了全表扫描"
        ) as WorkflowOrchestrator.Verdict.Reject

        assertNull(reject.targetTeam)
        assertEquals("需求分析师", reject.targetExpert)
        assertEquals("索引设计", reject.deliverable)
        assertEquals("用了全表扫描", reject.reason)
    }

    @Test
    fun parseVerdict_rejectWithOnlyReasonKeepsDefaults() {
        val reject = WorkflowOrchestrator.parseVerdict(
            "[[JUDGE]] REJECT | 交付不合理"
        ) as WorkflowOrchestrator.Verdict.Reject

        assertNull(reject.targetTeam)
        assertEquals("交付不合理", reject.targetExpert)
        assertEquals("上游交付", reject.deliverable)
        assertTrue(reject.reason.isNotBlank())
    }

    @Test
    fun parseVerdict_withoutMarkerIsUnknown() {
        val verdict = WorkflowOrchestrator.parseVerdict("我觉得应该交给下一层。")
        assertTrue(verdict is WorkflowOrchestrator.Verdict.Unknown)
        assertEquals("我觉得应该交给下一层。", (verdict as WorkflowOrchestrator.Verdict.Unknown).raw)

        assertTrue(WorkflowOrchestrator.parseVerdict("") is WorkflowOrchestrator.Verdict.Unknown)
        assertTrue(
            WorkflowOrchestrator.parseVerdict("[[JUDGE]] 随便写的") is WorkflowOrchestrator.Verdict.Unknown
        )
    }

    @Test
    fun rejectLedger_stopsAtLimit() {
        val ledger = WorkflowOrchestrator.RejectLedger(maxRounds = 2)
        val key = WorkflowOrchestrator.rejectKey(2, "系统架构师", 1, "需求分析师")

        assertTrue(ledger.register(key).allowed)
        assertTrue(ledger.register(key).allowed)
        val third = ledger.register(key)
        assertFalse(third.allowed)
        assertEquals(3, third.used)

        // 换一对专家各自计数，不能互相吃掉额度。
        val other = WorkflowOrchestrator.rejectKey(3, "后端工程专家", 2, "系统架构师")
        assertTrue(ledger.register(other).allowed)
        assertEquals(1, ledger.usedOf(other))
    }

    @Test
    fun judgePrompt_firstLayerNeverOffersReject() {
        val prompt = WorkflowOrchestrator.judgePrompt(
            workflow = workflow,
            layerIndex = 1,
            teamName = "需求分析团队",
            goal = "把登录改造完",
            hasNextLayer = true
        )

        assertTrue(prompt.contains(WorkflowOrchestrator.JUDGE_MARKER + " HANDLE"))
        assertTrue(prompt.contains(WorkflowOrchestrator.JUDGE_MARKER + " HANDOFF"))
        assertFalse(prompt.contains("REJECT"))
        assertTrue(prompt.contains("跨团队只认已落盘的事实。"))
    }

    @Test
    fun judgePrompt_middleLayerOffersReject_lastLayerOffersNoHandoff() {
        val middle = WorkflowOrchestrator.judgePrompt(workflow, 2, "架构设计团队", "把登录改造完", true)
        assertTrue(middle.contains("REJECT | 上游团队·上游专家"))

        val last = WorkflowOrchestrator.judgePrompt(workflow, 3, "实现团队", "把登录改造完", false)
        assertFalse(last.contains("HANDOFF"))
        assertTrue(last.contains("这是最后一层"))

        // 第三层 acceptsReject=false，不许退上游，也就不该出现 REJECT 模板。
        assertFalse(last.contains("REJECT | 上游团队·上游专家"))
    }

    @Test
    fun reworkPrompt_statesRoundAndScope() {
        val reject = WorkflowOrchestrator.Verdict.Reject(
            targetTeam = "需求分析团队",
            targetExpert = "需求分析师",
            deliverable = "接口契约",
            reason = "缺分页约定"
        )
        val prompt = WorkflowOrchestrator.reworkPrompt(
            workflow = workflow,
            targetLayer = 1,
            targetTeam = "需求分析团队",
            targetExpert = "需求分析师",
            reject = reject,
            goal = "把登录改造完",
            round = 2
        )

        assertTrue(prompt.contains("第 2 层驳回你提交的交付「接口契约」"))
        assertTrue(prompt.contains("缺分页约定"))
        assertTrue(prompt.contains("第 2 轮返工"))
        assertTrue(prompt.contains("只改被退回的部分"))
    }

    @Test
    fun escalationMessage_carriesLimitAndReason() {
        val message = WorkflowOrchestrator.escalationMessage(
            workflow = workflow,
            reject = WorkflowOrchestrator.Verdict.Reject(null, null, "接口契约", "缺分页约定"),
            downstreamTeam = "架构设计团队",
            downstreamExpert = "系统架构师",
            upstreamTeam = "需求分析团队",
            upstreamExpert = "需求分析师",
            rounds = 3
        )

        assertTrue(message.contains("往返已达 3 轮上限"))
        assertTrue(message.contains("maxRejectRounds=2"))
        assertTrue(message.contains("缺分页约定"))
        assertTrue(message.contains("交由主代理裁决"))
    }

    @Test
    fun reportLine_coversAllVerdicts() {
        assertTrue(
            WorkflowOrchestrator.reportLine(1, "需求分析团队", WorkflowOrchestrator.Verdict.Handle)
                .contains("不再往下流转")
        )
        assertTrue(
            WorkflowOrchestrator.reportLine(1, "需求分析团队", WorkflowOrchestrator.Verdict.Handoff)
                .contains("转交下一层")
        )
        assertTrue(
            WorkflowOrchestrator.reportLine(
                2,
                "架构设计团队",
                WorkflowOrchestrator.Verdict.Reject("需求分析团队", "需求分析师", "接口契约", "缺分页约定")
            ).contains("REJECT → 需求分析团队·需求分析师 的「接口契约」")
        )
        assertTrue(
            WorkflowOrchestrator.reportLine(2, "架构设计团队", WorkflowOrchestrator.Verdict.Unknown("随便"))
                .contains("按 HANDOFF 继续")
        )
    }
}
