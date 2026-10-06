package com.aharou.feature.agent.domain.session

import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.model.AgentMessage
import com.aharou.feature.agent.presentation.MessageRole
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessagePersistenceArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = mockk<AgentMessageDao>(relaxed = true)
    private val database = mockk<AgentDatabase>(relaxed = true)

    private fun persistence(store: MessageArchiveStore) = MessagePersistenceUseCase(dao, database, store)

    private fun entity(id: String, role: MessageRole, timestamp: Long) = AgentMessageEntity(
        id = id,
        sessionId = "session",
        role = role.name,
        content = "long".repeat(60_000),
        timestamp = timestamp
    )

    @Test
    fun persistedContentAndReasoningRemainCompleteAfterHistoryReload() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "archive"))
        val saved = slot<AgentMessageEntity>()
        coEvery { dao.insert(capture(saved)) } returns Unit
        val useCase = persistence(store)
        val content = "content".repeat(50_000)
        val reasoning = "reasoning".repeat(50_000)
        useCase.persist("session", MessageRole.ASSISTANT, content, id = "message", reasoning = reasoning)
        assertTrue(saved.captured.content.toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertTrue(requireNotNull(saved.captured.reasoning).toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(saved.captured)
        val reloaded = persistence(MessageArchiveStore(File(temporaryFolder.root, "archive")))
        assertEquals(content, reloaded.restore(saved.captured).content)
        assertEquals(reasoning, reloaded.restore(saved.captured).reasoning)
        val history = reloaded.buildHistory("session", "[running]").single() as AgentMessage.AssistantMessage
        assertEquals(content, history.content)
        assertEquals(reasoning, history.reasoning)
    }

    @Test
    fun updateContentPreservesOldSnapshotAndStoresBoundedNewSnapshot() = runTest {
        val store = MessageArchiveStore(File(temporaryFolder.root, "archive"))
        val original = entity("message", MessageRole.ASSISTANT, 1)
        val old = store.prepareForStorage(original)
        coEvery { dao.getMessageById("message") } returns old
        val savedContent = slot<String>()
        coEvery { dao.updateMessageContent("message", capture(savedContent)) } returns Unit
        val replacement = "new".repeat(100_000)
        persistence(store).updateContent("message", replacement)
        assertTrue(savedContent.captured.toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertEquals(replacement, store.restore(old.copy(content = savedContent.captured)).content)
        assertEquals(original, store.restore(old))
    }

    @Test
    fun deletingUserAlsoDeletesLaterRowsAndTheirArchives() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val user = entity("user", MessageRole.USER, 1)
        val response = entity("assistant", MessageRole.ASSISTANT, 2)
        coEvery { dao.getMessageById("user") } returns user
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(user, response)
        persistence(store).deleteMessage("user")
        coVerify(exactly = 1) { dao.deleteMessagesAfterTimestamp("session", 1) }
        coVerify(exactly = 1) { dao.deleteMessageById("user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "assistant") }
    }

    @Test
    fun rewindDeletesOlderSummaryAndMarkerArchivesButPreservesEarlierMessages() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val kept = entity("kept", MessageRole.USER, 1)
        val summary = entity("summary", MessageRole.ASSISTANT, 2).copy(isContextSummary = true)
        val marker = entity("marker", MessageRole.USER, 3).copy(isCompactionMarker = true)
        val removed = entity("removed", MessageRole.ASSISTANT, 4)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(kept, summary, marker, removed)
        persistence(store).rewindConversation("session", 4)
        coVerify(exactly = 1) { dao.rewindConversation("session", 4) }
        coVerify(exactly = 0) { store.deleteMessage("session", "kept") }
        coVerify(exactly = 1) { store.deleteMessage("session", "summary") }
        coVerify(exactly = 1) { store.deleteMessage("session", "marker") }
        coVerify(exactly = 1) { store.deleteMessage("session", "removed") }
    }

    @Test
    fun deletingFromTimestampOnlyRemovesArchivesForDeletedRows() = runTest {
        val store = mockk<MessageArchiveStore>(relaxed = true)
        val user = entity("user", MessageRole.USER, 1)
        val response = entity("assistant", MessageRole.ASSISTANT, 2)
        coEvery { dao.getMessagesBySessionOnce("session") } returns listOf(user, response)
        persistence(store).deleteMessagesFromTimestamp("session", 2)
        coVerify(exactly = 1) { dao.deleteMessagesFromTimestamp("session", 2) }
        coVerify(exactly = 0) { store.deleteMessage("session", "user") }
        coVerify(exactly = 1) { store.deleteMessage("session", "assistant") }
    }
}
