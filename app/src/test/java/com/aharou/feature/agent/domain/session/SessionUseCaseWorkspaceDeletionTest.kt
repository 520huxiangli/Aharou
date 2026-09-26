package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** 删除工作区时按 workspacePath 级联清理会话与消息的行为。 */
class SessionUseCaseWorkspaceDeletionTest {

    private fun session(id: String, workspacePath: String, parentId: String? = null) = ChatSessionEntity(
        id = id,
        title = "t",
        createdAt = 0L,
        updatedAt = 0L,
        workspacePath = workspacePath,
        parentId = parentId
    )

    @Test
    fun deleteSessionsByWorkspace_deletesMessagesThenSessions() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/a") } returns listOf(
            session("root", "/ws/a"),
            session("sub", "/ws/a", parentId = "root")
        )

        val useCase = SessionUseCase(chatDao, messageDao)
        val deleted = useCase.deleteSessionsByWorkspace("/ws/a")

        assertEquals(2, deleted)
        coVerify(exactly = 1) { messageDao.deleteBySession("root") }
        coVerify(exactly = 1) { messageDao.deleteBySession("sub") }
        coVerify(exactly = 1) { chatDao.deleteByWorkspace("/ws/a") }
    }

    @Test
    fun deleteSessionsByWorkspace_noSessions_skipsDeletion() = runTest {
        val chatDao = mockk<ChatSessionDao>(relaxed = true)
        val messageDao = mockk<AgentMessageDao>(relaxed = true)
        coEvery { chatDao.getAllSessionsByWorkspaceOnce("/ws/empty") } returns emptyList()

        val useCase = SessionUseCase(chatDao, messageDao)
        val deleted = useCase.deleteSessionsByWorkspace("/ws/empty")

        assertEquals(0, deleted)
        coVerify(exactly = 0) { messageDao.deleteBySession(any()) }
        coVerify(exactly = 0) { chatDao.deleteByWorkspace(any()) }
    }
}