package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionScope
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.SubAgentProfileMatcher
import com.aharou.feature.agent.domain.subagent.TeamStage
import com.aharou.feature.agent.domain.subagent.TeamTask
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow
import com.aharou.feature.agent.domain.teamworkflow.WorkflowOrchestrator
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef
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
        val badRoles = unknownRoles(team, enabled)
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

    /**
     * 把工作流的第 [layerIndex] 层（1 起）展开成一个可独立执行的 pipeline：按团队预设的阶段跑完，
     * 最后追加一个协调者任务输出判定。
     *
     * 层与层之间不复用 dependsOn：每层是一次独立的 pipeline 调用，上游事实靠 [upstreamBrief] 文本传递。
     * 这样驳回时只需重跑某一层，不必把整条链拼成一个大图。
     */
    fun buildWorkflowLayer(
        workflow: TeamWorkflow,
        layerIndex: Int,
        ref: WorkflowTeamRef,
        team: AgentTeam,
        goal: String,
        upstreamBrief: String,
        enabled: List<AgentDefinition>,
        maxRunning: Int
    ): Plan {
        val badRoles = unknownRoles(team, enabled).toMutableList()
        val lead = ref.lead?.trim()?.takeIf { it.isNotEmpty() }
        if (lead != null && enabled.none { it.name.equals(lead, ignoreCase = true) }) {
            badRoles += "协调者 → $lead"
        }
        if (badRoles.isNotEmpty()) {
            return Plan.Rejected(
                "AGENT_NOT_FOUND",
                "工作流「${workflow.name}」第 $layerIndex 层团队「${team.name}」引用了不可用的角色：" +
                    "${badRoles.joinToString("；")}。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}。"
            )
        }

        // 协调者三级回退：工作流里点名的 lead → 该团队首个任务点名的角色 → 首个任务的部门/关键词选角。
        val firstTask = team.stages.firstOrNull()?.tasks?.firstOrNull()
        val judgeAgent = lead ?: firstTask?.agent
        val judgeDepartment = if (judgeAgent == null) firstTask?.department else null
        val judgeQuery = if (judgeAgent == null) firstTask?.agentQuery else null

        val batchSize = maxRunning.coerceAtLeast(1)
        var layers = 0
        // 本层全部成员任务的 id：协调者要看到整层的产出，不能只看最后一批。
        val members = mutableListOf<String>()
        val pipeline = buildJsonArray {
            var upstream: List<String> = emptyList()
            team.stages.forEachIndexed { stageIndex, stage ->
                stage.tasks.chunked(batchSize).forEachIndexed { batchIndex, batch ->
                    val ids = batch.indices.map { "w$layerIndex-s${stageIndex + 1}-b${batchIndex + 1}-t${it + 1}" }
                    batch.forEachIndexed { taskIndex, task ->
                        addJsonObject {
                            put("id", ids[taskIndex])
                            put("description", "第$layerIndex 层·${stage.title}·${task.memberLabel()}".take(DESCRIPTION_MAX))
                            put("prompt", taskPrompt(team, stage, task, goal, upstreamBrief))
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
                    members += ids
                    layers++
                }
            }
            addJsonObject {
                put("id", judgeId(layerIndex))
                put("description", "第$layerIndex 层判定·${team.name}".take(DESCRIPTION_MAX))
                put(
                    "prompt",
                    buildString {
                        if (upstreamBrief.isNotBlank()) append("## 上游交付摘要\n").append(upstreamBrief).append("\n\n---\n\n")
                        append(
                            WorkflowOrchestrator.judgePrompt(
                                workflow = workflow,
                                layerIndex = layerIndex,
                                teamName = team.name,
                                goal = goal,
                                hasNextLayer = layerIndex < workflow.layerCount
                            )
                        )
                    }
                )
                judgeAgent?.let { put("agent", it) }
                judgeDepartment?.let { put("department", it) }
                judgeQuery?.let { put("agentQuery", it) }
                if (members.isNotEmpty()) {
                    put("dependsOn", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
                }
            }
        }
        return Plan.Ready(pipeline, layers + 1, team.taskCount + 1)
    }

    /**
     * 驳回后的返工：只给被点名的那位上游专家派一个任务，附带下游理由与轮次。
     * writePaths 不跟着传：返工范围由下游在理由里说清，硬塞路径反而会把无关文件也授出去。
     */
    fun buildReworkTask(
        workflow: TeamWorkflow,
        targetLayer: Int,
        targetTeam: String,
        targetExpert: String,
        reject: WorkflowOrchestrator.Verdict.Reject,
        goal: String,
        round: Int,
        upstreamBrief: String,
        enabled: List<AgentDefinition>
    ): Plan {
        if (enabled.none { it.name.equals(targetExpert, ignoreCase = true) }) {
            return Plan.Rejected(
                "AGENT_NOT_FOUND",
                "驳回目标「$targetTeam·$targetExpert」不是可用角色：驳回必须指名到具体专家，" +
                    "可先 task(action=\"roles\") 查名字。可用部门：${SubAgentProfileMatcher.departmentHint(enabled)}"
            )
        }
        val pipeline = buildJsonArray {
            addJsonObject {
                put("id", reworkId(targetLayer))
                put("description", "第$targetLayer 层返工·$targetExpert".take(DESCRIPTION_MAX))
                put(
                    "prompt",
                    buildString {
                        if (upstreamBrief.isNotBlank()) append("## 当前已有交付\n").append(upstreamBrief).append("\n\n---\n\n")
                        append(
                            WorkflowOrchestrator.reworkPrompt(
                                workflow = workflow,
                                targetLayer = targetLayer,
                                targetTeam = targetTeam,
                                targetExpert = targetExpert,
                                reject = reject,
                                goal = goal,
                                round = round
                            )
                        )
                    }
                )
                put("agent", targetExpert)
            }
        }
        return Plan.Ready(pipeline, 1, 1)
    }

    /** 每层协调者任务的 id；执行层按它取判定文本。 */
    fun judgeId(layerIndex: Int): String = "w$layerIndex-judge"

    /** 返工任务的 id。 */
    fun reworkId(layerIndex: Int): String = "w$layerIndex-rework"

    /** 角色引用一次性查完：写错角色名会让前面几个子代理白烧 token。 */
    private fun unknownRoles(team: AgentTeam, enabled: List<AgentDefinition>): List<String> {
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
        return badRoles
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
    private fun taskPrompt(team: AgentTeam, stage: TeamStage, task: TeamTask, goal: String, upstreamBrief: String = ""): String =
        buildString {
            if (upstreamBrief.isNotBlank()) append("## 上游交付摘要\n").append(upstreamBrief).append("\n\n---\n\n")
            append("你是团队「").append(team.name).append("」在阶段「").append(stage.title).append("」的成员。\n")
            append("本次团队目标：").append(goal).append("\n")
            if (team.charter.isNotBlank()) append("\n团队公约：\n").append(team.charter).append("\n")
            append("\n你的任务：\n").append(task.prompt)
        }

    private fun TeamTask.memberLabel(): String =
        agent ?: listOfNotNull(department, agentQuery).joinToString("/")
}
