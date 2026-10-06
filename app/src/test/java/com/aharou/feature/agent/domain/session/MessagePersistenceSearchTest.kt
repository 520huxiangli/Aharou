package com.aharou.feature.agent.domain.session

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.ChatSearchMatch
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.presentation.MessageRole
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessagePersistenceSearchTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dao = mockk<AgentMessageDao>()
    private val sessionDao = mockk<ChatSessionDao>()
    private val database = mockk<AgentDatabase>(relaxed = true)
    private lateinit var root: File
    private lateinit var store: MessageArchiveStore
    private lateinit var useCase: MessagePersistenceUseCase

    @Before
    fun setUp() {
        root = File(temporaryFolder.root, "archive")
        store = MessageArchiveStore(root)
        every { database.chatSessionDao() } returns sessionDao
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any()) } returns Unit
        useCase = MessagePersistenceUseCase(dao, database, store)
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    private fun session(id: String = "session", workspace: String = "workspace") = ChatSessionEntity(
        id = id, title = "title-$id", createdAt = 1L, updatedAt = 2L, workspacePath = workspace
    )

    private fun entity(
        id: String,
        content: String = "keyword",
        timestamp: Long = 1L,
        sessionId: String = "session",
        role: MessageRole = MessageRole.ASSISTANT
    ) = AgentMessageEntity(id, sessionId, role.name, content, timestamp)

    private fun pages(rows: List<AgentMessageEntity>, sessions: List<ChatSessionEntity> = listOf(session())) {
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("workspace") } returns sessions
        coEvery { dao.getPageBySessionAfter(any(), any(), any(), any()) } coAnswers {
            val sessionId = firstArg<String>()
            val timestamp = secondArg<Long>()
            val id = thirdArg<String>()
            assertEquals(16, arg<Int>(3))
            rows.filter {
                it.sessionId == sessionId && (it.timestamp > timestamp || it.timestamp == timestamp && it.id > id)
            }.sortedWith(compareBy<AgentMessageEntity> { it.timestamp }.thenBy { it.id }).take(16)
        }
    }

    @Test
    fun archivedTailIsMatchedCaseInsensitivelyAndNewestHitsHaveBoundedSnippets() = runTest {
        val original = entity("archived", "p".repeat(200_000) + "TAIL_KEYWORD" + "s".repeat(75), 30L)
        val archived = store.prepareForStorage(original)
        assertFalse(archived.content.contains("TAIL_KEYWORD"))
        val inline = entity("inline", "tail_keyword inline", 40L, role = MessageRole.USER)
        pages(listOf(entity("old", "tail_keyword old", 1L), archived, inline, entity("unmatched", "no hit", 100L)))

        val hits = useCase.searchInWorkspace("workspace", "tail_keyword", 2)

        assertEquals(listOf("inline", "archived"), hits.map { it.messageId })
        assertEquals(ChatSearchMatch("inline", "session", "title-session", "USER", inline.content, 40L), hits.first())
        assertEquals("…" + "p".repeat(40) + "TAIL_KEYWORD" + "s".repeat(40) + "…", hits.last().content)
        assertTrue(hits.all { it.content.length <= "tail_keyword".length + 82 })
        coVerify(exactly = 0) { dao.searchInWorkspace(any(), any(), any()) }
    }

    @Test
    fun equalTimestampsAreOrderedByDescendingIdRegardlessOfSessionOrder() = runTest {
        val first = session("first")
        val second = session("second")
        pages(listOf(
            entity("a", timestamp = 5L, sessionId = first.id),
            entity("y", timestamp = 5L, sessionId = first.id),
            entity("b", timestamp = 5L, sessionId = second.id),
            entity("z", timestamp = 5L, sessionId = second.id)
        ), listOf(first, second))
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("workspace") } returnsMany
            listOf(listOf(first, second), listOf(second, first))

        val hits = useCase.searchInWorkspace("workspace", "keyword", 3)

        assertEquals(listOf("z", "y", "b"), hits.map { it.messageId })
        assertEquals(hits, useCase.searchInWorkspace("workspace", "keyword", 3))
    }

    @Test
    fun missingAndCorruptArchivesKeepPreviewWithoutSyntheticUnavailableOrReferences() = runTest {
        val missing = store.prepareForStorage(entity("missing", "preview_keyword " + "x".repeat(200_000) + "TAIL_ONLY", 1L))
        val corrupt = store.prepareForStorage(entity("corrupt", "preview_keyword " + "y".repeat(200_000) + "TAIL_ONLY", 2L))
        store.deleteMessage(missing.sessionId, missing.id)
        root.walkTopDown().single { it.isFile && it.name.startsWith("content-") }.writeText("damaged")
        assertTrue(store.restore(missing).content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
        assertTrue(store.restore(corrupt).content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
        pages(listOf(missing, corrupt))

        assertTrue(useCase.searchInWorkspace("workspace", "unavailable", 2).isEmpty())
        assertTrue(useCase.searchInWorkspace("workspace", "aharou-message-archive", 2).isEmpty())
        assertTrue(useCase.searchInWorkspace("workspace", "TAIL_ONLY", 2).isEmpty())
        val hits = useCase.searchInWorkspace("workspace", "PREVIEW_KEYWORD", 2)
        assertEquals(listOf("corrupt", "missing"), hits.map { it.messageId })
        hits.forEach {
            assertTrue(it.content.contains("preview_keyword"))
            assertFalse(it.content.contains("unavailable"))
            assertFalse(it.content.contains("aharou-message-archive"))
        }
    }

    @Test
    fun realUnavailableTextRemainsSearchableInlineAndInArchivedTail() = runTest {
        val literal = MessageArchiveStore.UNAVAILABLE_MARKER
        val archived = store.prepareForStorage(entity("archived", "x".repeat(200_000) + literal, 2L))
        assertFalse(archived.content.contains("unavailable"))
        pages(listOf(entity("inline", "user wrote:$literal", 1L, role = MessageRole.USER), archived))

        val hits = useCase.searchInWorkspace("workspace", "message archive unavailable", 2)

        assertEquals(listOf("archived", "inline"), hits.map { it.messageId })
        assertTrue(hits.all { it.content.contains("[message archive unavailable]") })
    }

    @Test
    fun keysetCrossesEqualTimestampPagesAndSkipsInternalMessagesAndOtherWorkspaces() = runTest {
        val foreign = session("foreign", "other-workspace")
        val rows = (0 until 35).map { index ->
            val row = entity("m${index.toString().padStart(2, '0')}", timestamp = 7L,
                role = if (index % 2 == 0) MessageRole.USER else MessageRole.ASSISTANT)
            if (index >= 16) row else when (index % 5) {
                0 -> row.copy(isCompacted = true)
                1 -> row.copy(isContextSummary = true)
                2 -> row.copy(isCompactionMarker = true)
                3 -> row.copy(role = MessageRole.TOOL.name)
                else -> row.copy(role = "SYSTEM")
            }
        }
        pages(rows + entity("foreign-hit", timestamp = 100L, sessionId = foreign.id))
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("other-workspace") } returns listOf(foreign)

        val hits = useCase.searchInWorkspace("workspace", "keyword", 2)

        assertEquals(listOf("m34", "m33"), hits.map { it.messageId })
        assertEquals(rows.filter { it.id >= "m16" }.map { it.id }.reversed(),
            useCase.searchInWorkspace("workspace", "keyword", 100).map { it.messageId })
        coVerify(exactly = 2) { dao.getPageBySessionAfter("session", Long.MIN_VALUE, "", 16) }
        coVerify(exactly = 2) { dao.getPageBySessionAfter("session", 7L, "m15", 16) }
        coVerify(exactly = 2) { dao.getPageBySessionAfter("session", 7L, "m31", 16) }
        coVerify(exactly = 0) { dao.getPageBySessionAfter(foreign.id, any(), any(), any()) }
        coVerify(exactly = 0) { sessionDao.getAllSessionsByWorkspaceOnce("other-workspace") }
        coVerify(exactly = 2) { sessionDao.getAllSessionsByWorkspaceOnce("workspace") }
    }

    @Test
    fun snippetsHandleBothEdgesAndWildcardCharactersAreLiteral() = runTest {
        pages(listOf(
            entity("start", "!%_" + "s".repeat(100), 3L),
            entity("end", "p".repeat(100) + "!%_", 2L),
            entity("short", "literal !%_ token", 1L),
            entity("not-literal", "literal abc token", 4L)
        ))

        val hits = useCase.searchInWorkspace("workspace", "!%_", 4)

        assertEquals(listOf("start", "end", "short"), hits.map { it.messageId })
        assertEquals(listOf("!%_" + "s".repeat(40) + "…", "…" + "p".repeat(40) + "!%_", "literal !%_ token"),
            hits.map { it.content })
    }

    @Test
    fun longMultilineKeywordIsMatchedLiterallyInArchivedTailWithoutTruncation() = runTest {
        val keyword = "line one\nline two " + "x".repeat(90) + "\nline three"
        val archived = store.prepareForStorage(entity("archived", "p".repeat(150_000) + keyword + "s".repeat(50), 5L))
        assertFalse(archived.content.contains("line three"))
        pages(listOf(archived))

        val hits = useCase.searchInWorkspace("workspace", keyword, 1)

        assertEquals(listOf("archived"), hits.map { it.messageId })
        assertEquals("\u2026" + "p".repeat(40) + keyword + "s".repeat(40) + "\u2026", hits.single().content)
    }

    @Test
    fun daoCancellationPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("cancel search")
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("workspace") } returns listOf(session())
        coEvery { dao.getPageBySessionAfter("session", Long.MIN_VALUE, "", 16) } throws cancellation

        try {
            useCase.searchInWorkspace("workspace", "keyword", 1)
            throw AssertionError("Expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
        coVerify(exactly = 1) { dao.getPageBySessionAfter(any(), any(), any(), any()) }
    }

    @Test
    fun cancellationAfterReadingInternalPageStopsBeforeAnotherPage() = runTest {
        coEvery { sessionDao.getAllSessionsByWorkspaceOnce("workspace") } returns listOf(session())
        coEvery { dao.getPageBySessionAfter("session", Long.MIN_VALUE, "", 16) } coAnswers {
            currentCoroutineContext().cancel(CancellationException("cancel after page"))
            (0 until 16).map { entity("internal-$it").copy(isCompacted = true) }
        }
        var propagated = false

        val job = launch {
            try {
                useCase.searchInWorkspace("workspace", "keyword", 1)
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                propagated = true
            }
        }
        job.join()

        assertTrue(propagated)
        assertTrue(job.isCancelled)
        coVerify(exactly = 1) { dao.getPageBySessionAfter(any(), any(), any(), any()) }
    }

    @Test
    fun blankQueryAndNonpositiveLimitNeverReadDaos() = runTest {
        assertTrue(useCase.searchInWorkspace("workspace", "", 1).isEmpty())
        assertTrue(useCase.searchInWorkspace("workspace", " \n\t", 1).isEmpty())
        assertTrue(useCase.searchInWorkspace("workspace", "keyword", 0).isEmpty())
        assertTrue(useCase.searchInWorkspace("workspace", "keyword", -1).isEmpty())

        verify { dao wasNot Called }
        verify { sessionDao wasNot Called }
        verify(exactly = 0) { database.chatSessionDao() }
    }
}
