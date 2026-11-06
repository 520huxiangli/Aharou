package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentImage
import com.aharou.feature.agent.presentation.AgentAttachment

/**
 * 单次工具执行的原始结果。
 *
 * 从 [StatefulAgentWorkflow] 里搬出来，一是让幂等重放（[runIdempotentToolCall]）也能读写它，
 * 二是批执行那一段已经顶到单文件行数棘轮的上限，这类纯值对象不该再占工作流的行数。
 */
internal data class ToolRunResult(
    val raw: String,
    val isError: Boolean,
    /** 仅 sendFile 等展示型工具：随结果附带的文件卡片元数据，供 UI 渲染，不回放进模型上下文。 */
    val attachments: List<AgentAttachment> = emptyList(),
    val images: List<AgentImage> = emptyList()
)
