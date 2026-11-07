package com.aharou.feature.agent.data.local.task

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.data.local.dao.AgentMessageDao
import com.aharou.feature.agent.data.local.dao.AgentTaskDao
import com.aharou.feature.agent.data.local.dao.ChatSessionDao
import com.aharou.feature.agent.data.local.entity.AgentTaskEntity
import com.aharou.feature.agent.data.local.entity.ChatSessionEntity
import com.aharou.feature.agent.domain.subagent.CommunicationEnabledSource
import com.aharou.feature.agent.domain.subagent.MaxRunningSource
import com.aharou.feature.agent.domain.subagent.SubAgentEvent
import com.aharou.feature.agent.domain.subagent.SubAgentEventBus
import com.aharou.feature.agent.domain.subagent.SubAgentEventType
import com.aharou.feature.agent.domain.subagent.SubAgentRunRejectedException
import com.aharou.feature.agent.domain.subagent.SubAgentWriteLease
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SubAgentTaskTrackerTest {
    private class Tasks : AgentTaskDao {
        val rows = mutableMapOf<String, AgentTaskEntity>()
        override suspend fun insertIfAbsent(task: AgentTaskEntity): Long {
            if (task.id in rows) return -1
            rows[task.id] = task
            return 1
        }
        override suspend fun compareAndSetStatus(taskId: String, fromStatus: String, toStatus: String,
            result: String?, updatedAt: Long, finishedAt: Long?, expectedUpdatedAt: Long): Int {
            val old = rows[taskId] ?: return 0
            if (old.status != fromStatus || old.updatedAt != expectedUpdatedAt) return 0
            rows[taskId] = old.copy(status = toStatus, result = result, updatedAt = updatedAt, finishedAt = finishedAt)
            return 1
        }
        override suspend fun beginRun(taskId: String, expectedUpdatedAt: Long, updatedAt: Long): Int {
            val old = rows[taskId] ?: return 0
            if (old.updatedAt != expectedUpdatedAt) return 0
            rows[taskId] = old.copy(status = "RUNNING", result = null, finishedAt = null, updatedAt = updatedAt)
            return 1
        }
        override suspend fun getById(taskId: String): AgentTaskEntity? = rows[taskId]
        override suspend fun getByStatus(status: String): List<AgentTaskEntity> = rows.values.filter { it.status == status }
    }

    private val tasks = Tasks()
    private val messages = mockk<AgentMessageDao>(relaxed = true)
    private val sessions = mockk<ChatSessionDao>()
    private val bus = SubAgentEventBus(MaxRunningSource { 5 }, CommunicationEnabledSource { true })
    private val tracker = SubAgentTaskTracker(tasks, messages, bus, sessions)
    private val child = ChatSessionEntity("sub", "title", 1, 1, parentId = "parent")

    @Before
    fun setup() {
        mockkObject(FileLogger)
        every { FileLogger.i(any(), any()) } returns Unit
        coEvery { sessions.getById("sub") } returns child
        coEvery { sessions.getById("parent") } returns ChatSessionEntity("parent", "parent", 1, 1)
    }

    @After
    fun teardown() {
        unmockkObject(FileLogger)
    }

    @Test
    fun completedTaskBeginsAgainPreservingIdentityAndClearingResult() = runTest {
        tasks.rows["sub"] = AgentTaskEntity("sub", "parent", "original", "COMPLETED", "old", 1, 2, 2)
        tracker.start(backgroundScope)
        runCurrent()
        val run = requireNotNull(tracker.beginRun("sub", "parent"))
        val row = requireNotNull(tasks.rows["sub"])
        assertEquals("RUNNING", row.status)
        assertEquals("original", row.instruction)
        assertEquals(1L, row.createdAt)
        assertEquals("parent", row.parentSessionId)
        assertEquals(run.version, row.updatedAt)
        assertNull(row.result)
        assertNull(row.finishedAt)
        assertTrue(tracker.finishRun(run, SubAgentEventType.COMPLETED))
        assertEquals("COMPLETED", tasks.rows["sub"]?.status)
        val next = requireNotNull(tracker.beginRun("sub", "parent"))
        assertFalse(tracker.finishRun(run, SubAgentEventType.FAILED))
        assertFalse(tracker.stopRun("sub", "parent", expectedRun = run))
        assertEquals(next, bus.currentRun("sub"))
        assertEquals("RUNNING", tasks.rows["sub"]?.status)
    }

    @Test
    fun recoveryBarrierBlocksNewRunsUntilOldTasksAreRecovered() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayedTasks = mockk<AgentTaskDao>(relaxed = true)
        coEvery { delayedTasks.getByStatus("RUNNING") } coAnswers {
            entered.complete(Unit)
            release.await()
            emptyList()
        }
        coEvery { delayedTasks.getById("sub") } returns null
        coEvery { delayedTasks.insertIfAbsent(any()) } returns 1L
        val delayed = SubAgentTaskTracker(delayedTasks, messages, bus, sessions)
        delayed.start(backgroundScope)
        val start = async { delayed.beginRun("sub", "parent") }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertFalse(start.isCompleted)
        assertEquals(0, bus.activeCount)
        release.complete(Unit)
        runCurrent()
        assertNotNull(start.await())
    }

    @Test
    fun recoveryCancelDeleteAndReservedStopHaveDistinctFinalStates() = runTest {
        tasks.rows["sub"] = AgentTaskEntity("sub", "parent", "old", "RUNNING", createdAt = 1, updatedAt = 100)
        tracker.start(backgroundScope)
        runCurrent()
        assertEquals("INTERRUPTED", tasks.rows["sub"]?.status)
        val first = requireNotNull(tracker.beginRun("sub", "parent"))
        assertTrue(tracker.finishRun(first, SubAgentEventType.STOPPED))
        assertEquals("CANCELLED", tasks.rows["sub"]?.status)
        val next = requireNotNull(tracker.beginRun("sub", "parent"))
        assertTrue(tracker.stopRun("sub", "parent", deleted = true, expectedRun = next))
        assertEquals("CANCELLED", tasks.rows["sub"]?.status)
        assertNull(tracker.beginRun("sub", "parent"))
        bus.emit(SubAgentEvent("reserved", "parent", SubAgentEventType.SPAWNED))
        assertTrue(tracker.stopRun("reserved", "parent", expectedRun = bus.currentRun("reserved")))
        assertEquals(0, bus.activeCount)
    }

    @Test
    fun stoppedFirstSpawnCannotResumeAfterDelayedSessionLookup() = runTest {
        tracker.start(backgroundScope)
        runCurrent()
        bus.emit(SubAgentEvent("sub", "parent", SubAgentEventType.SPAWNED, "original"))
        val reserved = requireNotNull(bus.currentRun("sub"))
        assertTrue(tracker.stopRun("sub", "parent", expectedRun = reserved))
        assertNull(tracker.beginRun("sub", "parent", reserved.runId))
        assertEquals(0, bus.activeCount)
        assertEquals("CANCELLED", tasks.rows["sub"]?.status)
        assertEquals("original", tasks.rows["sub"]?.instruction)
    }

    @Test
    fun rejectedReservedSpawnPublishesFailedAndFreesOnlyItsSlot() = runTest {
        val leases = SubAgentWriteLease()
        leases.register("other", setOf("app"))
        leases.register("sub", setOf("app/src"))
        val conflictBus = SubAgentEventBus(MaxRunningSource { 5 }, CommunicationEnabledSource { true }, leases)
        val conflictTracker = SubAgentTaskTracker(tasks, messages, conflictBus, sessions)
        conflictTracker.start(backgroundScope)
        runCurrent()
        val other = requireNotNull(conflictBus.beginRun("other", "parent", 1))
        conflictBus.emit(SubAgentEvent("sub", "parent", SubAgentEventType.SPAWNED, "original"))
        val reserved = requireNotNull(conflictBus.currentRun("sub"))
        val rejection = try {
            conflictTracker.beginRun("sub", "parent", reserved.runId)
            null
        } catch (error: SubAgentRunRejectedException) {
            error
        }
        assertEquals("WRITE_LEASE_CONFLICT", rejection?.reasonCode)
        assertEquals("FAILED", tasks.rows["sub"]?.status)
        assertEquals("original", tasks.rows["sub"]?.instruction)
        assertEquals(SubAgentEventType.FAILED, conflictBus.terminalEvent("sub")?.type)
        assertEquals(setOf("other"), conflictBus.activeSubSessionIds.value)
        assertEquals(other, conflictBus.currentRun("other"))
    }

    @Test
    fun sameMillisecondVersionsAndOldCasCannotOverwriteNewRun() = runTest {
        assertEquals(101L, tracker.nextVersion(100, 100))
        assertEquals(101L, tracker.nextVersion(100, 99))
        assertEquals(102L, tracker.nextVersion(101, 100))
        tasks.rows["sub"] = AgentTaskEntity("sub", "parent", "original", "RUNNING", createdAt = 1, updatedAt = 102)
        assertEquals(0, tasks.compareAndSetStatus("sub", "RUNNING", "FAILED", null, 103, 103, 100))
        assertEquals(102L, tasks.rows["sub"]?.updatedAt)
        assertEquals("RUNNING", tasks.rows["sub"]?.status)
    }
}
