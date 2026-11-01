package com.aharou.feature.agent.domain.teamworkflow

import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import org.junit.Assert.assertEquals
import org.junit.Test

class TeamWorkflowRepositoryTest {

    private fun workflow(name: String) = TeamWorkflow(
        name = name,
        description = "",
        teams = listOf(WorkflowTeamRef("甲团队"), WorkflowTeamRef("乙团队")),
        charter = ""
    )

    @Test
    fun mergeAll_projectScopeOverridesGlobalAndSorts() {
        val merged = TeamWorkflowRepository.mergeAll(
            global = listOf(workflow("发布流程"), workflow("研发工作流")),
            project = listOf(workflow("研发工作流"), workflow("调研流程"))
        )

        // 同名项目级覆盖全局，且覆盖后不能出现两条同名项（否则 find 命中的是哪份就说不清了）。
        assertEquals(3, merged.size)
        assertEquals("发布流程", merged[0].workflow.name)
        assertEquals(AgentDefinitionScope.GLOBAL, merged[0].scope)
        assertEquals("研发工作流", merged[1].workflow.name)
        assertEquals(AgentDefinitionScope.PROJECT, merged[1].scope)
        assertEquals("调研流程", merged[2].workflow.name)
    }

    @Test
    fun mergeAll_isCaseInsensitiveOnName() {
        val merged = TeamWorkflowRepository.mergeAll(
            global = listOf(workflow("Delivery Flow")),
            project = listOf(workflow("delivery flow"))
        )

        assertEquals(1, merged.size)
        assertEquals(AgentDefinitionScope.PROJECT, merged.single().scope)
    }
}
