package com.aharou.feature.agent.presentation

import android.app.Application
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.schedule.ScheduledRunRequest
import com.aharou.feature.agent.domain.schedule.ScheduledTaskOutcome
import com.aharou.feature.agent.domain.session.SessionUseCase
import com.aharou.feature.agent.domain.workflow.AgentEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ScheduledTaskRunHelperTest {
    private val dao = mockk<ScheduledTaskDao>(relaxed = true)
    private val sessions = mockk<SessionUseCase>()
    private val request = ScheduledRunRequest("task", "Task", "prompt", "session", "stale-root", 123L)
    private val session = ChatSessionEntity(
        id = "session", title = "Title", workspacePath = "session-root", createdAt = 0L, updatedAt = 0L,
    )

    private fun existingSession() {
        coEvery { sessions.getSessionById("session") } returns session
    }

    @Test
    fun waitsForExecutionCleanupAndUsesSessionWorkspace() = runTest {
        existingSession()
        val release = CompletableDeferred<Unit>()
        val helper = launch {
            runScheduledTask(request, sessions, dao) { id, root, completion ->
                assertEquals("session", id)
                assertEquals("session-root", root)
                launch {
                    completion.observe(AgentEvent.Completed)
                    release.await()
                }
            }
        }
        runCurrent()
        coVerify(exactly = 0) { dao.recordOutcomeForRun(any(), any(), any(), any()) }
        assertFalse(helper.isCompleted)
        release.complete(Unit)
        helper.join()
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.COMPLETED, any()) }
    }

    @Test
    fun failedThenCompletedIsRecordedAsFailed() = runTest {
        existingSession()
        runScheduledTask(request, sessions, dao) { _, _, completion ->
            completion.observe(AgentEvent.Failed("network"))
            completion.observe(AgentEvent.Completed)
            Job().apply { complete() }
        }
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.FAILED, any()) }
    }

    @Test
    fun emptyExecutionIsRecordedAsFailed() = runTest {
        existingSession()
        runScheduledTask(request, sessions, dao) { _, _, _ -> Job().apply { complete() } }
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.FAILED, any()) }
    }

    @Test
    fun throwingBeforeExecutionIsRecordedAsFailed() = runTest {
        existingSession()
        runScheduledTask(request, sessions, dao) { _, _, _ -> throw IllegalStateException("launch") }
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.FAILED, any()) }
    }

    @Test
    fun missingSessionAndWorkspaceIsRecordedAsFailed() = runTest {
        coEvery { sessions.getSessionById("session") } returns null
        runScheduledTask(request.copy(workspacePath = null), sessions, dao) { _, _, _ ->
            throw AssertionError("Must not execute")
        }
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.FAILED, any()) }
    }

    @Test
    fun cancellationWaitsForIndependentExecutionCleanupBeforeOutcome() = runTest {
        existingSession()
        val cleanup = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var execution: Job? = null
        val helper = launch {
            runScheduledTask(request, sessions, dao) { _, _, completion ->
                backgroundScope.launch {
                    try {
                        completion.observe(AgentEvent.Completed)
                        entered.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    } finally {
                        withContext(NonCancellable) { cleanup.await() }
                    }
                }.also { execution = it }
            }
        }
        entered.await()
        helper.cancel()
        runCurrent()
        assertEquals(true, execution!!.isCancelled)
        assertFalse(helper.isCompleted)
        coVerify(exactly = 0) { dao.recordOutcomeForRun(any(), any(), any(), any()) }
        cleanup.complete(Unit)
        helper.join()
        coVerify { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.FAILED, any()) }
        coVerify(exactly = 0) { dao.recordOutcomeForRun("task", 123L, ScheduledTaskOutcome.COMPLETED, any()) }
    }
}
