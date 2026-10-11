package com.aharou.feature.agent.domain.tool.session

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.domain.model.AgentContext
import com.aharou.feature.agent.domain.session.MessagePersistenceUseCase
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.tool.AbstractContextualTool
import com.aharou.feature.agent.domain.tool.ParameterType
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.agent.domain.tool.ToolParameter
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.agent.presentation.MessageRole
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * 会话浏览工具（`sessions`）：让 AI 看到**当前工作区**下的其他会话——列出、搜索、读取。
 *
 * 访问范围严格锁在当前会话所属的工作区：所有查询都按该工作区的 `workspacePath` 过滤，读取单个会话时
 * 再校验其归属，跨工作区一律拒绝。工作区之间是有意隔离的（不同于 `task` 那条按父子关系走的口子）。
 */
class SessionTool @Inject constructor(
    private val sessionUseCase: SessionUseCase,
    private val chatSessionDao: ChatSessionDao,
    private val messagePersistenceUseCase: MessagePersistenceUseCase,
) : AbstractContextualTool() {

    private companion object {
        const val TAG = "SessionTool"

        const val DEFAULT_LIST_LIMIT = 30
        const val DEFAULT_READ_LIMIT = 40
        const val MAX_LIMIT = 200

        /** 单条消息回填给模型的最大字符数；太长只截断，避免一次读爆上下文。 */
        const val READ_CONTENT_MAX = 2_000
    }

    override val name = "sessions"

    override val description =
        "查看当前工作区下的其他会话。action=list 列出本工作区会话；search 在本工作区聊天记录里搜关键词；" +
            "read 读某个会话的消息（传 session_id，取自 list）。" +
            "只能访问当前工作区的会话——其它工作区是有意隔离的，一律看不到。"

    // 只读：命中策略引擎的 READ_AGENT_CONFIG 特判，非 AUTO 模式自动放行，PLAN 下也不算危险工具。
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "action" to ToolParameter(
            "action", ParameterType.STRING,
            "操作：list 列出当前工作区会话 / search 在当前工作区聊天记录里搜关键词 / read 读某个会话的消息",
            true,
            enum = listOf("list", "search", "read"),
        ),
        "session_id" to ToolParameter(
            "session_id", ParameterType.STRING,
            "read 专用：目标会话 id（取自 list 结果）", false,
        ),
        "query" to ToolParameter(
            "query", ParameterType.STRING,
            "search 专用：关键词", false,
        ),
        "limit" to ToolParameter(
            "limit", ParameterType.INTEGER,
            "可选：返回条数上限（list/search 默认 $DEFAULT_LIST_LIMIT，read 默认 $DEFAULT_READ_LIMIT）", false,
        ),
    )

    override suspend fun executeWithContext(args: Map<String, JsonElement>, context: AgentContext): ToolResult {
        val workspace = resolveWorkspace(context)
            ?: return ToolResult.Error("拿不到当前会话的工作区，无法浏览会话", "NO_WORKSPACE")
        val action = (args["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() ?: "list"
        val limit = (args["limit"] as? JsonPrimitive)?.intOrNull
        return try {
            when (action) {
                "list" -> listSessions(workspace, limit)
                "search" -> searchMessages(workspace, args, limit)
                "read" -> readSession(workspace, args, limit)
                else -> ToolResult.Error("未知 action：$action（支持 list / search / read）", "INVALID_ARGS")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLogger.e(TAG, "sessions 执行失败：$action", e)
            ToolResult.Error("会话查询失败：${e.message}", "SESSION_QUERY_FAILED")
        }
    }

    /** 解析当前工作区：优先取当前会话的 workspacePath，退回上下文里的 projectRoot。 */
    private suspend fun resolveWorkspace(context: AgentContext): String? {
        val fromSession = context.sessionId?.let { sessionUseCase.getSessionById(it)?.workspacePath }
        return fromSession?.takeIf { it.isNotBlank() }
            ?: context.projectRoot.takeIf { it.isNotBlank() }
    }

    /** 列出当前工作区的根会话。 */
    private suspend fun listSessions(workspace: String, limit: Int?): ToolResult {
        val sessions = chatSessionDao.getRootSessionsByWorkspaceOnce(workspace)
            .take((limit ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIMIT))
        return ToolResult.Success(
            buildJsonObject {
                put("workspace", workspace)
                put("count", sessions.size)
                put("sessions", buildJsonArray {
                    sessions.forEach { s ->
                        addJsonObject {
                            put("id", s.id)
                            put("title", s.title)
                            put("updatedAt", s.updatedAt)
                            put("providerId", s.providerId ?: "")
                            put("model", s.model ?: "")
                        }
                    }
                })
            }
        )
    }

    /** 在当前工作区的聊天记录里按关键词搜索（跨会话，命中用户 / 助手正文）。 */
    private suspend fun searchMessages(workspace: String, args: Map<String, JsonElement>, limit: Int?): ToolResult {
        val query = (args["query"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (query.isNullOrBlank()) return ToolResult.Error("search 需要 query", "MISSING_ARGS")
        val hits = messagePersistenceUseCase.searchInWorkspace(
            workspace, query, (limit ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIMIT)
        )
        return ToolResult.Success(
            buildJsonObject {
                put("workspace", workspace)
                put("query", query)
                put("count", hits.size)
                put("hits", buildJsonArray {
                    hits.forEach { h ->
                        addJsonObject {
                            put("sessionId", h.sessionId)
                            put("sessionTitle", h.sessionTitle)
                            put("role", h.role)
                            put("snippet", h.content)
                            put("timestamp", h.timestamp)
                        }
                    }
                })
            }
        )
    }

    /** 读某个会话的消息（限当前工作区）。 */
    private suspend fun readSession(workspace: String, args: Map<String, JsonElement>, limit: Int?): ToolResult {
        val sessionId = (args["session_id"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (sessionId.isNullOrBlank()) return ToolResult.Error("read 需要 session_id", "MISSING_ARGS")
        val session = sessionUseCase.getSessionById(sessionId)
            ?: return ToolResult.Error("会话不存在：$sessionId", "SESSION_NOT_FOUND")
        // 跨工作区一律拒绝：工作区之间是有意隔离的。
        if (session.workspacePath != workspace) {
            return ToolResult.Error("只能读取当前工作区的会话（跨工作区不可见）", "CROSS_WORKSPACE_DENIED")
        }
        val messages = messagePersistenceUseCase.getMessagesOnce(sessionId)
            .filter {
                (it.role == MessageRole.USER.name || it.role == MessageRole.ASSISTANT.name) &&
                    !it.isCompacted && !it.isContextSummary && !it.isCompactionMarker
            }
        val take = (limit ?: DEFAULT_READ_LIMIT).coerceIn(1, MAX_LIMIT)
        return ToolResult.Success(
            buildJsonObject {
                put("id", session.id)
                put("title", session.title)
                put("workspace", session.workspacePath)
                put("totalMessages", messages.size)
                put("messages", buildJsonArray {
                    messages.takeLast(take).forEach { m ->
                        addJsonObject {
                            put("role", m.role)
                            put("timestamp", m.timestamp)
                            put("content", m.content.take(READ_CONTENT_MAX))
                        }
                    }
                })
            }
        )
    }
}
