package com.aharou.feature.agent.domain.subagent

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.presentation.MessageRole
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 子代理收尾的统一检查：结论文本、claim 裁定、终止判定与 token 用量一次算好。
 *
 * 此前「子代理说完成」就等于完成——终态只有主代理主动 `task(action="read")` 才看得到，
 * 不读就发现不了虚报。这里把 [SubAgentClaimVerifier] 与 [SubAgentConclusionJudge] 的判定
 * 编排到一处，供会话收尾（`AgentTurnRunner`）与 `task` 读取路径共用，避免两边各写一份而逐渐分叉。
 *
 * 只做编排与证据收集，判定/核验一律走上面两个组件，不在本文件里另立一套规则。
 */
object SubAgentResultInspector {

    private const val TAG = "SubAgentResultInspector"

    /** 一次收尾检查的结果。 */
    data class Result(
        /** 结论文本，已剥离 claim 协议块——原始 JSON 不进父上下文。 */
        val conclusion: String,
        /** claim 核验报告；子代理未声明 claim 时为 null。 */
        val report: SubAgentClaimVerifier.Report?,
        /** 终止判定。 */
        val verdict: SubAgentVerdict,
        /** 子会话累计输入 token（按 ASSISTANT 行汇总）。 */
        val inputTokens: Int,
        /** 子会话累计输出 token（按 ASSISTANT 行汇总）。 */
        val outputTokens: Int,
    ) {
        /** 是否因 claim 验收项核验不上而降级。 */
        val downgraded: Boolean get() = report?.downgraded == true

        /** 收尾事件 detail 用的概要：终态、原因、claim 裁定与 token 用量。 */
        fun renderDetail(): String = buildString {
            append("终止状态：").append(verdict.termination.name)
            if (verdict.reason.isNotBlank()) append("（").append(verdict.reason).append("）")
            append("\n交付判定：").append(if (verdict.accepted) "已交付" else "未交付")
            report?.let { append("\n").append(SubAgentClaimVerifier.renderAdjudication(it)) }
            append("\ntoken 用量：输入 ").append(inputTokens).append(" / 输出 ").append(outputTokens)
        }
    }

    /**
     * 检查一个子会话的收尾状态。
     *
     * @param sessionId 子会话 id，仅用于非交付时的诊断日志。
     * @param messages 子会话消息（[AgentMessageEntity] 列表），由调用方按会话读取后传入。
     */
    suspend fun inspect(sessionId: String, messages: List<AgentMessageEntity>): Result {
        // 最后一条有正文的助手回复才是结论；跳过 reasoning-only 的中间消息。
        val lastAssistant = messages.lastOrNull {
            it.role == MessageRole.ASSISTANT.name && it.content.isNotBlank()
        }
        val conclusion = lastAssistant?.let { SubAgentClaimVerifier.stripClaimBlock(it.content) }.orEmpty()
        val report = lastAssistant?.let { msg ->
            SubAgentClaimVerifier.parse(msg.content)?.let { claim ->
                SubAgentClaimVerifier.verify(claim, collectEvidence(messages))
            }
        }
        val verdict = SubAgentConclusionJudge.judge(
            conclusion = conclusion,
            evidence = collectConclusionEvidence(messages),
            claimDowngraded = report?.downgraded == true,
        )
        if (!verdict.accepted) {
            FileLogger.w(
                TAG,
                "子代理收尾未交付 session=$sessionId termination=${verdict.termination} reason=${verdict.reason}"
            )
        }
        val assistantRows = messages.filter { it.role == MessageRole.ASSISTANT.name }
        return Result(
            conclusion = conclusion,
            report = report,
            verdict = verdict,
            inputTokens = assistantRows.sumOf { it.inputTokens },
            outputTokens = assistantRows.sumOf { it.outputTokens },
        )
    }

    /**
     * 从子会话消息里收集核验证据：把 ASSISTANT 的 tool_calls 与对应 TOOL 行的成败配对，
     * 只采信执行成功的调用——命令取 `command` 原文，落盘取 `path`。
     */
    private fun collectEvidence(messages: List<AgentMessageEntity>): SubAgentClaimVerifier.Evidence {
        val callSucceeded = mutableMapOf<String, Boolean>()
        messages.filter { it.role == MessageRole.TOOL.name }.forEach { m ->
            m.toolCallId?.let { callSucceeded[it] = !m.isError }
        }

        val commands = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        messages.filter { it.role == MessageRole.ASSISTANT.name }.forEach { m ->
            val calls = m.toolCallsJson?.let {
                runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<ToolCall>>(it) }.getOrNull()
            }.orEmpty()
            calls.forEach { call ->
                if (callSucceeded[call.id] != true) return@forEach
                when (call.name) {
                    "Bash" -> (call.arguments["command"] as? JsonPrimitive)?.contentOrNull
                        ?.let { commands += it.trim() }
                    "writeFile", "editFile" -> (call.arguments["path"] as? JsonPrimitive)?.contentOrNull
                        ?.let { paths += it.trim() }
                }
            }
        }
        return SubAgentClaimVerifier.Evidence(commands, paths)
    }

    /**
     * 结算证据：被写租约拦截的写入，以及「写过但最后一次失败」的落盘目标。
     * 只认结构化写工具（writeFile / editFile）——shell 写不在闸门范围，也无从判定。
     */
    private fun collectConclusionEvidence(messages: List<AgentMessageEntity>): SubAgentEvidence {
        val calls = mutableMapOf<String, Pair<String, String?>>()
        messages.filter { it.role == MessageRole.ASSISTANT.name }.forEach { m ->
            val json = m.toolCallsJson ?: return@forEach
            runCatching { Json { ignoreUnknownKeys = true }.decodeFromString<List<ToolCall>>(json) }.getOrNull()
                ?.forEach { call ->
                    val path = (call.arguments["path"] as? JsonPrimitive)?.contentOrNull?.trim()
                    calls[call.id] = call.name to path
                }
        }

        val blocked = mutableListOf<String>()
        val lastSucceeded = mutableMapOf<String, Boolean>()
        messages.filter { it.role == MessageRole.TOOL.name }.forEach { m ->
            val callId = m.toolCallId ?: return@forEach
            val (name, path) = calls[callId] ?: return@forEach
            if (name != "writeFile" && name != "editFile") return@forEach
            val target = path?.takeIf { it.isNotBlank() } ?: return@forEach
            if (m.isError && m.content.contains(WriteLease.DENIAL_PREFIX)) {
                if (target !in blocked) blocked += target
                return@forEach
            }
            lastSucceeded[target] = !m.isError
        }
        return SubAgentEvidence(
            blockedWrites = blocked,
            unresolvedWriteFailures = lastSucceeded.filterValues { !it }.keys.toList(),
        )
    }
}
