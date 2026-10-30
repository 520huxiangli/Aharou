package com.aharou.feature.agent.data.local.dao

import com.aharou.feature.agent.data.local.entity.AgentMessageEntity

/**
 * 历史回放用的投影。
 *
 * 不含 `toolArgs`：那一列单行可达 150KB，而回放从不读它。原来 `SELECT *` 会把整张会话
 * 的所有列连同这些大字段一起拉上堆，几千条消息的会话每轮 LLM 调用前都要付一次。
 * 字段与 [AgentMessageDao.getHistoryRowsBySessionOnce] 的 SELECT 列表一一对应，Room 按名字映射。
 */
data class HistoryRow(
    val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallsJson: String?,
    val toolCallId: String?,
    val toolName: String?,
    val attachmentsJson: String?,
    val reasoning: String?,
    val signature: String?,
    val thinkingBlocksJson: String?,
    val inputTokens: Int,
    val isCompacted: Boolean,
    val isContextExcluded: Boolean,
    val isContextSummary: Boolean,
    val isCompactionMarker: Boolean,
)

/** 测试与内存态（幽灵消息）转投影，让两条来源走同一套回放逻辑。 */
fun AgentMessageEntity.toHistoryRow(): HistoryRow = HistoryRow(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    timestamp = timestamp,
    toolCallsJson = toolCallsJson,
    toolCallId = toolCallId,
    toolName = toolName,
    attachmentsJson = attachmentsJson,
    reasoning = reasoning,
    signature = signature,
    thinkingBlocksJson = thinkingBlocksJson,
    inputTokens = inputTokens,
    isCompacted = isCompacted,
    isContextExcluded = isContextExcluded,
    isContextSummary = isContextSummary,
    isCompactionMarker = isCompactionMarker,
)

/** 投影还原成实体：未投影的列（toolArgs / isError / 输出用量）在回放里不参与判断，取默认值。 */
fun HistoryRow.toEntity(): AgentMessageEntity = AgentMessageEntity(
    id = id,
    sessionId = sessionId,
    role = role,
    content = content,
    timestamp = timestamp,
    toolCallsJson = toolCallsJson,
    toolCallId = toolCallId,
    toolName = toolName,
    attachmentsJson = attachmentsJson,
    reasoning = reasoning,
    signature = signature,
    thinkingBlocksJson = thinkingBlocksJson,
    inputTokens = inputTokens,
    isCompacted = isCompacted,
    isContextExcluded = isContextExcluded,
    isContextSummary = isContextSummary,
    isCompactionMarker = isCompactionMarker,
)
