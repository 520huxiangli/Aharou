package com.aharou.feature.agent.domain.tool.rule

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.rule.RuleRepository
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 让 AI 按需加载一个规则块的完整正文。
 *
 * 系统提示里只注入规则块的 name+description 清单；AI 判断某条规则适用于当前任务时，
 * 调用本工具拿到正文再按其行事（与 [com.aharou.feature.agent.domain.tool.skill.LoadSkillTool] 同一套路）。
 */
class LoadRuleTool @Inject constructor(
    private val ruleRepository: RuleRepository
) : AgentTool() {
    private companion object {
        const val TAG = "LoadRuleTool"
    }

    override val name = "loadRule"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定规则块的完整正文。当系统提示「按需规则」清单中的规则适用于当前任务时使用。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "rule_name" to ToolParameter(
            name = "rule_name",
            type = ParameterType.STRING,
            description = "要加载的规则块名称（与系统提示「按需规则」清单中的名称一致）。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        val ruleName = args["rule_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (ruleName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: rule_name", "MISSING_RULE_NAME")
        }

        val text = ruleRepository.loadText(ruleName)
        if (text == null) {
            val available = ruleRepository.listRules().joinToString(", ") { it.name }
            FileLogger.w(TAG, "loadRule 未找到: $ruleName，可用: $available")
            return ToolResult.Error(
                "未找到规则块「$ruleName」。可用规则: ${available.ifEmpty { "（无）" }}",
                "RULE_NOT_FOUND"
            )
        }

        FileLogger.d(TAG, "loadRule 加载成功: $ruleName (${text.length} 字符)")
        return ToolResult.Success(JsonPrimitive(text))
    }
}
