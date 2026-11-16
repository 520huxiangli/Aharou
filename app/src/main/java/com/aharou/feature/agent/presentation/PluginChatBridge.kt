package com.aharou.feature.agent.presentation

import com.aharou.feature.agent.domain.session.AgentChatRequest

/**
 * 插件发起的会话操作：复用聊天页同一条发送与停止链路。
 *
 * 插件只能入队，真正开轮次仍由 [AIAgentViewModel] 执行——工具授权、运行状态、通知都在那边，
 * 另起一套会绕过这些边界。
 */
internal suspend fun AIAgentViewModel.handlePluginChatRequest(request: AgentChatRequest) {
    when (request) {
        is AgentChatRequest.Select -> selectSession(request.sessionId)

        is AgentChatRequest.SetDraft -> {
            request.sessionId?.takeIf { it.isNotBlank() }?.let { selectSession(it) }
            val existing = inputDraftOf(request.sessionId)
            updateInputDraft(
                if (request.append && existing.isNotBlank()) {
                    existing.trimEnd() + "\n\n" + request.text
                } else {
                    request.text
                }
            )
        }

        is AgentChatRequest.Delete -> deleteSession(request.sessionId)

        is AgentChatRequest.Stop -> stopAgentSession(request.sessionId)

        is AgentChatRequest.Send -> {
            if (!sessionExists(request.sessionId)) return
            selectSession(request.sessionId)
            enqueueAgentRequest(
                request = request.text,
                projectRoot = request.workspacePath,
                targetSessionId = request.sessionId
            )
        }
    }
}
