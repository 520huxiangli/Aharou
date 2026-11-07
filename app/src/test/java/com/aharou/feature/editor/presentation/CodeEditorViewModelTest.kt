package com.aharou.feature.editor.presentation

import android.app.Application
import android.content.Context
import com.aharou.feature.agent.domain.tool.FileVersionRegistry
import com.aharou.feature.editor.data.EditorSettings
import com.aharou.feature.editor.data.EditorSettingsRepository
import com.aharou.feature.editor.lsp.EditorLspManager
import com.aharou.feature.workspace.domain.FileAccessProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class CodeEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val files = mockk<FileAccessProvider>(relaxed = true)
    private val first = mockk<FileAccessProvider>(relaxed = true)
    private val second = mockk<FileAccessProvider>(relaxed = true)
    private val identity = mockk<EditorWorkspaceIdentity>(relaxed = true)
    private val settings = mockk<EditorSettingsRepository>()
    private lateinit var model: CodeEditorViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        every { files.forWorkspace("/one") } returns first
        every { files.forWorkspace("/two") } returns second
        every { first.fingerprint(any()) } returns "first"
        every { second.fingerprint(any()) } returns "second"
        every { identity.matches(any(), any()) } returns true
        every { settings.settingsFlow } returns MutableStateFlow(EditorSettings())
        model = CodeEditorViewModel(mockk<Context>(relaxed = true), files, settings,
            mockk<EditorLspManager>(relaxed = true), mockk<FileVersionRegistry>(relaxed = true), identity)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun lateSave_keepsOriginalBoundRootAndCannotAffectNewBinding() = runTest(dispatcher) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.writeFile(any(), any(), any(), any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/two", "two")
        } finally { release.countDown() }
        assertEquals(SaveResult.Success(request), event.await())
        assertFalse(model.accepts(request))
        assertEquals(EditorUiState.Loading, model.uiState.value)
        verify(exactly = 1) { first.writeFile("~/workspace/a", "saved", any(), any()) }
        verify(exactly = 0) { second.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun switchingServerWithSameRoot_invalidatesOldGeneration() = runTest(dispatcher) {
        val generation = model.bindWorkspace("/one", "server-one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        model.bindWorkspace("/one", "server-two")
        model.unbindWorkspace(generation)
        assertFalse(model.accepts(request))
        assertEquals("server-two", model.createSaveRequest("~/workspace/a", "new")?.workspaceIdentity)
    }

    @Test
    fun staleEnvironmentRequest_failsWithoutWritingEitherRoot() = runTest(dispatcher) {
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "saved")!!
        every { identity.matches("/one", "one") } returns false
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.save(request)
        assertTrue(event.await() is SaveResult.Error)
        verify(exactly = 0) { first.writeFile(any(), any(), any(), any()) }
        verify(exactly = 0) { second.writeFile(any(), any(), any(), any()) }
    }

    @Test
    fun forceSave_afterChangingActivePath_writesOriginalRequest() = runTest(dispatcher) {
        model.bindWorkspace("/one", "one")
        val request = model.createSaveRequest("~/workspace/a", "original")!!
        every { first.exists(any()) } returns false
        model.load("~/workspace/b")
        val event = async(UnconfinedTestDispatcher(testScheduler)) { model.saveEvents.first() }
        model.saveForce(request)
        assertEquals(SaveResult.Success(request), event.await())
        verify(exactly = 1) { first.writeFile("~/workspace/a", "original", any(), any()) }
        verify(exactly = 0) { first.writeFile("~/workspace/b", any(), any(), any()) }
        assertTrue(model.isShowing("/one", "one", "~/workspace/b"))
    }

    @Test
    fun lateLoad_doesNotReplaceNewWorkspaceState() = runTest(dispatcher) {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { first.exists(any()) } answers {
            started.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            false
        }
        every { second.exists(any()) } returns false
        model.bindWorkspace("/one", "one")
        model.load("~/workspace/a")
        runCurrent()
        try {
            assertTrue(withContext(Dispatchers.IO) { started.await(5, TimeUnit.SECONDS) })
            model.bindWorkspace("/two", "two")
            model.load("~/workspace/a")
        } finally { release.countDown() }
        model.uiState.first { it is EditorUiState.Error }
        assertTrue(model.isShowing("/two", "two", "~/workspace/a"))
        verify(exactly = 1) { second.exists("~/workspace/a") }
    }
}
