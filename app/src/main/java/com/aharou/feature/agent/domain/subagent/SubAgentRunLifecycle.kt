package com.aharou.feature.agent.domain.subagent

data class SubAgentRun(
    val sessionId: String,
    val parentSessionId: String,
    val runId: String,
    val version: Long,
)

class SubAgentRunRejectedException(message: String, val reasonCode: String) : IllegalStateException(message)

interface SubAgentRunLifecycle {
    suspend fun beginRun(sessionId: String, parentSessionId: String, expectedRunId: String? = null): SubAgentRun?

    suspend fun finishRun(
        run: SubAgentRun,
        type: SubAgentEventType,
        detail: String = "",
        accepted: Boolean? = null,
    ): Boolean

    suspend fun stopRun(
        sessionId: String,
        parentSessionId: String,
        deleted: Boolean = false,
        broadcast: Boolean = true,
        expectedRun: SubAgentRun? = null,
    ): Boolean
}
