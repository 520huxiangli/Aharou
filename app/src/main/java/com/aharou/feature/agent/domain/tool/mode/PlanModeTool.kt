package com.aharou.feature.agent.domain.tool.mode

import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.PendingToolPermission
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolPermissionPolicy
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 进入或退出 PLAN（计划）模式。退出时恢复到进入 PLAN 之前的模式（见 [AgentContext.modeBeforePlan]）。
 */
class PlanModeTool @Inject constructor(
    private val chatSessionDao: ChatSessionDao
) : AbstractContextualTool() {

    override val name = "planMode"
    override val description = "进入或退出 PLAN（计划）模式。action=\"enter\"：进入计划模式，转为只读探索、构思复杂改动方案，不要在 BUILD 模式直接写代码；action=\"exit\"：计划已经完成、要开始动手时退出计划模式。退出后会自动恢复到进入 PLAN 之前的模式（从 AUTO 进入就回到 AUTO，从 BUILD 进入就回到 BUILD），无需你指定目标模式。进入与退出都需要用户授权，退出还会额外经过计划审查面板确认。注意：AUTO（自动）模式只能由用户在界面上手动进入，本工具无法进入 AUTO；但处于 AUTO 模式时可以 action=\"enter\" 进入 PLAN（这是 AI 退出 AUTO 的唯一路径）。"
    override val permissionPolicy = ToolPermissionPolicy.ASK
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            name = "action",
            type = ParameterType.STRING,
            description = "操作：'enter' 进入 PLAN 模式（只读规划），'exit' 退出 PLAN 模式（恢复到进入前的模式）",
            required = true,
            enum = listOf("enter", "exit")
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "进入或退出 PLAN 模式的理由，将展示给用户",
            required = true
        )
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val action = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase()
            ?: return ToolResult.Error("缺少必需参数: action", "MISSING_ACTION")

        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: return ToolResult.Error("缺少必需参数: reason", "MISSING_REASON")

        val entering = when (action) {
            "enter" -> true
            "exit" -> false
            else -> return ToolResult.Error("无效的 action: $action，只能是 'enter' 或 'exit'", "INVALID_ACTION")
        }

        if (entering && context.mode == AgentMode.PLAN) {
            return ToolResult.Success(JsonPrimitive("当前已处于 PLAN 模式，无需进入。"))
        }
        if (!entering && context.mode != AgentMode.PLAN) {
            return ToolResult.Error("当前不在 PLAN 模式，无法退出", "NOT_IN_PLAN")
        }

        val sessionId = context.sessionId
            ?: return ToolResult.Error("未关联会话 ID，无法切换模式", "NO_SESSION")

        val sessionEntity = chatSessionDao.getById(sessionId)
            ?: return ToolResult.Error("找不到会话记录", "SESSION_NOT_FOUND")

        // 进入 PLAN 时记下当时的模式，退出时恢复到它：从 AUTO 进入 PLAN 的规划往返结束后应回到 AUTO，
        // 而不是一律落回 BUILD。该字段只在处于 PLAN 时被读取，每次进入 PLAN 都会覆写，因此无需在退出时清理。
        val effectiveMode: AgentMode
        val modeBeforePlan: String?
        if (entering) {
            effectiveMode = AgentMode.PLAN
            modeBeforePlan = context.mode.name
        } else {
            effectiveMode = sessionEntity.modeBeforePlan
                ?.let { runCatching { AgentMode.valueOf(it) }.getOrNull() }
                ?: AgentMode.BUILD
            modeBeforePlan = sessionEntity.modeBeforePlan
        }

        // 保存到数据库。UI 层通过 flow 监听，会自动更新外观与后续流程的上下文。
        chatSessionDao.upsert(sessionEntity.copy(mode = effectiveMode.name, modeBeforePlan = modeBeforePlan))

        return ToolResult.Success(
            JsonPrimitive(
                if (entering) "已进入 PLAN 模式。" else "已退出 PLAN 模式，恢复为 ${effectiveMode.name} 模式。"
            )
        )
    }

    override fun buildPermissionRequest(
        callId: String,
        args: Map<String, JsonElement>,
        argsPreview: String
    ): PendingToolPermission {
        val entering = args["action"]?.jsonPrimitive?.contentOrNull?.trim()?.equals("enter", ignoreCase = true) ?: true
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull ?: "无理由"
        val label = if (entering) "进入 PLAN 模式" else "退出 PLAN 模式"

        return PendingToolPermission(
            id = callId,
            toolName = name,
            title = "计划模式申请",
            summary = "AI 申请$label",
            details = "操作：$label\n\n申请理由：$reason",
            argsPreview = argsPreview,
            rememberablePatterns = emptyList()
        )
    }
}