package com.aharou.feature.agent.domain.runner

import com.aharou.feature.agent.domain.workflow.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AgentTurnCompletionTest {
    @Test
    fun completedWaitsForJobToFinish() = runTest {
        val completion = AgentTurnCompletion()
        val release = CompletableDeferred<Unit>()
        val job = launch {
            completion.observe(AgentEvent.Completed)
            release.await()
        }
        completion.bind(job)
        val result = async { completion.await() }
        runCurrent()
        assertFalse(result.isCompleted)
        release.complete(Unit)
        assertEquals(AgentTurnResult.COMPLETED, result.await())
    }

    @Test
    fun failedThenCompletedRemainsFailed() = runTest {
        val completion = AgentTurnCompletion()
        completion.observe(AgentEvent.Failed("network"))
        completion.observe(AgentEvent.Completed)
        completion.bind(Job().apply { complete() })
        assertEquals(AgentTurnResult.FAILED, completion.await())
    }

    @Test
    fun emptyStreamFails() = runTest {
        val completion = AgentTurnCompletion()
        completion.bind(Job().apply { complete() })
        assertEquals(AgentTurnResult.FAILED, completion.await())
    }

    @Test
    fun caughtExceptionAfterCompletedFails() = runTest {
        val completion = AgentTurnCompletion()
        completion.observe(AgentEvent.Completed)
        completion.fail(IllegalStateException("persist"))
        completion.bind(Job().apply { complete() })
        assertEquals(AgentTurnResult.FAILED, completion.await())
    }

    @Test
    fun cancelledJobCannotReportSuccess() = runTest {
        val completion = AgentTurnCompletion()
        completion.observe(AgentEvent.Completed)
        completion.bind(Job().apply { cancel() })
        assertEquals(AgentTurnResult.CANCELLED, completion.await())
    }

    @Test
    fun bindingAlreadyFinishedJobIsSupported() = runTest {
        val completion = AgentTurnCompletion()
        completion.observe(AgentEvent.Completed)
        completion.bind(Job().apply { complete() })
        assertEquals(AgentTurnResult.COMPLETED, completion.await())
    }

    @Test
    fun bindingSameJobTwiceIsIdempotent() = runTest {
        val completion = AgentTurnCompletion()
        val job = Job()
        completion.bind(job)
        completion.bind(job)
        completion.observe(AgentEvent.Completed)
        job.complete()
        assertEquals(AgentTurnResult.COMPLETED, completion.await())
    }

    @Test
    fun exceptionalJobFailsWithoutCompleted() = runTest {
        val completion = AgentTurnCompletion()
        completion.bind(Job().apply { completeExceptionally(IllegalStateException("flow")) })
        assertEquals(AgentTurnResult.FAILED, completion.await())
    }
}
