package com.aharou.feature.agent.domain.tool.goal

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.GoalStatus
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import javax.inject.Inject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 结束会话级持续目标（goal）的自主循环。
 *
 * 只在目标处于 [GoalStatus.ACTIVE] 时可用：AI 判定目标已达成用 complete，判定受阻用 blocked。
 * 其余停止路径（暂停/清除/预算耗尽）由宿主侧处理，不经过本工具。
 */
class GoalStatusTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = GOAL_STATUS_TOOL_NAME

    override val description = "结束当前会话的持续目标循环：action=\"complete\" 表示目标已达成，" +
        "action=\"blocked\" 表示受阻且无法继续。调用前必须做完成审计——逐条核对目标的每个要求，" +
        "每条都要有工作区里的权威证据（读到的文件、命令输出、通过的测试），证据不足一律不算完成。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：'complete' 目标已达成，'blocked' 受阻无法继续",
            required = true,
            enum = listOf("complete", "blocked")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "结论依据：完成时列出目标各条要求对应的证据；受阻时说明阻塞原因与已尝试的办法",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        val sessionId = context.sessionId
            ?: return ToolResult.Error("当前没有会话上下文", "NO_SESSION")
        val entity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("会话不存在: $sessionId", "SESSION_NOT_FOUND")
        if (entity.goal.isNullOrBlank()) {
            return ToolResult.Error("当前会话没有设定目标，无需调用本工具", "NO_GOAL")
        }
        if (entity.goalStatus != GoalStatus.ACTIVE.name) {
            return ToolResult.Error("目标当前状态为 ${entity.goalStatus}，循环已停止", "GOAL_NOT_ACTIVE")
        }

        val status = when (action) {
            "complete" -> GoalStatus.COMPLETED
            "blocked" -> GoalStatus.BLOCKED
            else -> return ToolResult.Error("无效的 action: $action，只能是 'complete' 或 'blocked'", "INVALID_ACTION")
        }
        chatSessionDao.updateGoalStatus(sessionId, status.name)

        val tail = reason.takeIf { it.isNotEmpty() }?.let { "（依据：$it）" }.orEmpty()
        val text = when (status) {
            GoalStatus.COMPLETED -> "目标已标记为完成，自主循环结束$tail"
            else -> "目标已标记为受阻，自主循环结束$tail"
        }
        return ToolResult.Success(JsonPrimitive(text))
    }
}

/** 工具名常量：工具集过滤与 PlanModeTool 的互斥判断都要用。 */
const val GOAL_STATUS_TOOL_NAME = "goalStatus"
