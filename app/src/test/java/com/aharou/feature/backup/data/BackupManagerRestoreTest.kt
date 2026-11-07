package com.aharou.feature.backup.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.AgentMessageEntity
import com.aharou.feature.agent.domain.session.MessageArchiveStore
import com.aharou.feature.backup.domain.AgentMessageDto
import com.aharou.feature.backup.domain.BackupMetadata
import com.aharou.feature.backup.domain.BackupSnapshot
import com.aharou.feature.backup.domain.ChatSessionDto
import com.aharou.feature.backup.domain.ProviderConflictStrategy
import com.aharou.feature.backup.domain.ProviderDto
import com.aharou.feature.settings.data.local.entity.AIProviderEntity
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.model.Workspace
import com.aharou.feature.workspace.domain.model.WorkspaceType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackupManagerRestoreTest {
    private lateinit var context: Context
    private lateinit var database: AgentDatabase
    private lateinit var workspaces: WorkspaceRepository
    private lateinit var settings: BackupRestoreSettings
    private lateinit var workspace: File
    private lateinit var name: String
    private val json = Json { encodeDefaults = true }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AgentDatabase::class.java).allowMainThreadQueries().build()
        name = "restore-${UUID.randomUUID()}"
        workspace = File(context.filesDir, "projects/$name").apply { mkdirs() }
        workspaces = mockk(relaxed = true)
        every { workspaces.workspaces } returns MutableStateFlow(emptyList())
        every { workspaces.planLocalBackupWorkspace(any(), any()) } answers {
            val candidate = firstArg<String>()
            Workspace(candidate, File(context.filesDir, "projects/$candidate").path, WorkspaceType.INTERNAL)
        }
        settings = mockk(relaxed = true)
        coEvery { settings.apply(any(), any()) } returns Unit
    }

    @After
    fun tearDown() {
        database.close()
        workspace.deleteRecursively()
    }

    private fun manager(messages: AgentMessageDao = database.agentMessageDao()) = BackupManagerImpl(
        context = context,
        aiProviderDao = database.aiProviderDao(),
        remoteConnectionDao = database.remoteConnectionDao(),
        chatSessionDao = database.chatSessionDao(),
        agentMessageDao = messages,
        messageArchiveStore = MessageArchiveStore(File(context.filesDir, "message-archive")),
        todoItemDao = database.todoItemDao(),
        mcpConfigRepository = mockk(relaxed = true),
        mcpManager = mockk(relaxed = true),
        permissionRulesRepository = mockk(relaxed = true),
        themeSettingsRepository = mockk(relaxed = true),
        keepaliveSettingsRepository = mockk(relaxed = true),
        screenOnSettingsRepository = mockk(relaxed = true),
        agentSoundSettingsRepository = mockk(relaxed = true),
        generalSettingsRepository = mockk(relaxed = true),
        logSettingsRepository = mockk(relaxed = true),
        visionModelSettingsRepository = mockk(relaxed = true),
        compactionModelSettingsRepository = mockk(relaxed = true),
        syncSettingsRepository = mockk(relaxed = true),
        workspaceRepository = workspaces,
        database = database,
        restoreSettings = settings
    )

    private fun metadata() = json.encodeToString(BackupMetadata.serializer(), BackupMetadata(80, createdAt = 0))
    private fun session() = ChatSessionDto("session", "restored", 1, 1, workspace.path)
    private fun sessionLine() = json.encodeToString(ChatSessionDto.serializer(), session())

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val output = ByteArrayOutputStream()
        GzipCompressorOutputStream(output).use { gzip ->
            TarArchiveOutputStream(gzip).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_GNU)
                entries.forEach { (path, text) ->
                    val bytes = text.toByteArray(Charsets.UTF_8)
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return output.toByteArray()
    }

    @Test
    fun badLastJsonLineLeavesDatabaseAndFilesUntouched() = runBlocking {
        val target = File(workspace, "file.txt").apply { writeText("original") }
        val validRows = (1..500).joinToString("\n") { index ->
            json.encodeToString(ChatSessionDto.serializer(), session().copy(id = "s$index"))
        }
        val input = archive(
            "metadata.json" to metadata(), "workspaces/$name/file.txt" to "new",
            "chatSessions.jsonl" to "$validRows\n{broken"
        )
        assertTrue(manager().import(input.inputStream(), null).isFailure)
        assertTrue(database.chatSessionDao().getAllOnce().isEmpty())
        assertEquals("original", target.readText())
        coVerify(exactly = 0) { settings.apply(any(), any()) }
    }

    @Test
    fun badGzipTrailerLeavesDatabaseUntouched() = runBlocking {
        val input = archive("metadata.json" to metadata(), "chatSessions.jsonl" to sessionLine())
        input[input.size - 8] = (input[input.size - 8].toInt() xor 1).toByte()
        assertTrue(manager().import(input.inputStream(), null).isFailure)
        assertTrue(database.chatSessionDao().getAllOnce().isEmpty())
        coVerify(exactly = 0) { settings.apply(any(), any()) }
    }

    @Test
    fun missingMetadataLeavesWorkspaceUntouched() = runBlocking {
        val target = File(workspace, "file.txt").apply { writeText("original") }
        assertTrue(manager().import(archive("workspaces/$name/file.txt" to "new").inputStream(), null).isFailure)
        assertEquals("original", target.readText())
    }

    @Test
    fun databaseWriteFailureRollsBackEarlierRowsAndFiles() = runBlocking {
        val target = File(workspace, "file.txt").apply { writeText("original") }
        val actual = database.agentMessageDao()
        val failing = object : AgentMessageDao by actual {
            override suspend fun insertAll(messages: List<AgentMessageEntity>) { throw IOException("injected DAO failure") }
        }
        val message = json.encodeToString(AgentMessageDto.serializer(), AgentMessageDto("m", "session", "USER", "hello", 1))
        val input = archive("metadata.json" to metadata(), "workspaces/$name/file.txt" to "new",
            "chatSessions.jsonl" to sessionLine(), "messages.jsonl" to message)
        assertTrue(manager(failing).import(input.inputStream(), null).isFailure)
        assertTrue(database.chatSessionDao().getAllOnce().isEmpty())
        assertEquals("original", target.readText())
    }

    @Test
    fun settingsFailureRollsBackDatabaseFilesAndRegisteredSettingUndo() = runBlocking {
        var setting = "original"
        coEvery { settings.apply(any(), any()) } coAnswers {
            val changes = secondArg<BackupRestoreChanges>()
            changes.record { setting = "original" }
            setting = "restored"
            throw IOException("injected settings failure")
        }
        val target = File(workspace, "file.txt").apply { writeText("original") }
        val input = archive("metadata.json" to metadata(), "workspaces/$name/file.txt" to "new",
            "chatSessions.jsonl" to sessionLine())
        assertTrue(manager().import(input.inputStream(), null).isFailure)
        assertTrue(database.chatSessionDao().getAllOnce().isEmpty())
        assertEquals("original", target.readText())
        assertEquals("original", setting)
    }

    @Test
    fun cancellationDoesNotCommitAndRunsCompensation() = runBlocking {
        coEvery { settings.apply(any(), any()) } throws CancellationException("cancel restore")
        val target = File(workspace, "file.txt").apply { writeText("original") }
        val input = archive("metadata.json" to metadata(), "workspaces/$name/file.txt" to "new",
            "chatSessions.jsonl" to sessionLine())
        var cancelled = false
        try { manager().import(input.inputStream(), null).getOrThrow() } catch (_: CancellationException) { cancelled = true }
        assertTrue(cancelled)
        assertEquals("original", target.readText())
        assertTrue(database.chatSessionDao().getAllOnce().isEmpty())
    }

    @Test
    fun legacySnapshotHonorsAllProviderConflictStrategies() = runBlocking {
        val provider = ProviderDto("provider", "imported", "OPENAI", "", "https://example.invalid", "model")
        val snapshot = BackupSnapshot(80, createdAt = 0, providers = listOf(provider))
        val input = archive("snapshot.json" to json.encodeToString(BackupSnapshot.serializer(), snapshot))
        ProviderConflictStrategy.entries.forEach { strategy ->
            database.clearAllTables()
            database.aiProviderDao().insertProvider(AIProviderEntity("provider", "local", "OPENAI", "",
                baseUrl = "https://local.invalid", defaultModel = "model"))
            assertTrue(manager().import(input.inputStream(), null, providerConflict = strategy).isSuccess)
            val rows = database.aiProviderDao().getAllProvidersOnce()
            when (strategy) {
                ProviderConflictStrategy.KEEP_LOCAL -> assertEquals("local", rows.single().name)
                ProviderConflictStrategy.OVERWRITE -> assertEquals("imported", rows.single().name)
                ProviderConflictStrategy.KEEP_BOTH -> {
                    assertEquals(2, rows.size)
                    assertTrue(rows.any { it.id != "provider" })
                }
            }
        }
    }
}
