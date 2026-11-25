package com.aharou.feature.agent.domain.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceLedgerTest {

    @Test
    fun sessionsAreIsolated() {
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "运行测试", "12 passed")
        ledger.record("s2", "readFile", "读构建配置", "minSdk = 26")

        val first = ledger.snapshot("s1")
        val second = ledger.snapshot("s2")
        assertEquals(1, first.size)
        assertEquals("bash", first.single().toolName)
        assertEquals(1, second.size)
        assertEquals("readFile", second.single().toolName)
        assertTrue(ledger.snapshot(null).isEmpty())
    }

    @Test
    fun simplifiedRecordLandsInDefaultBucket() {
        val ledger = EvidenceLedger()
        ledger.record("bash", "ls", "a.kt")

        assertEquals(1, ledger.snapshot(null).size)
        assertEquals(1, ledger.snapshot(EvidenceLedger.DEFAULT_SESSION).size)
    }

    @Test
    fun longResultKeepsHeadAndTail() {
        val head = "HEAD-" + "a".repeat(400)
        val tail = "b".repeat(400) + "-TAIL"
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "长输出", head + tail)

        val evidence = ledger.snapshot("s1").single()
        assertTrue(evidence.truncated)
        assertTrue(evidence.excerpt.length <= EvidenceLedger.MAX_EXCERPT_CHARS)
        assertTrue(evidence.excerpt.startsWith("HEAD-"))
        assertTrue(evidence.excerpt.endsWith("-TAIL"))
        assertTrue(evidence.excerpt.contains("…"))
    }

    @Test
    fun shortResultIsKeptVerbatim() {
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "echo ok", "ok\n")

        val evidence = ledger.snapshot("s1").single()
        assertFalse(evidence.truncated)
        assertEquals("ok\n", evidence.excerpt)
    }

    @Test
    fun argsSummaryIsCollapsedToOneLine() {
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "line1\n   line2", "ok")

        assertEquals("line1 line2", ledger.snapshot("s1").single().argsSummary)
    }

    @Test
    fun clearOnlyRemovesTargetSession() {
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "a", "1")
        ledger.record("s2", "bash", "b", "2")

        ledger.clear("s1")

        assertTrue(ledger.snapshot("s1").isEmpty())
        assertEquals(1, ledger.snapshot("s2").size)
    }

    @Test
    fun snapshotIsACopy() {
        val ledger = EvidenceLedger()
        ledger.record("s1", "bash", "a", "1")

        val snapshot = ledger.snapshot("s1").toMutableList()
        snapshot.clear()

        assertEquals(1, ledger.snapshot("s1").size)
    }
}
