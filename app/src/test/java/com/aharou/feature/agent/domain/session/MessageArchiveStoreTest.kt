package com.aharou.feature.agent.domain.session

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class MessageArchiveStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var root: File
    private lateinit var store: MessageArchiveStore

    @Before
    fun setUp() {
        root = File(temporaryFolder.root, "message-archive")
        store = MessageArchiveStore(root)
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
    }

    private fun entity(content: String = "small", id: String = "message") = AgentMessageEntity(
        id = id,
        sessionId = "session",
        role = "ASSISTANT",
        content = content,
        timestamp = 42L,
        isContextSummary = true,
        isContextExcluded = true,
        compactedBySummaryId = "summary"
    )

    @Test
    fun oversizedFieldsRemainBoundedInDatabaseAndRestoreExactly() = runTest {
        val original = entity("汉😀".repeat(60_000)).copy(
            reasoning = "reason".repeat(60_000),
            toolCallsJson = "[{\"arguments\":\"${"x".repeat(200_000)}\"}]",
            toolArgs = "args".repeat(60_000),
            signature = "signed".repeat(60_000),
            thinkingBlocksJson = "[{\"thinking\":\"${"y".repeat(200_000)}\"}]",
            attachmentsJson = "[{\"name\":\"${"z".repeat(30_000)}\"}]"
        )
        val stored = store.prepareForStorage(original)
        assertTrue(stored.content.toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertTrue(requireNotNull(stored.reasoning).toByteArray().size <= MessagePersistenceUseCase.MAX_CONTENT_BYTES)
        assertTrue(requireNotNull(stored.toolCallsJson).toByteArray().size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertTrue(requireNotNull(stored.signature).toByteArray().size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertTrue(requireNotNull(stored.thinkingBlocksJson).toByteArray().size <= MessagePersistenceUseCase.MAX_SNAPSHOT_BYTES)
        assertTrue(requireNotNull(stored.toolArgs).toByteArray().size <= MessagePersistenceUseCase.MAX_TOOL_ARGS_BYTES)
        assertTrue(requireNotNull(stored.attachmentsJson).toByteArray().size <= MessagePersistenceUseCase.MAX_ATTACHMENTS_BYTES)
        assertEquals(original, MessageArchiveStore(root).restore(stored))
        assertEquals(stored, store.prepareForStorage(store.restore(stored)))
        assertEquals(7, root.walkTopDown().count { it.isFile })
    }

    @Test
    fun smallMessagesDoNotCreateArchives() = runTest {
        val original = entity().copy(reasoning = "thinking", thinkingBlocksJson = "[]")
        assertEquals(original, store.prepareForStorage(original))
        assertEquals(original, store.restore(original))
        assertFalse(root.exists())
    }

    @Test
    fun inlineImagesAreBoundedButOriginalContentIsRecoverable() = runTest {
        val original = entity("![image](data:image/png;base64,${"a".repeat(250_000)})")
        val stored = store.prepareForStorage(original)
        assertFalse(stored.content.contains("a".repeat(1000)))
        assertEquals(original, store.restore(stored))
    }

    @Test
    fun ordinaryTextResemblingReferenceRoundTripsWithoutBeingInterpreted() = runTest {
        val original = entity("ordinary text\n[aharou-message-archive:v1:content:${"0".repeat(64)}]")
        val stored = store.prepareForStorage(original)
        assertEquals(original, store.restore(stored))
        assertFalse(stored.content == original.content)
        val second = store.prepareForStorage(stored.copy(content = stored.content))
        assertEquals(stored, store.restore(second))
    }

    @Test
    fun replacingSameMessageKeepsBothSnapshotsValid() = runTest {
        val first = entity("first".repeat(60_000))
        val second = first.copy(content = "second".repeat(60_000))
        val storedFirst = store.prepareForStorage(first)
        val storedSecond = store.prepareForStorage(second)
        assertEquals(first, store.restore(storedFirst))
        assertEquals(second, store.restore(storedSecond))
        assertEquals(entity(), store.prepareForStorage(entity()))
        assertEquals(first, store.restore(storedFirst))
    }

    @Test
    fun missingAndCorruptArchivesDoNotLeakReferences() = runTest {
        val stored = store.prepareForStorage(entity("long".repeat(60_000)).copy(signature = "signature".repeat(30_000)))
        root.walkTopDown().filter { it.isFile && it.name.startsWith("content-") }.forEach { it.writeText("damaged") }
        root.walkTopDown().filter { it.isFile && it.name.startsWith("signature-") }.forEach { it.delete() }
        val restored = store.restore(stored)
        assertTrue(restored.content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
        assertFalse(restored.content.contains("aharou-message-archive"))
        assertEquals(null, restored.signature)
    }

    @Test
    fun idsCannotEscapePrivateRootAndReferencesCannotBeReusedAcrossMessages() = runTest {
        val original = entity("long".repeat(60_000), "../../outside").copy(sessionId = "../../../outside")
        val stored = store.prepareForStorage(original)
        assertEquals(original, store.restore(stored))
        assertFalse(File(temporaryFolder.root, "outside").exists())
        val foreign = store.restore(stored.copy(id = "another-message"))
        assertTrue(foreign.content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
    }

    @Test
    fun archiveFailureDoesNotReturnTruncatedSuccess() = runTest {
        val blocked = temporaryFolder.newFile("not-a-directory")
        try {
            MessageArchiveStore(blocked).prepareForStorage(entity("large".repeat(60_000)))
            throw AssertionError("Expected archive failure")
        } catch (_: IOException) {
            assertTrue(blocked.isFile)
        }
    }

    @Test
    fun cancelledWritesDoNotChangePreviousSnapshot() = runTest {
        val original = entity("old".repeat(60_000))
        val stored = store.prepareForStorage(original)
        val job = launch {
            cancel()
            try {
                store.prepareForStorage(original.copy(content = "new".repeat(60_000)))
                throw AssertionError("Expected cancellation")
            } catch (_: CancellationException) {
                assertEquals(1, root.walkTopDown().count { it.isFile })
            }
        }
        job.join()
        assertEquals(original, store.restore(stored))
        assertFalse(root.walkTopDown().any { it.extension == "tmp" })
    }

    @Test
    fun deleteMessageAndSessionRemoveTheirArchivesOnly() = runTest {
        val first = store.prepareForStorage(entity("large".repeat(60_000)))
        val second = store.prepareForStorage(entity("other".repeat(60_000), "other"))
        store.deleteMessage(first.sessionId, first.id)
        assertTrue(store.restore(first).content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
        assertEquals("other".repeat(60_000), store.restore(second).content)
        store.deleteSession(second.sessionId)
        assertTrue(store.restore(second).content.endsWith(MessageArchiveStore.UNAVAILABLE_MARKER))
        store.clear()
        assertFalse(root.exists())
    }
}
