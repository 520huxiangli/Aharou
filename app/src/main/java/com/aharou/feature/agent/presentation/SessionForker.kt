package com.aharou.feature.agent.presentation

import android.content.Context
import com.aharou.R
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.ToolCall
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import dagger.hilt.android.scopes.ViewModelScoped
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * 「从此处新建分支」：以某条消息（含）之前的历史另起一个会话。
 *
 * 单独成类是为了不再往已到行数上限的 [AIAgentViewModel] 里堆逻辑；调用方拿返回的会话 id 自己去切。
 */
@ViewModelScoped
class SessionForker @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val sessionUseCase: SessionUseCase,
    private val agentMessageDao: AgentMessageDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) {

    private companion object {
        const val TAG = "SessionForker"
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 复制 [sourceSessionId] 的历史到新会话，返回新会话 id；源会话或被定位的消息不存在时返回 null。
     *
     * 尾部不完整轮次必须先截齐（见 [trimIncompleteTail]），否则回放会把悬挂的 tool_use 发给 Anthropic，
     * 请求直接 400。checkpoint / todo / LLM 调用记录属于原会话的执行痕迹，不跟着复制。
     */
    suspend fun fork(sourceSessionId: String, messageId: String, fallbackWorkspace: String): String? {
        try {
            val source = sessionUseCase.getSessionById(sourceSessionId) ?: return null
            val all = agentMessageDao.getMessagesBySessionOnce(sourceSessionId)
            val cut = all.indexOfFirst { it.id == messageId }
            if (cut < 0) return null
            val head = trimIncompleteTail(all.subList(0, cut + 1))
            if (head.isEmpty()) return null

            val fork = sessionUseCase.newSessionEntity(
                workspacePath = source.workspacePath.ifBlank { fallbackWorkspace },
                providerId = source.providerId,
                model = source.model,
                reasoningEffort = source.reasoningEffort,
                mode = source.mode
            )
            // 超长正文已归档到磁盘（路径按 sessionId + messageId 算），换 id 前必须先整体读回，
            // 否则副本里只剩一段 archive 标记，展示与回放都是空的。
            val copied = messagePersistenceUseCase.restoreAll(head)
            val idMap = copied.associate { it.id to UUID.randomUUID().toString() }
            val entities = copied.map { entity ->
                entity.copy(
                    id = idMap.getValue(entity.id),
                    sessionId = fork.id,
                    // 这两个字段存的是别的消息 id，跟着重映射，否则副本里的压缩归属与变体分组全断。
                    compactedBySummaryId = entity.compactedBySummaryId?.let { idMap[it] },
                    variantGroupId = entity.variantGroupId?.let { idMap[it] ?: it }
                )
            }
            // token 统计按复制后的消息重算：累计值取各条之和，上下文大小沿用最后一条回答的输入量。
            sessionUseCase.upsertSession(
                fork.copy(
                    title = context.getString(R.string.chat_fork_title, source.title),
                    modeBeforePlan = source.modeBeforePlan,
                    totalInputTokens = entities.sumOf { it.inputTokens },
                    totalOutputTokens = entities.sumOf { it.outputTokens },
                    lastInputTokens = entities.lastOrNull { it.role == MessageRole.ASSISTANT.name }
                        ?.inputTokens ?: 0
                )
            )
            agentMessageDao.insertAll(entities.map { messagePersistenceUseCase.prepareForStorage(it) })
            messagePersistenceUseCase.invalidateHistory(fork.id)
            return fork.id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "分叉会话失败", e)
            return null
        }
    }

    /**
     * 截掉历史尾部的不完整轮次：结果未回的 TOOL 行、tool_use 没拿到全部结果的 ASSISTANT 行。
     *
     * 只要有一条 tool_use 缺配对结果，Anthropic 回放就会 400，OpenAI 兼容侧则静默错配。
     * 从尾往前退，退到一条普通回答、或一轮已全部配对完的工具轮为止。
     */
    private fun trimIncompleteTail(messages: List<AgentMessageEntity>): List<AgentMessageEntity> {
        var end = messages.size
        while (end > 0) {
            val last = messages[end - 1]
            val answered = messages.subList(0, end).mapNotNullTo(HashSet<String>()) { it.toolCallId }
            if (last.role == MessageRole.TOOL.name) {
                // 结果的发起行必须还在 head 内，且它这一轮的所有 tool_use 都要有结果，才算配对完整。
                val resultId = last.toolCallId
                val owner = resultId?.let { rid -> (end - 2 downTo 0).firstOrNull { rid in messages[it].toolCallIds() } }
                if (owner != null && messages[owner].toolCallIds().all { it in answered }) break
                // 悬挂结果（或同轮仍有 tool_use 没结果）：连同发起行一起回退；发起行不在 head 内就只退这一行。
                end = owner ?: (end - 1)
                continue
            }
            val issued = last.toolCallIds()
            if (issued.isEmpty() || issued.all { it in answered }) break
            end--
        }
        return if (end <= 0) emptyList() else messages.take(end)
    }

    /** ASSISTANT 行上的 tool_calls id；非工具行、或快照被截断解析不了时返回空表。 */
    private fun AgentMessageEntity.toolCallIds(): Set<String> =
        toolCallsJson?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { json.decodeFromString<List<ToolCall>>(raw) }.getOrNull() }
            ?.mapTo(HashSet<String>()) { it.id }
            .orEmpty()
}
