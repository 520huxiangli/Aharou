package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.subagent.AgentDefinition
import com.aharou.feature.agent.domain.subagent.AgentDefinitionRepository
import com.aharou.feature.agent.domain.subagent.AgentTeamEntry
import com.aharou.feature.agent.domain.subagent.AgentTeamRepository
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * 团队预设的两个工具动作：`teams`（列清单/详情）与 `team_run`（按预设跑）。
 *
 * 抽在 TaskTool 外面是为了让它保持入口职责：这里负责读预设、查角色、按并发上限分批展开，再把
 * pipeline 交给调用方执行——执行仍走 TaskTool 的 `runPipeline`，分层调度与写冲突拦截不重复实现。
 */
@Singleton
class TeamTasks @Inject constructor(
    private val teamRepository: AgentTeamRepository,
    private val agentDefinitionRepository: AgentDefinitionRepository,
    private val maxRunningSource: MaxRunningSource
) {

    fun list(args: Map<String, JsonElement>): ToolResult {
        val entries = loadEntries() ?: return ToolResult.Error("读取团队预设失败", "TEAM_LIST_FAILED")
        val target = (args["team"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (target.isNotEmpty()) {
            val entry = entries.firstOrNull { it.team.name.equals(target, ignoreCase = true) }
                ?: return ToolResult.Error(notFoundMessage(entries, target), "TEAM_NOT_FOUND")
            return ToolResult.Success(
                buildJsonObject {
                    put("content", TeamRunner.renderDetail(entry))
                    put("name", entry.team.name)
                    put("stages", entry.team.stages.size)
                    put("tasks", entry.team.taskCount)
                }
            )
        }
        if (entries.isEmpty()) {
            return ToolResult.Error(
                "当前没有团队预设。在 ${teamRepository.globalRoot}（全局）或 " +
                    "${teamRepository.projectRoot}（当前工作区）下新建 <名>.md：frontmatter 写 stages、" +
                    "正文写团队公约，格式见文档「子代理 → 团队预设」一节。",
                "TEAM_LIST_EMPTY"
            )
        }
        return ToolResult.Success(
            buildJsonObject {
                put("content", TeamRunner.renderList(entries))
                put("count", entries.size)
            }
        )
    }

    /**
     * 按预设展开并执行。[exec] 接收展开好的 pipeline 数组，由调用方交给 runPipeline 跑。
     */
    suspend fun run(
        args: Map<String, JsonElement>,
        exec: suspend (JsonArray) -> ToolResult
    ): ToolResult {
        val teamName = (args["team"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (teamName.isEmpty()) {
            return ToolResult.Error("team_run 需要 team（团队名，先用 task(action=\"teams\") 查）", "MISSING_TEAM")
        }
        val goal = (args["goal"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (goal.isEmpty()) {
            return ToolResult.Error("team_run 需要 goal（本次团队要达成的目标）", "MISSING_GOAL")
        }

        val entries = loadEntries() ?: return ToolResult.Error("读取团队预设失败", "TEAM_LIST_FAILED")
        val team = entries.firstOrNull { it.team.name.equals(teamName, ignoreCase = true) }?.team
            ?: return ToolResult.Error(notFoundMessage(entries, teamName), "TEAM_NOT_FOUND")

        val enabled: List<AgentDefinition> = try {
            agentDefinitionRepository.listEnabled().map { it.definition }
        } catch (e: Exception) {
            FileLogger.e(TAG, "读取子代理定义失败", e)
            return ToolResult.Error("读取子代理定义失败：${e.message ?: "未知错误"}", "AGENT_LIST_FAILED")
        }

        return when (val plan = TeamRunner.build(team, goal, enabled, maxRunningSource.maxRunning())) {
            is TeamRunner.Plan.Rejected -> ToolResult.Error(plan.message, plan.code)
            is TeamRunner.Plan.Ready -> {
                FileLogger.i(
                    TAG,
                    "团队「${team.name}」开始执行：${team.stages.size} 阶段 → ${plan.layers} 层 / ${plan.taskCount} 任务"
                )
                exec(plan.pipeline)
            }
        }
    }

    private fun loadEntries(): List<AgentTeamEntry>? = try {
        teamRepository.listAll()
    } catch (e: Exception) {
        FileLogger.e(TAG, "读取团队预设失败", e)
        null
    }

    private fun notFoundMessage(entries: List<AgentTeamEntry>, target: String): String =
        if (entries.isEmpty()) {
            "团队预设不存在：$target；当前也没有任何预设。在 ${teamRepository.globalRoot} 下新建 <名>.md 即可。"
        } else {
            "团队预设不存在：$target。可用团队：${entries.joinToString("、") { it.team.name }}"
        }

    private companion object {
        const val TAG = "TeamTasks"
    }
}
