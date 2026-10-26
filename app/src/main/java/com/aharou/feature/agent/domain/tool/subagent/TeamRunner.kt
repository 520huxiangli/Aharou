package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put

/**
 * 团队预设的展开与文案：把 `stages` 变成 `task(action="pipeline")` 能直接跑的数组。
 *
 * 抽成纯逻辑有两个理由：单文件行数门禁盯着 TaskTool；这里的校验与格式化可以在单测里直接覆盖，
 * 不必真的派子代理。
 */
object TeamRunner {

    private const val DESCRIPTION_MAX = 30
    private const val LIST_DESCRIPTION_MAX = 160

    /** 展开结果：要么是可执行的 pipeline，要么是带错误码的拒绝原因。 */
    sealed interface Plan {
        /** [layers] 是实际执行层数：阶段内任务超过并发上限会被拆成多批，比阶段数多。 */
        data class Ready(val pipeline: JsonArray, val layers: Int, val taskCount: Int) : Plan
        data class Rejected(val code: String, val message: String) : Plan
    }

    /**
     * 派发前把角色引用一次性查完：写错角色名会让前面几个子代理白烧 token。
     *
     * 阶段内任务数超过并发上限时**自动分批排队**（后一批依赖前一批），不报错——预设里多写两个
     * 成员不该逼用户去拆阶段。
     */
    fun build(team: AgentTeam, goal: String, enabled: List<AgentDefinition>, maxRunning: Int): Plan {
        val badRoles = mutableListOf<String>()
        team.stages.forEach { stage ->
            stage.tasks.forEach { task ->
                val agentName = task.agent
                if (agentName != null) {
                    if (enabled.none { it.name.equals(agentName, ignoreCase = true) }) {
                        badRoles += "${stage.title} → $agentName"
                    }
                } else if (
                    SubAgentProfileMatcher.match(enabled, task.department, task.agentQuery)
                    is SubAgentProfileMatcher.MatchOutcome.Unmatched
                ) {
                    badRoles += "${stage.title} → ${task.memberLabel()}"
                }
            }
        }
        if (badRoles.isNotEmpty()) {
            return Plan.Rejected(
                "AGENT_NOT_FOUND",
                "团队「${team.name}」引用了不可用的角色：${badRoles.joinToString("；")}。" +
                    "可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}；" +
                    "先用 task(action=\"roles\", department=\"…\", agentQuery=\"…\") 查角色名，再改团队文件的 agent 字段。"
            )
        }

        val batchSize = maxRunning.coerceAtLeast(1)
        var layers = 0
        val pipeline = buildJsonArray {
            var upstream: List<String> = emptyList()
            team.stages.forEachIndexed { stageIndex, stage ->
                stage.tasks.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                    val ids = batch.indices.map { "s${stageIndex + 1}-b${batchIndex + 1}-t${it + 1}" }
                    batch.forEachIndexed { taskIndex, task ->
                        addJsonObject {
                            put("id", ids[taskIndex])
                            put("description", "${stage.title}·${task.memberLabel()}".take(DESCRIPTION_MAX))
                            put("prompt", taskPrompt(team, stage, task, goal))
                            task.agent?.let { put("agent", it) }
                            task.department?.let { put("department", it) }
                            task.agentQuery?.let { put("agentQuery", it) }
                            if (task.writePaths.isNotEmpty()) {
                                put("writePaths", buildJsonArray { task.writePaths.forEach { add(JsonPrimitive(it)) } })
                            }
                            if (upstream.isNotEmpty()) {
                                put("dependsOn", buildJsonArray { upstream.forEach { add(JsonPrimitive(it)) } })
                            }
                        }
                    }
                    upstream = ids
                    layers++
                }
            }
        }
        return Plan.Ready(pipeline, layers, team.taskCount)
    }

    /** 全部团队的一行式清单。 */
    fun renderList(entries: List<AgentTeamEntry>): String = buildString {
        append("团队预设（共 ").append(entries.size).append(" 个）：\n")
        entries.forEach { entry ->
            val team = entry.team
            append("- ").append(team.name).append("（").append(scopeLabel(entry.scope)).append("，")
                .append(team.stages.size).append(" 阶段 / ").append(team.taskCount).append(" 任务）")
            val summary = team.description.lineSequence().first().trim().take(LIST_DESCRIPTION_MAX)
            if (summary.isNotEmpty()) append("：").append(summary)
            append("\n  流程：").append(team.stages.joinToString(" → ") { it.title }).append("\n")
        }
        append("执行：task(action=\"team_run\", team=\"<名>\", goal=\"<本次目标>\"）；")
            .append("看单个预设的成员与文件路径用 task(action=\"teams\", team=\"<名>\")。")
    }

    /** 单个团队的阶段与成员详情。 */
    fun renderDetail(entry: AgentTeamEntry): String = buildString {
        val team = entry.team
        append("团队「").append(team.name).append("」（").append(scopeLabel(entry.scope)).append("）")
        if (team.description.isNotBlank()) append("：").append(team.description)
        append("\n共 ").append(team.stages.size).append(" 个阶段、").append(team.taskCount).append(" 个任务：\n")
        team.stages.forEachIndexed { index, stage ->
            append(index + 1).append(". ").append(stage.title).append("（")
            append(if (stage.tasks.size > 1) "并行 ${stage.tasks.size} 个" else "1 个").append("）：")
            append(stage.tasks.joinToString("、") { it.memberLabel() })
            append("\n")
        }
        append("文件：").append(team.filePath ?: "-").append("\n")
        append("执行：task(action=\"team_run\", team=\"").append(team.name).append("\", goal=\"<本次目标>\")")
    }

    fun scopeLabel(scope: AgentDefinitionScope): String =
        if (scope == AgentDefinitionScope.GLOBAL) "全局" else "项目"

    /** 单个团队成员的任务指令：团队公约 + 本次目标 + 本阶段任务。 */
    private fun taskPrompt(team: AgentTeam, stage: TeamStage, task: TeamTask, goal: String): String =
        buildString {
            append("你是团队「").append(team.name).append("」在阶段「").append(stage.title).append("」的成员。\n")
            append("本次团队目标：").append(goal).append("\n")
            if (team.charter.isNotBlank()) append("\n团队公约：\n").append(team.charter).append("\n")
            append("\n你的任务：\n").append(task.prompt)
        }

    private fun TeamTask.memberLabel(): String =
        agent ?: listOfNotNull(department, agentQuery).joinToString("/")
}
