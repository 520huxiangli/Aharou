package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage

/**
 * 只保留最近 [rounds] 轮（一问一答算一轮）作为发送投影。
 *
 * 从最早的轮次往前丢，且始终切在「用户消息」边界上——工具调用与它的结果永远落在同一段里，
 * 不会切出「有 tool_call 却没有结果」的断头消息。历史本身不动，数据库里仍然完整，
 * 只影响这一轮发给模型的那一份。
 */
internal fun List<AgentMessage>.trimToRecentRounds(rounds: Int): List<AgentMessage> {
    if (rounds <= 0) return this
    val userIndexes = indices.filter { this[it] is AgentMessage.UserMessage }
    if (userIndexes.size <= rounds) return this
    return subList(userIndexes[userIndexes.size - rounds], size)
}
