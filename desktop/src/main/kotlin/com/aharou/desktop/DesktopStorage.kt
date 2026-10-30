package com.aharou.desktop

import com.aharou.core.storage.openAgentDatabase
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import java.io.File
import java.util.UUID

/**
 * 桌面端的会话存储。
 *
 * 库结构与手机端完全一致（同一个 [AgentDatabase]，schema 78），所以两边的数据格式天然对得上；
 * 数据库文件单独放在 `~/.aharou-desktop/`，不与手机端共享文件。
 */
object DesktopStorage {

    private val dir = File(System.getProperty("user.home"), ".aharou-desktop")

    private val database: AgentDatabase by lazy {
        dir.mkdirs()
        openAgentDatabase(File(dir, "aharou.db").absolutePath)
    }

    /** 取该工作区最近一个会话；没有就新建一个。 */
    suspend fun openOrCreateSession(workspacePath: String): ChatSessionEntity {
        val dao = database.chatSessionDao()
        dao.getLatestRootSessionByWorkspace(workspacePath)?.let { return it }
        val now = System.currentTimeMillis()
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            title = "桌面会话",
            createdAt = now,
            updatedAt = now,
            workspacePath = workspacePath
        )
        dao.upsert(session)
        return session
    }

    suspend fun messages(sessionId: String): List<AgentMessageEntity> =
        database.agentMessageDao().getMessagesBySessionOnce(sessionId)

    suspend fun appendUserMessage(sessionId: String, content: String): AgentMessageEntity {
        val entity = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "USER",
            content = content,
            timestamp = System.currentTimeMillis()
        )
        database.agentMessageDao().insert(entity)
        touch(sessionId)
        return entity
    }

    suspend fun appendAssistantMessage(
        sessionId: String,
        content: String,
        reasoning: String,
        signature: String,
        thinkingBlocksJson: String,
        inputTokens: Int
    ): AgentMessageEntity {
        val entity = AgentMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "ASSISTANT",
            content = content,
            timestamp = System.currentTimeMillis(),
            reasoning = reasoning.ifBlank { null },
            signature = signature.ifBlank { null },
            thinkingBlocksJson = thinkingBlocksJson.ifBlank { null },
            inputTokens = inputTokens
        )
        database.agentMessageDao().insert(entity)
        touch(sessionId)
        return entity
    }

    /** 会话列表按 updatedAt 排序，写消息时同步推一下。 */
    private suspend fun touch(sessionId: String) {
        database.chatSessionDao().touch(sessionId, System.currentTimeMillis())
    }
}
