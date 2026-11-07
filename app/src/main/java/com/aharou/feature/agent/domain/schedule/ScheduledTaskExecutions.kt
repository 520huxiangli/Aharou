package com.aharou.feature.agent.domain.schedule

import kotlinx.coroutines.Job

internal class ScheduledTaskExecutions {
    private val jobs = mutableMapOf<Int, Job>()
    private var latestStartId = 0

    fun onStart(startId: Int) {
        latestStartId = startId
    }

    fun register(startId: Int, job: Job) {
        jobs[startId] = job
    }

    fun finish(startId: Int): Int? {
        jobs.remove(startId)
        return idleStartId()
    }

    fun idleStartId(): Int? = latestStartId.takeIf { jobs.isEmpty() }

    fun cancelAll() {
        jobs.values.toList().forEach { it.cancel() }
    }
}
