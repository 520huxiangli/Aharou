package com.aharou.feature.agent.domain.sidequestion

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.domain.provider.AIProvider
import com.aharou.feature.agent.domain.provider.OneShotProviderFactory
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.SIDE_QUESTION_PREFIX
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旁问：就当前对话的内容问一句，答案只给用户看，不进主线上下文，也不打断正在跑的一轮。
 *
 * 与子代理的区别是它不做任务：不给工具、不写用户消息、不改标题，一次 [AIProvider.complete]
 * 就收尾。答案落库时标 [AgentMessageDao.updateContextExcluded]，[MessagePersistenceUseCase.buildHistory]
 * 的回放过滤会自动把它挡在下一次请求之外，落库只是为了让它在重开 App 后还在。
 */
@Singleton
class SideQuestionRunner @Inject constructor(
    private val agentMessageDao: AgentMessageDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
    private val oneShotProviderFactory: OneShotProviderFactory,
) {

    /**
     * 提问并落库。失败不抛给调用方，而是落一条错误卡片：模型答不了和主线跑挂了不是一回事，
     * 不该让上层把它当成错误状态处理。
     */
    suspend fun ask(sessionId: String, question: String) {
        val trimmed = question.trim()
        if (trimmed.isEmpty()) {
            messagePersistenceUseCase.persist(
                sessionId = sessionId,
                role = MessageRole.TOOL,
                content = USAGE_HINT,
                toolName = TOOL_NAME,
            )
            return
        }
        runCatching { askInternal(sessionId, trimmed) }
            .onFailure { e ->
                FileLogger.w(TAG, "旁问失败", e)
                messagePersistenceUseCase.persist(
                    sessionId = sessionId,
                    role = MessageRole.TOOL,
                    content = "旁问失败：" + (e.message ?: "未知错误"),
                    toolName = TOOL_NAME,
                    isError = true,
                )
            }
    }

    private suspend fun askInternal(sessionId: String, question: String): String {
        val provider = oneShotProviderFactory.create(sessionId)
            ?: throw IllegalStateException("没有可用的 AI 供应商，先到设置里配一个再旁问")

        // 把已有对话压成一段纯文本喂给模型，而不是还原成结构化历史：旁问只做一次补全，
        // 不需要工具配对，也不该继承主线的 system prompt 与工具定义。
        val history = messagePersistenceUseCase.buildHistory(sessionId, SessionUseCase.PENDING_TOOL_MARKER)
        val transcript = history.takeLast(MAX_CONTEXT_MESSAGES).joinToString("\n\n") { message ->
            when (message) {
                is AgentMessage.UserMessage -> message.content.takeIf { it.isNotBlank() }?.let { "用户：$it" }.orEmpty()
                is AgentMessage.AssistantMessage -> message.content.takeIf { it.isNotBlank() }?.let { "助手：$it" }.orEmpty()
                else -> ""
            }
        }.trim()

        val prompt = buildString {
            if (transcript.isNotEmpty()) append("以下是此前的对话：\n\n").append(transcript).append("\n\n")
            append("旁问：").append(question)
        }
        val response = provider.complete(
            systemPrompt = SYSTEM_PROMPT,
            messages = listOf(AgentMessage.UserMessage(content = prompt)),
            tools = emptyList()
        )
        val answer = response.content.trim()
        if (answer.isBlank()) throw IllegalStateException("旁问没有返回内容，可以再问一次")

        val id = UUID.randomUUID().toString()
        messagePersistenceUseCase.persist(
            sessionId = sessionId,
            role = MessageRole.ASSISTANT,
            content = SIDE_QUESTION_PREFIX + answer,
            id = id,
            inputTokens = response.inputTokens,
            outputTokens = response.outputTokens,
        )
        agentMessageDao.updateContextExcluded(listOf(id), excluded = true)
        return answer
    }

    private companion object {
        const val TAG = "SideQuestionRunner"

        /** 无配对 toolCallId 的 TOOL 记录，回放时被抛掉，界面上渲染成一张卡片。 */
        const val TOOL_NAME = "旁问"

        /** 只给了命令没给问题时告诉用户怎么写，而不是静默什么都不做。 */
        const val USAGE_HINT = "用法：/btw 后面跟上想问的问题"

        /** 只带最近这些条：旁问是补一句，不是让模型重读整段会话。 */
        const val MAX_CONTEXT_MESSAGES = 40

        const val SYSTEM_PROMPT = "你在回答用户关于当前这段对话的旁问（提问会标注为「旁问」）。" +
            "依据给出的对话内容直接回答，不要调用工具、不要执行任务、不要展开额外的下一步计划。" +
            "答案会被单独展示，不进入后续对话上下文。对话里没有的信息就直说没有。"
    }
}
