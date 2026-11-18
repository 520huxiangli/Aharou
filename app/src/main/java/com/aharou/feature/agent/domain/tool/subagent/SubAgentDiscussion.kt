package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.core.util.FileLogger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 多智能体讨论：同一议题让 2–4 个子代理分轮发言，每轮都能看到此前所有人的发言。
 *
 * 子代理之间不会互相唤醒，轮次推进只能由发起方串行驱动——所以这里每轮都是
 * 「发一句话 → 等全部发言完 → 拼下一轮上下文」，中间不并行发第二轮，否则后发言的人看不到前文。
 */
object SubAgentDiscussion {

    private const val TAG = "SubAgentDiscussion"
    private const val MIN_PARTICIPANTS = 2
    private const val MAX_PARTICIPANTS = 4
    private const val DEFAULT_ROUNDS = 2
    private const val MAX_ROUNDS = 5
    /** 单轮发言进入下一轮上下文时的截断长度：四个角色乘上轮数，不截断会撑爆 prompt。 */
    private const val EXCERPT_MAX = 1500
    /** 最终记录里保留的发言长度。 */
    private const val RECORD_MAX = 3000

    private data class Participant(
        val name: String,
        val agent: String?,
        val department: String?,
        val agentQuery: String?,
        val stance: String?,
        var id: String = ""
    )

    private data class Turn(val name: String, val text: String)

    suspend fun run(tool: TaskTool, args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        if (context.sessionId == null) return ToolResult.Error("缺少会话上下文", "NO_SESSION")

        val topic = text(args["topic"])
        if (topic.isEmpty()) return ToolResult.Error("discuss 需要 topic（讨论主题）", "INVALID_ARGS")

        val rounds = (args["rounds"] as? JsonPrimitive)?.jsonPrimitive?.intOrNull
            ?.coerceIn(1, MAX_ROUNDS) ?: DEFAULT_ROUNDS

        val raw = args["participants"] as? JsonArray
            ?: return ToolResult.Error("discuss 需要 participants 数组，每项至少给 name", "INVALID_ARGS")
        val participants = raw.mapNotNull { it as? JsonObject }.mapNotNull { obj ->
            val name = text(obj["name"])
            if (name.isEmpty()) null else Participant(
                name = name,
                agent = text(obj["agent"]).ifBlank { null },
                department = text(obj["department"]).ifBlank { null },
                agentQuery = text(obj["agentQuery"]).ifBlank { null },
                stance = text(obj["stance"]).ifBlank { null }
            )
        }
        if (participants.size !in MIN_PARTICIPANTS..MAX_PARTICIPANTS) {
            return ToolResult.Error(
                "参与者需要 $MIN_PARTICIPANTS–$MAX_PARTICIPANTS 个（每项给 name，可选 agent / department / agentQuery / stance）",
                "INVALID_ARGS"
            )
        }

        val created = participants.filter { p ->
            val createArgs = buildJsonObject {
                put("action", "create")
                put("description", p.name.take(30))
                put("prompt", openingPrompt(p, topic, rounds))
                p.agent?.let { put("agent", it) }
                p.department?.let { put("department", it) }
                p.agentQuery?.let { put("agentQuery", it) }
            }
            val result = tool.createSubagent(createArgs, context)
            val id = ((result as? ToolResult.Success)?.data as? JsonObject)
                ?.get("id")?.let { (it as? JsonPrimitive)?.contentOrNull }
            if (id.isNullOrBlank()) {
                FileLogger.w(TAG, "参与者「${p.name}」派发失败：${(result as? ToolResult.Error)?.message.orEmpty()}")
                false
            } else {
                p.id = id
                true
            }
        }
        if (created.size < MIN_PARTICIPANTS) {
            return ToolResult.Error("参与者派发失败过多（成功 ${created.size} 个），讨论无法进行", "SUBAGENT_DISPATCH_FAILED")
        }

        val transcript = mutableListOf<List<Turn>>()
        for (round in 1..rounds) {
            if (round > 1) {
                val context80 = (transcript.lastOrNull().orEmpty()).joinToString("\n\n") { turn ->
                    "【${turn.name}】${turn.text.take(EXCERPT_MAX)}"
                }
                for (p in created) {
                    tool.sendToSubagent(
                        buildJsonObject {
                            put("action", "send")
                            put("id", p.id)
                            put("message", nextRoundPrompt(round, rounds, context80))
                        },
                        context
                    )
                }
            }
            val finished = tool.awaitSubAgents(created.map { it.id }.toSet())
            val turns = created.map { p ->
                val event = finished[p.id]
                val text = tool.readSubagentOutput(p.id, context).trim()
                val shown = when {
                    event?.type == SubAgentEventType.FAILED -> text.ifBlank { "（本轮执行失败）" }
                    event == null -> text.ifBlank { "（本轮未在等待时限内结束）" }
                    text.isBlank() -> "（本轮没有产出）"
                    else -> text
                }
                Turn(p.name, shown)
            }
            transcript += turns
        }

        val record = buildString {
            appendLine("# 讨论记录：$topic")
            appendLine()
            appendLine("参与者：${created.joinToString("、") { it.name }}｜轮次：$rounds")
            transcript.forEachIndexed { index, turns ->
                appendLine()
                appendLine("## 第 ${index + 1} 轮")
                turns.forEach { turn ->
                    appendLine()
                    appendLine("### ${turn.name}")
                    appendLine(turn.text.take(RECORD_MAX))
                }
            }
        }

        return ToolResult.Success(
            buildJsonObject {
                put("state", "done")
                put("rounds", rounds)
                put("participants", created.joinToString("、") { it.name })
                put("transcript", record)
                put("message", record)
            }
        )
    }

    private fun openingPrompt(p: Participant, topic: String, rounds: Int): String = buildString {
        appendLine("你在参加一场多智能体讨论，角色是「${p.name}」。")
        p.stance?.let { appendLine("你的立场与职责：$it") }
        appendLine()
        appendLine("议题：$topic")
        appendLine()
        appendLine("这是第 1 轮，共 $rounds 轮。请给出你的初始观点：结论先行，再列关键理由与风险。")
        appendLine("不要复述议题，不要写开场客套话；观点要能站住，避免模棱两可。")
    }

    private fun nextRoundPrompt(round: Int, rounds: Int, context: String): String = buildString {
        appendLine("这是第 $round 轮（共 $rounds 轮）。此前的发言如下：")
        appendLine()
        appendLine(context)
        appendLine()
        appendLine("请基于以上内容继续：明确同意或反对哪些点、补充它们漏掉的关键之处，最后给出你的本轮结论。")
        appendLine("如果别人已经说服你，就直接说明你改变了哪一点；不要为了显得有观点而重复。")
    }

    private fun text(value: JsonElement?): String =
        (value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
}
