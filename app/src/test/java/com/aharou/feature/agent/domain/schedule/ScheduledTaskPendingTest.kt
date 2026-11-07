package com.aharou.feature.agent.domain.schedule

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.aharou.feature.agent.data.local.dao.ScheduledTaskDao
import com.aharou.feature.agent.data.local.database.AgentDatabase
import com.aharou.feature.agent.data.local.entity.ScheduledTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ScheduledTaskPendingTest {
    private lateinit var database: AgentDatabase
    private lateinit var dao: ScheduledTaskDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AgentDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = database.scheduledTaskDao()
    }

    @After
    fun tearDown() = database.close()

    private fun task() = ScheduledTaskEntity(
        id = "task", name = "Task", prompt = "prompt", intervalMinutes = 15,
        scheduleType = ScheduledTaskEntity.TYPE_ONCE, onceAtMillis = 100L,
        nextRunAt = 100L, createdAt = 0L, updatedAt = 0L,
    )

    private suspend fun pending(prompt: String = "rendered") {
        dao.upsert(task())
        assertEquals(1, dao.claim("task", 100L, Long.MAX_VALUE, 200L))
        assertEquals(1, dao.markPendingRun("task", prompt, 200L, disableOnce = true))
    }

    @Test
    fun disabledOncePendingIsRecoveredExactlyOnce() = runBlocking {
        pending()
        assertFalse(dao.getById("task")!!.enabled)
        assertEquals(1, dao.pendingRunsCount())
        val requests = mutableListOf<ScheduledRunRequest>()
        val runner = ScheduledPendingRunner(dao) { requests.add(it) }
        runner.drain()
        runner.drain()
        assertEquals(1, requests.size)
        assertEquals("rendered", requests.single().prompt)
        assertEquals(200L, requests.single().claimedAt)
        assertEquals(0, dao.pendingRunsCount())
    }

    @Test
    fun userDisablingPendingClearsIt() = runBlocking {
        pending()
        ScheduledTaskRepository(dao).setEnabled("task", false, 300L)
        assertEquals(0, dao.pendingRunsCount())
        assertNull(dao.getById("task")!!.pendingRunPrompt)
    }

    @Test
    fun disableBeforeMarkPendingCannotBeUndone() = runBlocking {
        dao.upsert(task())
        dao.claim("task", 100L, Long.MAX_VALUE, 200L)
        dao.setEnabled("task", false, 201L)
        assertEquals(0, dao.markPendingRun("task", "late", 200L, disableOnce = true))
        assertEquals(0, dao.pendingRunsCount())
    }

    @Test
    fun savingDisabledTaskClearsPending() = runBlocking {
        pending()
        val stale = dao.getById("task")!!
        val repo = ScheduledTaskRepository(dao)
        repo.save(stale, "Renamed", stale.prompt, null, null, 15, 0, false,
            scheduleType = stale.scheduleType, onceAtMillis = stale.onceAtMillis, now = 300L)
        assertNull(dao.getById("task")!!.pendingRunPrompt)
    }

    @Test
    fun staleSaveCannotRestoreConsumedPendingOrResetExecutionState() = runBlocking {
        pending()
        val stale = dao.getById("task")!!
        dao.clearPendingRun("task", "rendered", 200L, 300L)
        dao.recordOutcomeForRun("task", 200L, ScheduledTaskOutcome.COMPLETED, 301L)
        ScheduledTaskRepository(dao).upsert(stale.copy(name = "Renamed", enabled = true,
            lastRunAt = null, runCount = 0, lastOutcome = null, nextRunAt = 100L))
        val saved = dao.getById("task")!!
        assertNull(saved.pendingRunPrompt)
        assertEquals(1, saved.runCount)
        assertEquals(200L, saved.lastRunAt)
        assertEquals(ScheduledTaskOutcome.COMPLETED, saved.lastOutcome)
        assertEquals(Long.MAX_VALUE, saved.nextRunAt)
    }

    @Test
    fun stalePromptCannotConsumeReplacementPending() = runBlocking {
        pending("old")
        dao.upsert(dao.getById("task")!!.copy(pendingRunPrompt = "new"))
        assertEquals(0, dao.clearPendingRun("task", "old", 200L, 300L))
        assertEquals("new", dao.getById("task")!!.pendingRunPrompt)
    }

    @Test
    fun staleRunCannotConsumeSamePromptFromNewRun() = runBlocking {
        pending("same")
        dao.upsert(dao.getById("task")!!.copy(lastRunAt = 400L, pendingRunPrompt = "same"))
        assertEquals(0, dao.clearPendingRun("task", "same", 200L, 500L))
        assertEquals("same", dao.getById("task")!!.pendingRunPrompt)
    }

    @Test
    fun staleRunCannotOverwriteNewOutcome() = runBlocking {
        pending()
        dao.upsert(dao.getById("task")!!.copy(lastRunAt = 400L, lastOutcome = "dispatched"))
        assertEquals(0, dao.recordOutcomeForRun("task", 200L, ScheduledTaskOutcome.COMPLETED, 500L))
        assertEquals("dispatched", dao.getById("task")!!.lastOutcome)
    }

    @Test
    fun concurrentPendingClaimsHaveSingleWinner() = runBlocking {
        pending()
        val claimed = (1..2).map {
            async(Dispatchers.IO) { dao.clearPendingRun("task", "rendered", 200L, 300L) }
        }.awaitAll()
        assertEquals(1, claimed.sum())
    }

    @Test
    fun concurrentDueClaimsHaveSingleWinner() = runBlocking {
        dao.upsert(task())
        val claimed = (1..2).map {
            async(Dispatchers.IO) { dao.claim("task", 100L, Long.MAX_VALUE, 200L) }
        }.awaitAll()
        assertEquals(1, claimed.sum())
        assertEquals(1, dao.getById("task")!!.runCount)
    }

    @Test
    fun disabledTaskCannotBeClaimedFromStaleDueSnapshot() = runBlocking {
        dao.upsert(task().copy(enabled = false))
        assertEquals(0, dao.claim("task", 100L, Long.MAX_VALUE, 200L))
    }

    @Test
    fun racingUserDisableAndPendingWriteLeaveNoPending() = runBlocking {
        dao.upsert(task())
        dao.claim("task", 100L, Long.MAX_VALUE, 200L)
        listOf(
            async(Dispatchers.IO) { dao.markPendingRun("task", "rendered", 200L, disableOnce = true) },
            async(Dispatchers.IO) { dao.setEnabled("task", false, 300L) },
        ).awaitAll()
        assertEquals(0, dao.pendingRunsCount())
        assertFalse(dao.getById("task")!!.enabled)
    }

    @Test
    fun racingDisabledSaveAndPendingWriteLeaveNoPending() = runBlocking {
        val stale = task()
        dao.upsert(stale)
        dao.claim("task", 100L, Long.MAX_VALUE, 200L)
        listOf(
            async(Dispatchers.IO) { dao.markPendingRun("task", "rendered", 200L, disableOnce = true) },
            async(Dispatchers.IO) { ScheduledTaskRepository(dao).upsert(stale.copy(enabled = false)) },
        ).awaitAll()
        val saved = dao.getById("task")!!
        assertNull(saved.pendingRunPrompt)
        assertFalse(saved.enabled)
        assertEquals(1, saved.runCount)
        assertEquals(200L, saved.lastRunAt)
    }

    @Test
    fun consumedPendingIsNotRetriedAfterExecutionFailure() = runBlocking {
        pending()
        val failure = IllegalStateException("execute")
        try {
            ScheduledPendingRunner(dao) { throw failure }.drain()
            throw AssertionError("Expected execution failure")
        } catch (e: IllegalStateException) {
            assertEquals(failure, e)
        }
        assertEquals(0, dao.pendingRunsCount())
    }
}
