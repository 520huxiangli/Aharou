package com.aharou.feature.agent.domain.schedule

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledTaskExecutionsTest {
    @Test
    fun finishingOneTaskLeavesOtherTaskRunning() {
        val executions = ScheduledTaskExecutions()
        val first = Job()
        val second = Job()
        executions.onStart(1)
        executions.register(1, first)
        executions.onStart(2)
        executions.register(2, second)
        first.complete()
        assertNull(executions.finish(1))
        assertTrue(second.isActive)
        second.complete()
        assertEquals(2, executions.finish(2))
    }

    @Test
    fun invalidLatestStartDoesNotStopExistingTask() {
        val executions = ScheduledTaskExecutions()
        val job = Job()
        executions.onStart(3)
        executions.register(3, job)
        executions.onStart(4)
        assertNull(executions.idleStartId())
        assertTrue(job.isActive)
        job.complete()
        assertEquals(4, executions.finish(3))
    }

    @Test
    fun stopCancelsEveryActiveTask() {
        val executions = ScheduledTaskExecutions()
        val first = Job()
        val second = Job()
        executions.onStart(1)
        executions.register(1, first)
        executions.onStart(2)
        executions.register(2, second)
        executions.onStart(3)
        executions.cancelAll()
        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertNull(executions.finish(1))
        assertEquals(3, executions.finish(2))
    }

    @Test
    fun reverseFinishOrderStillStopsWithLatestStartId() {
        val executions = ScheduledTaskExecutions()
        executions.onStart(7)
        executions.register(7, Job())
        executions.onStart(8)
        executions.register(8, Job())
        assertNull(executions.finish(8))
        assertEquals(8, executions.finish(7))
        assertFalse(executions.idleStartId() == 7)
    }
}
