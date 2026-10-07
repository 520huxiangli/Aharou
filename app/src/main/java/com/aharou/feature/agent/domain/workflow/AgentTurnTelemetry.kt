package com.aharou.feature.agent.domain.workflow

import android.os.SystemClock
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolCall
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一轮 agent 处理的阶段枚举。[wire] 是落盘日志里的稳定字面量，改动它等于破坏既有日志的 grep 口径。
 *
 * 一轮（[AgentTurnTelemetry] 的生命周期）从主循环开始到 [TURN_END] / [TURN_FAILED]，
 * 中间会穿插多次 LLM 调用（[LLM_REQUEST] → [LLM_FIRST_BYTE] → [LLM_STREAM_END]）
 * 与若干工具批（[TOOL_BATCH_START] → [TOOL_BATCH_END]）。
 */
enum class AgentTurnPhase(val wire: String) {
    TURN_START("turn_start"),
    HISTORY_READY("history_ready"),
    COMPACTION_START("compaction_start"),
    COMPACTION_END("compaction_end"),
    LLM_REQUEST("llm_request"),
    LLM_FIRST_BYTE("llm_first_byte"),
    LLM_STREAM_END("llm_stream_end"),
    TOOL_BATCH_START("tool_batch_start"),
    TOOL_BATCH_END("tool_batch_end"),
    TURN_END("turn_end"),
    TURN_FAILED("turn_failed"),
}

/**
 * 一轮 agent 处理的阶段遥测：把「准备历史 → 压缩 → 发请求 → 首字 → 收流 → 执行工具」的时间线与顺序
 * 以成对、带耗时的结构化日志打出来，替代过去「靠多行零散日志人工拼时间线」的排查方式。
 *
 * 每条记录形如：
 * ```
 * phase=llm_stream_end turn=a1b2c3d4 seq=7 elapsedMs=4210 session=<sid> call=1 textChars=812 ... durationMs=3980
 * ```
 * `elapsedMs` 是本轮开始的相对时刻，`durationMs` / `ttfbMs` 是该子阶段的自身耗时；统一用
 * `phase=` 前缀即可过滤，`grep 'turn=<id>'` 可还原单轮完整时间线。
 *
 * 只输出到 [FileLogger]，不落库、不改任何会话状态，纯观测。[TAG] 固定为 `AgentTurn`。
 */
class AgentTurnTelemetry private constructor(
    private val turnId: String,
    private val sessionId: String?,
) {
    private val startedAtElapsed = SystemClock.elapsedRealtime()
    private val seq = AtomicInteger(0)
    private val llmSeq = AtomicInteger(0)
    private val batchSeq = AtomicInteger(0)
    /** 各 LLM 调用 / 工具批的起点（elapsedRealtime），用于算子阶段自身耗时。 */
    private val llmStartElapsed = ConcurrentHashMap<Int, Long>()
    private val batchStartElapsed = ConcurrentHashMap<Int, Long>()

    /**
     * 打一个阶段点。`fields` 里值为 null 的键会被跳过；值里的换行会被压成空格，避免破坏「一行一条」。
     */
    fun mark(phase: AgentTurnPhase, vararg fields: Pair<String, Any?>) {
        val line = StringBuilder(128)
        line.append("phase=").append(phase.wire)
            .append(" turn=").append(turnId)
            .append(" seq=").append(seq.incrementAndGet())
            .append(" elapsedMs=").append(SystemClock.elapsedRealtime() - startedAtElapsed)
        if (sessionId != null) line.append(" session=").append(sessionId)
        for ((key, value) in fields) {
            if (value == null) continue
            line.append(' ').append(key).append('=').append(sanitize(value.toString()))
        }
        FileLogger.i(TAG, line.toString())
    }

    /** 历史与提示词就绪、即将进入主循环：记下本轮起点的体量，便于对照后续耗时。 */
    fun historyReady(systemPromptChars: Int, tools: Int, historyMessages: Int) =
        mark(AgentTurnPhase.HISTORY_READY,
            "systemChars" to systemPromptChars, "tools" to tools, "history" to historyMessages)

    fun compactionStart() = mark(AgentTurnPhase.COMPACTION_START)

    fun compactionEnd(compacted: Boolean, messages: Int) =
        mark(AgentTurnPhase.COMPACTION_END, "compacted" to compacted, "messages" to messages)

    /** 请求即将发出，返回本次调用的序号，供首字/收流用同一 `call=` 关联。 */
    fun llmRequest(providerId: String, model: String, messages: Int, tools: Int, inputTokens: Int): Int {
        val id = llmSeq.incrementAndGet()
        llmStartElapsed[id] = SystemClock.elapsedRealtime()
        mark(AgentTurnPhase.LLM_REQUEST,
            "call" to id, "provider" to providerId.ifBlank { null }, "model" to model,
            "messages" to messages, "tools" to tools, "inputTokens" to inputTokens)
        return id
    }

    /** 首字到达（正文/思考/工具名任一先到）：附上从请求发出到首字的 ttfb。 */
    fun llmFirstByte(callId: Int) {
        val start = llmStartElapsed[callId]
        mark(AgentTurnPhase.LLM_FIRST_BYTE, "call" to callId,
            "ttfbMs" to start?.let { SystemClock.elapsedRealtime() - it })
    }

    /** 流结束（正常收完或已拿到终态响应）：附上请求自身的总耗时与本次产出体量。 */
    fun llmStreamEnd(
        callId: Int,
        textChars: Int,
        reasoningChars: Int,
        toolCalls: Int,
        stopReason: String?,
        inputTokens: Int,
        outputTokens: Int,
        retryCount: Int,
    ) {
        val start = llmStartElapsed.remove(callId)
        mark(AgentTurnPhase.LLM_STREAM_END,
            "call" to callId, "textChars" to textChars, "reasoningChars" to reasoningChars,
            "toolCalls" to toolCalls, "stopReason" to stopReason, "inputTokens" to inputTokens,
            "outputTokens" to outputTokens, "retryCount" to retryCount,
            "durationMs" to start?.let { SystemClock.elapsedRealtime() - it })
    }

    /** 流在收到终态前失败/中断：与 [llmStreamEnd] 同属 [AgentTurnPhase.LLM_STREAM_END]，保证成对。 */
    fun llmStreamFailed(callId: Int, error: String, textChars: Int) {
        val start = llmStartElapsed.remove(callId)
        mark(AgentTurnPhase.LLM_STREAM_END, "call" to callId, "failed" to true,
            "textChars" to textChars, "error" to error.take(200),
            "durationMs" to start?.let { SystemClock.elapsedRealtime() - it })
    }

    /** 一批工具开始执行，返回批次序号。 */
    fun toolBatchStart(toolCalls: List<ToolCall>): Int {
        val id = batchSeq.incrementAndGet()
        batchStartElapsed[id] = SystemClock.elapsedRealtime()
        mark(AgentTurnPhase.TOOL_BATCH_START,
            "batch" to id, "count" to toolCalls.size, "tools" to toolNames(toolCalls))
        return id
    }

    fun toolBatchEnd(batchId: Int, count: Int, errorCount: Int) {
        val start = batchStartElapsed.remove(batchId)
        mark(AgentTurnPhase.TOOL_BATCH_END,
            "batch" to batchId, "count" to count, "errors" to errorCount,
            "durationMs" to start?.let { SystemClock.elapsedRealtime() - it })
    }

    /** 一轮正常结束。 */
    fun end(iterations: Int, toolCalls: Int) =
        mark(AgentTurnPhase.TURN_END, "iterations" to iterations, "toolCalls" to toolCalls)

    /** 一轮因错误结束。 */
    fun fail(error: String, errorCode: String?, iterations: Int) =
        mark(AgentTurnPhase.TURN_FAILED,
            "errorCode" to errorCode, "error" to error.take(200), "iterations" to iterations)

    private fun toolNames(toolCalls: List<ToolCall>): String =
        toolCalls.joinToString(",") { it.name }.take(200)

    /** 值里的换行/回车会破坏「一行一条、key=value 可 grep」的约定，统一压成空格。 */
    private fun sanitize(value: String): String =
        if (value.indexOf('\n') < 0 && value.indexOf('\r') < 0) value else value.replace(NEWLINES, " ")

    companion object {
        const val TAG = "AgentTurn"
        private val NEWLINES = Regex("[\\r\\n]+")

        /** 开始记录一轮并立即打出 [AgentTurnPhase.TURN_START]。 */
        fun begin(sessionId: String?, mode: String, tools: Int, historyMessages: Int): AgentTurnTelemetry {
            val telemetry = AgentTurnTelemetry(UUID.randomUUID().toString().substring(0, 8), sessionId)
            telemetry.mark(AgentTurnPhase.TURN_START,
                "mode" to mode, "tools" to tools, "history" to historyMessages)
            return telemetry
        }
    }
}
