package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.tool.SIDE_EFFECT_TOOL_NAMES
import com.aharou.feature.agent.domain.tool.TOOL_CALL_REPLAY_NOTICE
import com.aharou.feature.agent.domain.tool.ToolCall
import com.aharou.feature.agent.domain.tool.ToolCallIdempotencyStore

/**
 * 执行一次工具调用，对有副作用的工具按 `sessionId + toolCallId` 幂等。
 *
 * - 同 id 同参：不重跑，直接回放上次结果，并在结果前加一行说明——重试、断流续跑、用户重发都会
 *   走到这条路径，对写盘、`git commit`、部署这类命令就是实打实的重复副作用；
 * - 同 id 不同参：交给 [conflict] 构造拒绝结果，不替模型猜哪次才是本意；
 * - 没有记录：真跑一次并把结果记下来。
 *
 * 签名按需计算（[computeSignature]），读类工具不会为此白序列化一遍参数。
 * 只覆盖 [SIDE_EFFECT_TOOL_NAMES] 里的工具：读类工具重跑一次只是多花一次读取，
 * 缓存它们反而会让模型看到过期数据。
 */
internal suspend fun runIdempotentToolCall(
    store: ToolCallIdempotencyStore,
    sessionId: String?,
    toolCall: ToolCall,
    computeSignature: () -> String,
    conflict: () -> ToolRunResult,
    execute: suspend () -> ToolRunResult
): ToolRunResult {
    if (toolCall.name !in SIDE_EFFECT_TOOL_NAMES) return execute()
    // 没有会话 id 就定位不到「哪一笔调用」：多个无会话任务会共用同一个键空间，
    // 撞上同 id 的调用等于把别人的结果当自己的回放。这类场景重跑概率本来就低，宁可不做保护。
    if (sessionId.isNullOrBlank()) return execute()
    val signature = computeSignature()
    when (val decision = store.check(sessionId, toolCall.id, signature)) {
        is ToolCallIdempotencyStore.Decision.Replay -> {
            val cached = decision.result
            return ToolRunResult(
                TOOL_CALL_REPLAY_NOTICE + "\n\n" + cached.raw,
                cached.isError,
                cached.attachments,
                cached.images
            )
        }
        ToolCallIdempotencyStore.Decision.Conflict -> return conflict()
        ToolCallIdempotencyStore.Decision.Execute -> Unit
    }
    val result = execute()
    store.record(
        sessionId,
        toolCall.id,
        signature,
        ToolCallIdempotencyStore.CachedResult(result.raw, result.isError, result.attachments, result.images)
    )
    return result
}
