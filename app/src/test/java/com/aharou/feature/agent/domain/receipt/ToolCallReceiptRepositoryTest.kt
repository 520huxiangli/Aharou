package com.aharou.feature.agent.domain.receipt

import com.aharou.feature.agent.data.local.dao.ToolCallReceiptDao
import com.aharou.feature.agent.data.local.entity.ToolCallReceiptEntity
import com.aharou.feature.agent.domain.tool.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallReceiptRepositoryTest {
    private class Records : ToolCallReceiptDao {
        val rows = linkedMapOf<Long, ToolCallReceiptEntity>()
        var recoveries = 0
        var failRecoveryOnce = false
        override suspend fun insert(receipt: ToolCallReceiptEntity): Long = (rows.size + 1L).also { rows[it] = receipt.copy(id = it) }
        override suspend fun finishRunning(id: Long, status: String, errorCode: String?, resultPreview: String,
            outputPath: String?, durationMs: Long, buildAuthJson: String?): Int {
            val old = rows[id]?.takeIf { it.status == "RUNNING" } ?: return 0
            rows[id] = old.copy(status = status, errorCode = errorCode, resultPreview = resultPreview,
                outputPath = outputPath, durationMs = durationMs, buildAuthJson = buildAuthJson)
            return 1
        }
        override suspend fun recoverRunning(before: Long): Int {
            recoveries++
            if (failRecoveryOnce) { failRecoveryOnce = false; throw IllegalStateException("fixture recovery failure") }
            val running = rows.filterValues { it.status == "RUNNING" && it.createdAt <= before }
            running.forEach { (id, row) -> rows[id] = row.copy(status = "UNKNOWN") }
            return running.size
        }
        override fun observeSession(sessionId: String, limit: Int): Flow<List<ToolCallReceiptEntity>> = MutableStateFlow(rows.values.toList())
        override suspend fun deleteOlderThan(before: Long): Int = 0
        override suspend fun deleteBySession(sessionId: String): Int = 0
    }

    @Test fun beginsAndFinishesSameRowWithoutReplacingTerminal() = runTest {
        val records = Records()
        val repo = ToolCallReceiptRepository(records)
        val id = repo.beginExecution("session", "readFile", mapOf("path" to JsonPrimitive("src/main.kt")))!!
        assertEquals("RUNNING", records.rows[id]?.status)
        repo.finishExecution(id, "readFile", emptyMap(), ToolResult.Success(JsonPrimitive("done")), 12, null)
        repo.finishExecution(id, "readFile", emptyMap(), null, 20, null)
        assertEquals(1, records.rows.size)
        assertEquals("SUCCESS", records.rows[id]?.status)
        assertEquals(12L, records.rows[id]?.durationMs)
    }

    @Test fun recoversOnlyPreviousProcessRowsOnce() = runTest {
        val records = Records()
        records.insert(ToolCallReceiptEntity(sessionId = "old", toolName = "Bash", status = "RUNNING", createdAt = 1))
        val repo = ToolCallReceiptRepository(records)
        val current = repo.beginExecution("new", "Bash", emptyMap())!!
        repo.beginExecution("new", "readFile", emptyMap())
        assertEquals("UNKNOWN", records.rows[1]?.status)
        assertEquals("RUNNING", records.rows[current]?.status)
        assertEquals(1, records.recoveries)
    }

    @Test fun unknownAndInterruptedDoNotClaimOperationFailed() = runTest {
        val records = Records()
        val repo = ToolCallReceiptRepository(records)
        val id = repo.beginExecution("session", "writeFile", emptyMap())!!
        repo.finishExecution(id, "writeFile", emptyMap(), null, 1, null, interrupted = true)
        assertEquals("INTERRUPTED", records.rows[id]?.status)
        assertTrue(records.rows[id]?.resultPreview.isNullOrEmpty())
        assertNull(records.rows[id]?.errorCode)
    }

    @Test fun failedRecoveryIsRetriedBeforeAnyNewRunningRowIsInserted() = runTest {
        val records = Records()
        records.insert(ToolCallReceiptEntity(sessionId = "old", toolName = "Bash", status = "RUNNING", createdAt = 1))
        records.failRecoveryOnce = true
        val repo = ToolCallReceiptRepository(records)
        assertNull(repo.beginExecution("new", "Bash", emptyMap()))
        val current = repo.beginExecution("new", "Bash", emptyMap())!!
        assertEquals("UNKNOWN", records.rows[1]?.status)
        assertEquals("RUNNING", records.rows[current]?.status)
        assertEquals(2, records.recoveries)
    }

    @Test fun bearerHeaderKeepsNeitherSchemeReplacementNorCredentialTail() {
        val masked = ReceiptPrivacy.arguments("Bash", mapOf("command" to JsonPrimitive("curl -H 'Authorization: Bearer opaqueFixtureCredential1234'")))
        assertFalse(masked.contains("opaqueFixtureCredential1234"))
    }

    @Test fun sensitiveValuesAreMaskedButOrdinaryPathRemains() = runTest {
        val records = Records()
        val repo = ToolCallReceiptRepository(records)
        val id = repo.beginExecution("session", "config", mapOf("path" to JsonPrimitive("providers.sample.api_key"),
            "value" to JsonPrimitive("fixture-not-a-real-credential")))!!
        assertFalse(records.rows[id]!!.argsPreview.contains("fixture-not-a-real-credential"))
        val normal = repo.beginExecution("session", "readFile", mapOf("path" to JsonPrimitive("src/main.kt")))!!
        assertTrue(records.rows[normal]!!.argsPreview.contains("src/main.kt"))
    }
}
