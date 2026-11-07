package com.aharou.feature.agent.domain.runner

import com.aharou.feature.agent.domain.workflow.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job

enum class AgentTurnResult { COMPLETED, FAILED, CANCELLED }

class AgentTurnCompletion {
    private val lock = Any()
    private val result = CompletableDeferred<AgentTurnResult>()
    private var completed = false
    private var failed = false
    private var cancelled = false
    private var boundJob: Job? = null

    fun observe(event: AgentEvent) {
        synchronized(lock) {
            when (event) {
                AgentEvent.Completed -> completed = true
                is AgentEvent.Failed -> failed = true
                else -> Unit
            }
        }
    }

    fun fail(cause: Throwable? = null) {
        synchronized(lock) {
            if (cause is CancellationException) cancelled = true else failed = true
        }
    }

    fun bind(job: Job) {
        synchronized(lock) {
            if (boundJob === job) return
            check(boundJob == null) { "Completion is already bound to another job" }
            boundJob = job
        }
        job.invokeOnCompletion { cause ->
            val outcome = synchronized(lock) {
                when {
                    cancelled || cause is CancellationException -> AgentTurnResult.CANCELLED
                    failed || cause != null || !completed -> AgentTurnResult.FAILED
                    else -> AgentTurnResult.COMPLETED
                }
            }
            result.complete(outcome)
        }
    }

    suspend fun await(): AgentTurnResult = result.await()
}
