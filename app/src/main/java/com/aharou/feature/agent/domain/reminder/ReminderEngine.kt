package com.aharou.feature.agent.domain.reminder

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.domain.tool.toTransportString
import com.aharou.feature.agent.domain.workflow.StatefulAgentWorkflow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 规则提醒的匹配与渲染。
 *
 * 提醒只做「事后提示」：工具层把提示追加在结果末尾，收尾层在一轮将结束时补给模型。
 * 任何一步匹配失败（正则写错、配置缺字段）都只跳过那一条规则，不抛给调用方。
 */
@Singleton
class ReminderEngine @Inject constructor(
    private val repository: ReminderRepository,
) {
    /** 本轮工具调用的一条记录，供 turn_end 判定 trigger / resolve。 */
    private data class ToolTrace(
        val toolName: String,
        val argsText: String,
        val resultText: String,
        val isError: Boolean,
    )

    /** 工具层便捷入口：直接把工具结果交给提醒引擎，返回值是追加了提醒的结果文本。 */
    suspend fun decorate(
        toolName: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        result: ToolResult,
        projectRoot: String?,
    ): String = decorateToolResult(toolName, args, result.toTransportString(), result is ToolResult.Error, projectRoot)

    /**
     * 工具层：命中则把提醒追加到工具结果末尾并返回，没命中原样返回。
     * 已在结果里出现过同一条提醒时不重复追加（同一条工具结果会被多次读，重复追加等于刷屏）。
     */
    suspend fun decorateToolResult(
        toolName: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        transportJson: String,
        isError: Boolean,
        projectRoot: String?,
    ): String {
        val rules = runCatching { repository.rules(projectRoot) }.getOrElse { emptyList() }
            .filter { it.layer == ReminderLayer.TOOL && it.appliesTo(toolName) }
        if (rules.isEmpty()) return transportJson

        val argsText = runCatching { JsonObject(args).toString() }.getOrDefault("")
        val boundedResult = bounded(transportJson)
        val hits = rules.filter { rule ->
            ReminderMatcher.matchesAll(rule.conditions, rule.match, argsText, boundedResult, isError)
        }.filterNot { transportJson.contains(it.message) }
        if (hits.isEmpty()) return transportJson

        val text = hits.joinToString("\n") { render(it) }
        FileLogger.i(TAG, "规则提醒命中（工具层）$toolName：${hits.joinToString(",") { it.id }}")
        return transportJson + "\n\n" + text
    }

    /**
     * 收尾层：本轮有工具调用命中 trigger、且此后没有任何调用满足 resolve 时返回提醒文本。
     *
     * 只看最近 [TRACE_WINDOW] 条消息：提醒的判定对象是「刚才发生的事」，一轮里工具调用再多也不会
     * 差出这个量级；反过来一次扫全历史会把上一轮早已解决掉的问题重新翻出来。
     */
    suspend fun turnEndReminder(
        messages: List<AgentMessage>,
        projectRoot: String?,
        action: StatefulAgentWorkflow.AgentAction,
    ): String? {
        // 只在「模型给出不带工具调用的最终回复」那一刻才需要算，其余 action 直接退出。
        if (action !is StatefulAgentWorkflow.AgentAction.LlmResponse || action.response.toolCalls.isNotEmpty()) return null
        val rules = runCatching { repository.rules(projectRoot) }.getOrElse { emptyList() }
            .filter { it.layer == ReminderLayer.TURN_END }
        if (rules.isEmpty()) return null

        val traces = collectTraces(messages)
        if (traces.isEmpty()) return null

        return rules.firstNotNullOfOrNull { rule ->
            val triggerGroups = rule.conditions.filterIsInstance<ReminderCondition.TriggerCondition>()
            val resolveGroups = rule.conditions.filterIsInstance<ReminderCondition.ResolveCondition>()
            if (triggerGroups.isEmpty()) return@firstNotNullOfOrNull null

            // 按顺序判定：只有「触发之后」才出现解除条件才算真的解除，否则先成功后失败会被当成已经办完。
            val triggerIndex = traces.indexOfLast { trace ->
                triggerGroups.any {
                    ReminderMatcher.matchesAll(it.conditions, it.match, trace.argsText, trace.resultText, trace.isError)
                }
            }
            if (triggerIndex < 0) return@firstNotNullOfOrNull null

            val resolved = traces.drop(triggerIndex + 1).any { trace ->
                resolveGroups.any {
                    ReminderMatcher.matchesAll(it.conditions, it.match, trace.argsText, trace.resultText, trace.isError)
                }
            }
            if (resolved) return@firstNotNullOfOrNull null
            if (messages.any { it is AgentMessage.UserMessage && it.content == render(rule) }) return@firstNotNullOfOrNull null

            FileLogger.i(TAG, "规则提醒命中（收尾层）：${rule.id}")
            render(rule)
        }
    }

    /** 把 assistant 的 toolCall 与随后的 tool 结果按 id 配对；未配对（被中断）的调用不参与判定。 */
    private fun collectTraces(messages: List<AgentMessage>): List<ToolTrace> {
        val window = messages.takeLast(TRACE_WINDOW)
        val results = window.filterIsInstance<AgentMessage.ToolResultMessage>()
            .associateBy { it.id }
        return window.filterIsInstance<AgentMessage.AssistantMessage>()
            .flatMap { it.toolCalls }
            .mapNotNull { call ->
                val result = results[call.id] ?: return@mapNotNull null
                ToolTrace(
                    toolName = call.name,
                    argsText = runCatching { JsonObject(call.arguments).toString() }.getOrDefault(""),
                    resultText = bounded(result.result),
                    isError = isErrorPayload(result.result),
                )
            }
    }

    private fun render(rule: ReminderRule): String = buildString {
        append("【规则提醒】").append(rule.message)
        if (rule.refs.isNotEmpty()) {
            append("\n（依据：").append(rule.refs.joinToString("、")).append("）")
        }
    }

    /**
     * 大输出只留首尾各 [MATCH_EDGE_CHARS] 字符参与匹配：一次构建/搜索的输出动辄几十万字符，
     * 整串跑正则会把主线程外的 IO 线程也拖死；出错信息基本都在这两段里。
     */
    private fun bounded(text: String): String =
        if (text.length <= MATCH_EDGE_CHARS * 2) text
        else text.take(MATCH_EDGE_CHARS) + "\n…\n" + text.takeLast(MATCH_EDGE_CHARS)

    /** 工具结果的 transport JSON 里 status=error 即为失败（工具结果统一由 toTransportString 产出）。 */
    private fun isErrorPayload(transport: String): Boolean = runCatching {
        val status = transport.trim().let { text ->
            if (text.startsWith("{")) text else return@runCatching false
        }
        val root = ReminderJson.parseToJsonElement(status).jsonObject["status"] as? JsonPrimitive
        root?.contentOrNull == "error"
    }.getOrDefault(false)

    private companion object {
        const val TAG = "ReminderEngine"
        const val MATCH_EDGE_CHARS = 8 * 1024
        const val TRACE_WINDOW = 60
    }
}
