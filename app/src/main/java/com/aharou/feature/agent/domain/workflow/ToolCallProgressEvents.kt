package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.provider.AIStreamChunk

/**
 * 参数进度 chunk → UI 事件。
 *
 * 进度已经在 adapter 侧按门限节流过（见 provider 包的 `ToolArgProgressThrottle`），这里原样转发：
 * 再加一层时间门限会把相邻两次真实变化吞成一条，UI 反而看不到「写到多少了」。
 */
internal fun toolCallProgressEvent(chunk: AIStreamChunk.ToolCallArgumentProgress): AgentEvent =
    AgentEvent.ToolCallPreparing(
        chunk.name,
        ToolArgsProgress(chunk.argsSoFarChars, chunk.argsSoFarLines)
    )
