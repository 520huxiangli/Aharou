package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentTeam
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflow
import com.aharou.feature.agent.domain.teamworkflow.TeamWorkflowRepository
import com.aharou.feature.agent.domain.teamworkflow.WorkflowOrchestrator
import com.aharou.feature.agent.domain.teamworkflow.WorkflowTeamRef
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 多团队工作流的执行器：逐层跑团队、读该层协调者的判定、必要时把某份交付驳回给上游专家返工。
 *
 * 与单团队的 [TeamTasks] 分开：单团队是一条写死的 pipeline，工作流是一条按判定推进的循环，
 * 混在一个类里会让「什么时候往下走」既看不清也测不了。
 *
 * 执行仍然复用 `task(action="pipeline")` 的调度（阶段内并行、写冲突拦截），这里只负责
 * 「跑哪一层、要不要重跑、退给谁」。
 */
@Singleton
class WorkflowRunner @Inject constructor(
    private val workflowRepository: TeamWorkflowRepository,
    private val teamRepository: AgentTeamRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val maxRunningSource: MaxRunningSource
) {

    /** 列工作流清单；给了 [workflow] 就展开它的团队链。 */
    fun list(target: String): ToolResult {
        val entries = try {
            workflowRepository.listAll()
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取工作流预设失败", e)
            return ToolResult.Error("读取工作流预设失败：${e.message ?: "未知错误"}", "WORKFLOW_LIST_FAILED")
        }
        if (target.isNotBlank() && target != "*") {
            val entry = entries.firstOrNull { it.workflow.name.equals(target, ignoreCase = true) }
                ?: return ToolResult.Error(
                    if (entries.isEmpty()) {
                        "工作流预设不存在：$target；当前也没有任何工作流。多团队流转需要在 " +
                            "${workflowRepository.globalRoot} 下新建 <名>.md（单团队不需要工作流）。"
                    } else {
                        "工作流预设不存在：$target。可用工作流：${entries.joinToString("、") { it.workflow.name }}"
                    },
                    "WORKFLOW_NOT_FOUND"
                )
            return ToolResult.Success(
                buildJsonObject {
                    put("content", renderDetail(entry.workflow, entry.scope.name))
                    put("name", entry.workflow.name)
                    put("layers", entry.workflow.layerCount)
                }
            )
        }
        if (entries.isEmpty()) {
            return ToolResult.Error(
                "当前没有工作流预设。只有单个团队时不需要工作流，直接用 task(action=\"team_run\", team=…, goal=…)；" +
                    "需要多层流转时在 ${workflowRepository.globalRoot}（全局）或 " +
                    "${workflowRepository.projectRoot}（当前工作区）新建 <名>.md，frontmatter 的 teams 按流转方向排列。",
                "WORKFLOW_LIST_EMPTY"
            )
        }
        val content = buildString {
            append("工作流预设（共 ").append(entries.size).append(" 个）：\n")
            entries.forEach { entry ->
                append("- ").append(entry.workflow.name)
                    .append("（").append(if (entry.scope.name == "GLOBAL") "全局" else "项目").append("，")
                    .append(entry.workflow.layerCount).append(" 层，最多驳回 ")
                    .append(entry.workflow.maxRejectRounds).append(" 轮）")
                val summary = entry.workflow.description.lineSequence().first().trim().take(160)
                if (summary.isNotEmpty()) append("：").append(summary)
                append("\n  流转：")
                    .append(entry.workflow.teams.joinToString(" → ") { it.teamName })
                    .append("\n")
            }
            append("执行：task(action=\"team_run\", workflow=\"<名>\", goal=\"<本次目标>\")。")
        }
        return ToolResult.Success(
            buildJsonObject {
                put("content", content)
                put("count", entries.size)
            }
        )
    }

    /**
     * 按工作流逐层执行。[exec] 接收一层的 pipeline 并返回带各任务产出的结果，
     * 由调用方接到 `task(action="pipeline")` 上。
     */
    suspend fun run(
        args: Map<String, JsonElement>,
        exec: suspend (JsonArray) -> ToolResult
    ): ToolResult {
        val name = arg(args, "workflow")
        if (name.isEmpty()) {
            return ToolResult.Error("team_run 指定 workflow 时必须给工作流名（先用 action=\"teams\" 查）", "MISSING_WORKFLOW")
        }
        val goal = arg(args, "goal")
        if (goal.isEmpty()) {
            return ToolResult.Error("team_run 需要 goal（本次要达成的目标）", "MISSING_GOAL")
        }

        val workflow = try {
            workflowRepository.find(name)
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取工作流预设失败", e)
            return ToolResult.Error("读取工作流预设失败：${e.message ?: "未知错误"}", "WORKFLOW_LIST_FAILED")
        } ?: return ToolResult.Error("工作流预设不存在：$name（先用 action=\"teams\" 查）", "WORKFLOW_NOT_FOUND")

        val enabled: List<AgentDefinition> = try {
            agentDefinitionRepository.listEnabled().map { it.definition }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取子代理定义失败", e)
            return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
        }

        val teams = try {
            teamRepository.listAll().associateBy { it.team.name.lowercase() }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取团队预设失败", e)
            return ToolResult.Error("读取团队预设失败：${e.message ?: "未知错误"}", "TEAM_LIST_FAILED")
        }

        val ledger = WorkflowOrchestrator.RejectLedger(workflow.maxRejectRounds)
        val report = mutableListOf<String>()
        var brief = ""
        var degraded = false
        var layerIndex = 0

        while (layerIndex < workflow.layerCount) {
            val layer = layerIndex + 1
            val ref = workflow.teams[layerIndex]
            val team = teams[ref.teamName.lowercase()]?.team
                ?: return ToolResult.Error(
                    "工作流「${workflow.name}」第 $layer 层引用的团队「${ref.teamName}」不存在或已禁用。" +
                        "可用团队：${teams.values.joinToString("、") { it.team.name }}",
                    "TEAM_NOT_FOUND"
                )

            val layerPlan = TeamRunner.buildWorkflowLayer(
                workflow = workflow,
                layerIndex = layer,
                ref = ref,
                team = team,
                goal = goal,
                upstreamBrief = brief,
                enabled = enabled,
                maxRunning = maxRunningSource.maxRunning()
            )
            val pipeline = when (layerPlan) {
                is TeamRunner.Plan.Rejected -> return ToolResult.Error(layerPlan.message, layerPlan.code)
                is TeamRunner.Plan.Ready -> layerPlan.pipeline
            }

            FileLogger.i(TAG, "工作流「${workflow.name}」第 $layer 层「${team.name}」开始执行")
            val executed = exec(pipeline)
            if (executed is ToolResult.Error) {
                report += "第 $layer 层「${team.name}」执行失败（${executed.code}）"
                return ToolResult.Error(
                    "工作流「${workflow.name}」在第 $layer 层中断：${executed.message}\n已跑过的层：\n" +
                        report.joinToString("\n"),
                    executed.code
                )
            }
            val outputs = extractOutputs(executed)
            val verdict = WorkflowOrchestrator.parseVerdict(outputs[TeamRunner.judgeId(layer)].orEmpty())
            report += WorkflowOrchestrator.reportLine(layer, team.name, verdict)

            // 本层产出作为下游事实：协调者的结论放最后，下游最先看到的是成员交付。
            val layerBrief = buildBrief(layer, team, outputs)

            when (verdict) {
                is WorkflowOrchestrator.Verdict.Handle -> return finish(
                    workflow = workflow,
                    report = report,
                    completedLayers = layer,
                    degraded = degraded,
                    brief = brief,
                    state = "done"
                )

                is WorkflowOrchestrator.Verdict.Handoff, is WorkflowOrchestrator.Verdict.Unknown -> {
                    if (verdict is WorkflowOrchestrator.Verdict.Unknown) degraded = true
                    brief = mergeBrief(brief, layerBrief)
                    layerIndex++
                }

                is WorkflowOrchestrator.Verdict.Reject -> {
                    if (layer <= 1 || !ref.acceptsReject) {
                        val why = if (layer <= 1) "第一层没有上游" else "acceptsReject=false"
                        report += "  └ 本层不能驳回（$why），按转交处理"
                        brief = mergeBrief(brief, layerBrief)
                        layerIndex++
                    } else {
                        val upstreamRef = workflow.teams[layerIndex - 1]
                        val upstreamTeam = teams[upstreamRef.teamName.lowercase()]?.team
                        val targetExpert = verdict.targetExpert
                            ?: upstreamRef.lead
                            ?: upstreamTeam?.stages?.firstOrNull()?.tasks?.firstOrNull()?.agent
                        if (targetExpert.isNullOrBlank()) {
                            report += "  └ 驳回没有指名到具体专家，无法返工：已升级裁决"
                            return finish(
                                workflow = workflow,
                                report = report,
                                completedLayers = layer,
                                degraded = degraded,
                                brief = brief,
                                state = "escalated",
                                note = "驳回目标无法解析成专家名（工作流里该层没写 lead，" +
                                    "团队首个任务也没用 agent 点名）。请在工作流的该层补 lead，或让协调者按 " +
                                    "'[[JUDGE]] REJECT | 团队·专家 | 交付 | 理由' 指名。"
                            )
                        }
                        val downstreamExpert = judgeName(ref, team)
                        val key = WorkflowOrchestrator.rejectKey(
                            upstreamLayer = layer - 1,
                            upstreamExpert = targetExpert,
                            downstreamLayer = layer,
                            downstreamExpert = downstreamExpert
                        )
                        val round = ledger.register(key)
                        if (!round.allowed) {
                            report += "  └ 往返已到上限（${workflow.maxRejectRounds} 轮），停止自动返工：已升级裁决"
                            return finish(
                                workflow = workflow,
                                report = report,
                                completedLayers = layer,
                                degraded = degraded,
                                brief = brief,
                                state = "escalated",
                                note = WorkflowOrchestrator.escalationMessage(
                                    workflow = workflow,
                                    reject = verdict,
                                    downstreamTeam = team.name,
                                    downstreamExpert = downstreamExpert,
                                    upstreamTeam = upstreamRef.teamName,
                                    upstreamExpert = targetExpert,
                                    rounds = round.used
                                )
                            )
                        }

                        val reworkPlan = TeamRunner.buildReworkTask(
                            workflow = workflow,
                            targetLayer = layer - 1,
                            targetTeam = upstreamRef.teamName,
                            targetExpert = targetExpert,
                            reject = verdict,
                            goal = goal,
                            round = round.used,
                            upstreamBrief = brief,
                            enabled = enabled
                        )
                        val reworkPipeline = when (reworkPlan) {
                            is TeamRunner.Plan.Rejected -> return ToolResult.Error(reworkPlan.message, reworkPlan.code)
                            is TeamRunner.Plan.Ready -> reworkPlan.pipeline
                        }
                        val reworkResult = exec(reworkPipeline)
                        if (reworkResult is ToolResult.Error) {
                            report += "  └ 返工派发失败（${reworkResult.code}）：已升级裁决"
                            return finish(
                                workflow = workflow,
                                report = report,
                                completedLayers = layer,
                                degraded = degraded,
                                brief = brief,
                                state = "escalated",
                                note = "第 ${layer - 1} 层「$targetExpert」返工失败：${reworkResult.message}"
                            )
                        }
                        val reworkOutput = extractOutputs(reworkResult)[TeamRunner.reworkId(layer - 1)].orEmpty()
                        report += "  └ 已退回第 ${layer - 1} 层「${upstreamRef.teamName}·$targetExpert」" +
                            "（第 ${round.used} 轮），理由：${verdict.reason}"
                        brief = mergeBrief(
                            brief,
                            "## 返工后交付（第 ${layer - 1} 层 · $targetExpert）\n" + reworkOutput.ifBlank { "（返工任务没有产出结论）" }
                        )
                        // 不推进层号：返工后的交付要重新过一遍本层判定。
                    }
                }
            }
        }

        return finish(
            workflow = workflow,
            report = report,
            completedLayers = workflow.layerCount,
            degraded = degraded,
            brief = brief,
            state = "done",
            note = "已跑完工作流全部 ${workflow.layerCount} 层。"
        )
    }

    /** 层执行结果里取回各任务产出（`outputs` 由 pipeline 执行侧回传，尾部截断）。 */
    private fun extractOutputs(result: ToolResult): Map<String, String> {
        val data = (result as? ToolResult.Success)?.data as? JsonObject ?: return emptyMap()
        val outputs = data["outputs"] as? JsonObject ?: return emptyMap()
        return outputs.mapNotNull { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (text.isNullOrBlank()) null else key to text
        }.toMap()
    }

    /** 一层的协调者展示名：点了 lead 就照它，否则是该团队首个任务的角色，再退回团队名。 */
    private fun judgeName(ref: WorkflowTeamRef, team: AgentTeam): String =
        ref.lead ?: team.stages.firstOrNull()?.tasks?.firstOrNull()?.agent ?: team.name

    /** 一层的交付摘要：成员在前，协调者结论在后。 */
    private fun buildBrief(layer: Int, team: AgentTeam, outputs: Map<String, String>): String = buildString {
        append("## 第 ").append(layer).append(" 层「").append(team.name).append("」交付\n")
        outputs.forEach { (id, text) ->
            if (id == TeamRunner.judgeId(layer)) return@forEach
            append("\n### ").append(id).append("\n").append(text).append("\n")
        }
        val judge = outputs[TeamRunner.judgeId(layer)].orEmpty()
        if (judge.isNotBlank()) {
            append("\n### 本层协调者结论\n").append(judge).append("\n")
        }
    }

    /** 上游事实累计有上限：逐层追加不设限会把 token 撑爆，超了只留最新的一段。 */
    private fun mergeBrief(current: String, addition: String): String {
        if (addition.isBlank()) return current
        val joined = if (current.isBlank()) addition else "$current\n\n$addition"
        return if (joined.length <= BRIEF_MAX_CHARS) joined else joined.takeLast(BRIEF_MAX_CHARS)
    }

    private fun finish(
        workflow: TeamWorkflow,
        report: List<String>,
        completedLayers: Int,
        degraded: Boolean,
        brief: String,
        state: String,
        note: String? = null
    ): ToolResult {
        val body = buildString {
            append("工作流「").append(workflow.name).append("」（state=").append(state).append("）\n")
            report.forEach { append(it).append("\n") }
            note?.let { append("\n").append(it).append("\n") }
            if (degraded) append("\n注意：有层没按 [[JUDGE]] 协议给出判定，已按 HANDOFF 继续，结论可能不完整。\n")
        }
        FileLogger.i(TAG, "工作流「${workflow.name}」结束：state=$state，走了 $completedLayers 层")
        return ToolResult.Success(
            buildJsonObject {
                put("state", state)
                put("workflow", workflow.name)
                put("layers", workflow.layerCount)
                put("completedLayers", completedLayers)
                put("report", report.joinToString("\n"))
                put("message", body)
                if (brief.isNotBlank()) put("brief", brief)
            }
        )
    }

    private fun renderDetail(workflow: TeamWorkflow, scope: String): String = buildString {
        append("工作流「").append(workflow.name).append("」（")
            .append(if (scope == "GLOBAL") "全局" else "项目").append("）")
        if (workflow.description.isNotBlank()) append("：").append(workflow.description)
        append("\n共 ").append(workflow.layerCount).append(" 层，同一对专家最多驳回 ")
            .append(workflow.maxRejectRounds).append(" 轮：\n")
        workflow.teams.forEachIndexed { index, ref ->
            append(index + 1).append(". ").append(ref.teamName)
            append("（协调者：").append(ref.lead ?: "由团队首个角色兼任").append("；")
            append(if (ref.acceptsReject) "可驳回上游" else "不许驳回上游").append("）\n")
        }
        if (workflow.charter.isNotBlank()) append("工作流公约：").append(workflow.charter).append("\n")
        append("文件：").append(workflow.filePath ?: "-").append("\n")
        append("执行：task(action=\"team_run\", workflow=\"").append(workflow.name).append("\", goal=\"<本次目标>\")")
    }

    private fun arg(args: Map<String, JsonElement>, key: String): String =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private companion object {
        const val TAG = "WorkflowRunner"

        /** 上游事实累计上限：约两千字中文，够下游判断，又不至于把每层 prompt 撑成上万 token。 */
        const val BRIEF_MAX_CHARS = 6000
    }
}
