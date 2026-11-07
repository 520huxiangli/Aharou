package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.permission.PermissionChoice
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 多会话并行授权语义：不同会话可同时挂起各自的授权请求（互不阻塞），
 * 侧边栏据 [ToolPermissionManager.awaitingSessionIds] 点亮，弹窗按插入顺序逐个展示，
 * resolve 按请求 id 精确唤醒对应会话。
 */
class ToolPermissionManagerTest {

    private fun req(id: String, sessionId: String) = PendingToolPermission(
        id = id,
        toolName = "Bash",
        title = "确认执行工具",
        summary = "",
        details = "",
        argsPreview = "",
        sessionId = sessionId
    )

    @Test
    fun twoSessionsPendSimultaneously_resolveByIdTargetsCorrectSession() = runBlocking {
        val mgr = ToolPermissionManager()

        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        val d2 = async { mgr.awaitApproval(req("id2", "s2")) }

        withTimeout(1000) { while (mgr.awaitingSessionIds.value.size < 2) yield() }

        // 两个会话都点亮，互不阻塞
        assertEquals(setOf("s1", "s2"), mgr.awaitingSessionIds.value)
        // 弹窗展示最早进入的请求
        assertEquals("id1", mgr.pendingRequest.value?.id)
        // 按会话查询待决请求
        assertEquals("id2", mgr.pendingForSession("s2")?.id)

        // 先解决 s2（乱序），只唤醒 s2
        mgr.resolve("id2", PermissionChoice.ONCE)
        assertEquals(PermissionChoice.ONCE, d2.await())

        withTimeout(1000) { while (mgr.awaitingSessionIds.value != setOf("s1")) yield() }
        assertEquals("id1", mgr.pendingRequest.value?.id)
        assertNull(mgr.pendingForSession("s2"))

        mgr.resolve("id1", PermissionChoice.ALWAYS)
        assertEquals(PermissionChoice.ALWAYS, d1.await())

        assertNull(mgr.pendingRequest.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun resolveUnknownId_isNoop() = runBlocking {
        val mgr = ToolPermissionManager()
        val d1 = async { mgr.awaitApproval(req("id1", "s1")) }
        withTimeout(1000) { while (mgr.pendingRequest.value == null) yield() }

        mgr.resolve("nonexistent", PermissionChoice.REJECT)
        // 原请求仍挂起
        assertTrue(d1.isActive)
        assertEquals("id1", mgr.pendingRequest.value?.id)

        mgr.resolve("id1", PermissionChoice.REJECT)
        assertEquals(PermissionChoice.REJECT, d1.await())
    }

    @Test
    fun batchApprovalResolvesAllItemsWithSameChoice() = runBlocking {
        val mgr = ToolPermissionManager()
        val batch = PendingPermissionBatch("b1", "s1", listOf(req("id1", "s1"), req("id2", "s1")))
        val d = async { mgr.awaitBatchApproval(batch) }
        withTimeout(1000) { while (mgr.pendingBatch.value == null) yield() }

        assertEquals("b1", mgr.pendingBatch.value?.id)
        assertEquals("b1", mgr.pendingBatchForSession("s1")?.id)
        assertEquals(setOf("s1"), mgr.awaitingSessionIds.value)

        mgr.resolveBatch("b1", PermissionChoice.ALWAYS)
        assertEquals(mapOf("id1" to PermissionChoice.ALWAYS, "id2" to PermissionChoice.ALWAYS), d.await())
        assertNull(mgr.pendingBatch.value)
        assertTrue(mgr.awaitingSessionIds.value.isEmpty())
    }

    @Test
    fun tryResolveChecksSessionAndIdAndReturnsFalseForDuplicate() = runBlocking {
        val mgr = ToolPermissionManager()
        val result = async { mgr.awaitApproval(req("id1", "s1")) }
        yield()
        assertFalse(mgr.tryResolve("unknown", "s1", PermissionChoice.ONCE))
        assertFalse(mgr.tryResolve("id1", "s2", PermissionChoice.ONCE))
        assertTrue(result.isActive)
        assertTrue(mgr.tryResolve("id1", "s1", PermissionChoice.REJECT))
        assertFalse(mgr.tryResolve("id1", "s1", PermissionChoice.ONCE))
        assertEquals(PermissionChoice.REJECT, result.await())
        assertFalse(mgr.tryResolve("id1", "s1", PermissionChoice.ONCE))
    }

    @Test
    fun tryResolveCannotCompleteCancelledRequestBeforeCleanup() = runBlocking {
        val mgr = ToolPermissionManager()
        val result = async { mgr.awaitApproval(req("id1", "s1")) }
        yield()
        result.cancel()
        assertFalse(mgr.tryResolve("id1", "s1", PermissionChoice.ONCE))
        result.join()
        assertNull(mgr.pendingRequest.value)
    }

    @Test
    fun tryResolveBatchChecksSessionAndRejectsRepeatedCompletion() = runBlocking {
        val mgr = ToolPermissionManager()
        val batch = PendingPermissionBatch("b1", "s1", listOf(req("id1", "s1")))
        val result = async { mgr.awaitBatchApproval(batch) }
        yield()
        assertFalse(mgr.tryResolveBatch("unknown", "s1", PermissionChoice.ONCE))
        assertFalse(mgr.tryResolveBatch("b1", "s2", PermissionChoice.ONCE))
        assertTrue(result.isActive)
        assertTrue(mgr.tryResolveBatch("b1", "s1", PermissionChoice.REJECT))
        assertFalse(mgr.tryResolveBatch("b1", "s1", PermissionChoice.ONCE))
        assertEquals(mapOf("id1" to PermissionChoice.REJECT), result.await())
        assertFalse(mgr.tryResolveBatch("b1", "s1", PermissionChoice.ONCE))
    }

    @Test
    fun tryResolveBatchCannotCompleteCancelledBatchBeforeCleanup() = runBlocking {
        val mgr = ToolPermissionManager()
        val batch = PendingPermissionBatch("b1", "s1", listOf(req("id1", "s1")))
        val result = async { mgr.awaitBatchApproval(batch) }
        yield()
        result.cancel()
        assertFalse(mgr.tryResolveBatch("b1", "s1", PermissionChoice.ONCE))
        result.join()
        assertNull(mgr.pendingBatch.value)
    }

    @Test
    fun batchApprovalAcceptsPerItemDecisions() = runBlocking {
        val mgr = ToolPermissionManager()
        val batch = PendingPermissionBatch("b1", "s1", listOf(req("id1", "s1"), req("id2", "s1")))
        val d = async { mgr.awaitBatchApproval(batch) }
        withTimeout(1000) { while (mgr.pendingBatch.value == null) yield() }

        mgr.resolveBatch("b1", mapOf("id1" to PermissionChoice.ONCE, "id2" to PermissionChoice.REJECT))
        assertEquals(mapOf("id1" to PermissionChoice.ONCE, "id2" to PermissionChoice.REJECT), d.await())
    }
}
