package com.aharou.feature.agent.domain.steward

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.workflow.AgentWorkflow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主管判定：拿「用户最初那句」和「AI 最后一次汇报」问一次模型——任务到底做完没有。
 *
 * 走会话链路既有的一次性调用入口 [AgentWorkflow.completeOnce]，不自己拼 HTTP：
 * provider 解析、多 Key 轮换、重试与用量记账都跟着主链路走，换供应商时这里不用改。
 */
@Singleton
class StewardJudge @Inject constructor(
    private val agentWorkflow: AgentWorkflow,
) {

    /**
     * @return 判定结果；超时、模型不可用、回的不是 JSON 一律返回 null（由调用方静默不续发）。
     */
    suspend fun judge(originalInstruction: String, lastReport: String, sessionId: String? = null): StewardJudgement? {
        if (originalInstruction.isBlank()) return null
        return try {
            val prompt = JUDGE_PROMPT
                .replace(PLACEHOLDER_INSTRUCTION, originalInstruction)
                .replace(PLACEHOLDER_REPORT, lastReport.ifBlank { NO_REPORT_PLACEHOLDER })
            val raw = withTimeoutOrNull(JUDGE_TIMEOUT_MS) {
                agentWorkflow.completeOnce(systemPrompt = SYSTEM_PROMPT, userText = prompt, sessionId = sessionId)
            }
            if (raw == null) {
                FileLogger.w(TAG, "主管判定超时或无输出，跳过本轮续发")
                return null
            }
            val judgement = parse(raw)
            if (judgement == null) {
                FileLogger.w(TAG, "主管判定没回可解析的 JSON，跳过本轮续发: ${raw.take(200)}")
            }
            judgement
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.w(TAG, "主管判定失败，跳过本轮续发: ${e.message}", e)
            null
        }
    }

    /** 宽容解析：模型常把 JSON 包在代码块里或前后带解释，字段也可能缺。 */
    private fun parse(raw: String): StewardJudgement? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { Json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull()
            ?: return null
        val completed = readBoolean(obj["completed"]) ?: return null
        val reason = (obj["reason"] as? JsonPrimitive)?.content?.trim().orEmpty()
        val next = (obj["next_instruction"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        return StewardJudgement(completed = completed, reason = reason, nextInstruction = next)
    }

    /** 兼容 true / "true" / "yes" 这类写法；判不出来返回 null（缺失即视为判不出来，不假装完成）。 */
    private fun readBoolean(element: JsonElement?): Boolean? =
        when ((element as? JsonPrimitive)?.content?.trim()?.lowercase()) {
            "true", "yes", "1" -> true
            "false", "no", "0" -> false
            else -> null
        }

    companion object {
        private const val TAG = "StewardJudge"

        /** 判定是旁路调用，超时就当没判出来，绝不拖住会话主链路。 */
        const val JUDGE_TIMEOUT_MS = 60_000L

        private const val SYSTEM_PROMPT = "You are a task supervisor. Follow the instruction and return only the requested JSON object."
        private const val NO_REPORT_PLACEHOLDER = "(the AI produced no text reply this turn)"
        private const val PLACEHOLDER_INSTRUCTION = "{instruction}"
        private const val PLACEHOLDER_REPORT = "{report}"

        /** 判定提示词：照搬参考实现（RikkaHub Steward Mode）的原文，内联在此以免多一个资产文件。 */
        const val JUDGE_PROMPT = """You are a task supervisor. The user gave an instruction and the AI has given an execution report. Determine whether the user's instruction has been fully completed.

User original instruction:
{instruction}

AI last execution report:
{report}

Return only a JSON object, with no additional content:
{
  "completed": true or false,
  "reason": "one-sentence justification",
  "next_instruction": "the next instruction when not completed; leave as an empty string when completed"
}"""
    }
}