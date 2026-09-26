package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.permission.PermissionChoice
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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
}
