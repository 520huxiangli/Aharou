package com.aharou.feature.agent.data.local.entity

import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.BACKGROUND_NOTIFICATION_PREFIX
import com.aharou.feature.agent.presentation.COMPACTION_FAILURE_TOOL_NAME
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.SAMPLING_LOOP_TOOL_NAME
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 实体到界面消息的映射。
 *
 * 放在 :app 而不是实体文件里：实体要随 :core-storage 共享给桌面端，不能依赖 presentation 层的类型。
 */
fun AgentMessageEntity.toUIMessage(): AgentUIMessage {
    val roleEnum = MessageRole.valueOf(role)
    return AgentUIMessage(
        id = id,
        role = roleEnum,
        content = content,
        timestamp = timestamp,
        toolName = toolName,
        toolArgs = toolArgs,
        isError = isError,
        reasoning = reasoning,
        attachments = decodeAttachments(attachmentsJson),
        isCompactionMarker = isCompactionMarker,
        isContextSummary = isContextSummary,
        isCompactionFailure = roleEnum == MessageRole.TOOL && toolName == COMPACTION_FAILURE_TOOL_NAME,
        isSamplingLoopStop = roleEnum == MessageRole.TOOL && toolName == SAMPLING_LOOP_TOOL_NAME,
        isBackgroundNotification = roleEnum == MessageRole.USER &&
            content.startsWith(BACKGROUND_NOTIFICATION_PREFIX),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cachedInputTokens,
        error = error,
        variantGroupId = variantGroupId,
        variantIndex = variantIndex
    )
}

private val attachmentJson = Json { ignoreUnknownKeys = true }

private fun decodeAttachments(value: String?): List<AgentAttachment> {
    if (value.isNullOrBlank()) return emptyList()
    return runCatching { attachmentJson.decodeFromString<List<AgentAttachment>>(value) }.getOrDefault(emptyList())
}
