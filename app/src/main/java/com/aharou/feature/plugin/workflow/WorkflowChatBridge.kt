package com.aharou.feature.plugin.workflow

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.runner.AgentTurnCompletion
import com.aharou.feature.agent.domain.runner.AgentTurnRequest
import com.aharou.feature.agent.domain.runner.AgentTurnResult
import com.aharou.feature.agent.domain.runner.AgentTurnRunner
import com.aharou.feature.agent.domain.workflow.AgentTurnTrigger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Operit `extended_chat:*` 动作的落点：把「找对话 / 在对话里发消息并等回复」接到 Aharou 的会话体系。
 *
 * 移植自 Operit（`AAswordman/Operit`，LGPL-3.0）
 * `core/tools/defaultTool/standard/StandardChatManagerTool.kt` 的 findChat / chat_with_agent；
 * Aharou 侧改走 [ChatSessionDao] 与 [AgentTurnRunner]，不经过聊天页 ViewModel（工作流常在后台跑）。
 */
@Singleton
class WorkflowChatBridge @Inject constructor(
    private val sessionDao: ChatSessionDao,
    private val messageDao: AgentMessageDao,
    private val agentTurnRunner: AgentTurnRunner,
) {

    suspend fun invoke(action: String, args: Map<String, String>): WorkflowNodeOutcome = when (action) {
        "find_chat" -> findChat(args)
        "chat_with_agent" -> chatWithAgent(args)
        else -> WorkflowNodeOutcome.failure(
            "不支持的 extended_chat 动作: $action（Aharou 支持 find_chat / chat_with_agent）"
        )
    }

    /**
     * 按 id 或标题找会话，返回 `{"data":{"matchedCount":n,"chat":{...}}}`，
     * 与 Operit 的结果结构一致——模板里的下游节点就靠 `data.chat.id` 取值。
     */
    private suspend fun findChat(args: Map<String, String>): WorkflowNodeOutcome {
        val query = args["query"]?.trim().orEmpty()
        if (query.isEmpty()) return WorkflowNodeOutcome.failure("extended_chat:find_chat 缺少 query 参数")
        val matchMode = args["match"]?.trim()?.lowercase().orEmpty().ifEmpty { MATCH_CONTAINS }
        if (matchMode !in setOf(MATCH_CONTAINS, MATCH_EXACT, MATCH_REGEX)) {
            return WorkflowNodeOutcome.failure("extended_chat:find_chat 的 match 只能是 contains/exact/regex")
        }
        val index = args["index"]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0

        val sessions = sessionDao.getAllOnce().filter { it.parentId == null }
        val byId = sessions.filter { it.id == query }
        val matched = if (byId.isNotEmpty()) byId else sessions.filter { matchesTitle(it.title, query, matchMode) }
        if (matched.isEmpty()) {
            return WorkflowNodeOutcome.failure("没有标题匹配「$query」的会话；先用 chat.createSession 或手动建一个")
        }
        if (index !in matched.indices) {
            return WorkflowNodeOutcome.failure("会话下标越界: index=$index，匹配到 ${matched.size} 个")
        }
        val session = matched[index]
        val payload = buildJsonObject {
            putJsonObject("data") {
                put("matchedCount", matched.size)
                putJsonObject("chat") {
                    put("id", session.id)
                    put("title", session.title)
                    put("workspacePath", session.workspacePath)
                    put("updatedAt", session.updatedAt)
                }
            }
        }
        return WorkflowNodeOutcome.success(payload.toString())
    }

    /**
     * 在指定（或最近）会话里跑一轮 agent，等到本轮结束再返回助手回复文本。
     *
     * Operit 的 `character_card_name` 参数在 Aharou 没有对应概念（本仓没有角色卡），忽略并记一条日志；
     * `hide_user_message` / `persist_turn` 映射到 [AgentTurnRequest.persistUserMessage]。
     */
    private suspend fun chatWithAgent(args: Map<String, String>): WorkflowNodeOutcome {
        val message = args["message"].orEmpty()
        if (message.isBlank()) return WorkflowNodeOutcome.failure("extended_chat:chat_with_agent 缺少 message 参数")
        if (args["character_card_name"].orEmpty().isNotBlank()) {
            FileLogger.i(TAG, "工作流指定了 character_card_name，Aharou 没有角色卡，已忽略")
        }
        val requestedChatId = args["chat_id"]?.trim()?.takeIf { it.isNotEmpty() }
        val session = requestedChatId?.let { sessionDao.getById(it) }
        if (requestedChatId != null && session == null) {
            return WorkflowNodeOutcome.failure("找不到会话: $requestedChatId")
        }
        val workspace = session?.workspacePath?.takeIf { it.isNotBlank() }
            ?: agentTurnRunner.currentWorkspacePath()
        if (workspace.isBlank()) {
            return WorkflowNodeOutcome.failure("当前没有打开的工作区，无法发起对话轮次")
        }
        val sessionId = session?.id ?: agentTurnRunner.ensureSession(workspace)
        val timeoutMs = (args["timeout"]?.trim()?.toLongOrNull() ?: DEFAULT_TIMEOUT_MS)
            .coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
        val persistTurn = parseBooleanLike(args["persist_turn"]) ?: true
        val hideUserMessage = parseBooleanLike(args["hide_user_message"]) ?: false

        val baseline = messageDao.getMessagesPageDescending(sessionId, null, null, 1)
            .firstOrNull()?.timestamp ?: 0L
        val completion = AgentTurnCompletion()
        val outcome = try {
            withTimeout(timeoutMs) {
                coroutineScope {
                    val job = launch {
                        try {
                            agentTurnRunner.run(
                                AgentTurnRequest(
                                    sessionId = sessionId,
                                    text = message,
                                    projectRoot = workspace,
                                    isAutoTrigger = true,
                                    trigger = AgentTurnTrigger.SCHEDULED_TASK,
                                    skipTitleUpdate = true,
                                    persistUserMessage = persistTurn && !hideUserMessage,
                                )
                            ).collect { event -> completion.observe(event) }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            completion.fail(e)
                            FileLogger.e(TAG, "工作流对话轮次异常: $sessionId", e)
                        }
                    }
                    completion.bind(job)
                    completion.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            return WorkflowNodeOutcome.failure("等待 AI 回复超时（${timeoutMs}ms）")
        }
        if (outcome != AgentTurnResult.COMPLETED) {
            return WorkflowNodeOutcome.failure("AI 轮次未正常完成: ${outcome.name}")
        }
        val reply = messageDao
            .getMessagesPageDescending(sessionId, null, baseline, REPLY_SCAN_LIMIT)
            .firstOrNull { it.role.equals("assistant", ignoreCase = true) && it.content.isNotBlank() }
        if (reply == null) {
            return WorkflowNodeOutcome.failure("本轮没有产生助手回复（会话 $sessionId）")
        }
        return WorkflowNodeOutcome.success(reply.content)
    }

    private fun matchesTitle(title: String, query: String, mode: String): Boolean = when (mode) {
        MATCH_EXACT -> title == query
        MATCH_REGEX -> runCatching { Regex(query).containsMatchIn(title) }.getOrDefault(false)
        else -> title.contains(query, ignoreCase = true)
    }

    private fun parseBooleanLike(value: String?): Boolean? = when (value?.trim()?.lowercase()) {
        null, "" -> null
        "true", "1", "yes", "y", "on" -> true
        "false", "0", "no", "n", "off" -> false
        else -> null
    }

    private companion object {
        const val TAG = "WorkflowChatBridge"
        const val MATCH_CONTAINS = "contains"
        const val MATCH_EXACT = "exact"
        const val MATCH_REGEX = "regex"
        const val DEFAULT_TIMEOUT_MS = 300_000L
        const val MIN_TIMEOUT_MS = 5_000L
        const val MAX_TIMEOUT_MS = 600_000L
        const val REPLY_SCAN_LIMIT = 16
    }
}
