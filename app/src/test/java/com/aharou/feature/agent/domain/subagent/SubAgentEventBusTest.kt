package com.aharou.feature.agent.domain.subagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 活跃子代理集合的维护语义：并发槽位靠它计数，只要有一条路径忘了交回槽位，
 * 用户就会在没有任何子代理运行时被告知「已达上限」，直到进程重启。
 */
class SubAgentEventBusTest {

    private fun spawn(bus: SubAgentEventBus, id: String) = bus.emit(
        SubAgentEvent(subSessionId = id, parentSessionId = "parent", type = SubAgentEventType.SPAWNED)
    )

    @Test
    fun spawn_marksActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertEquals(setOf("sub-1"), bus.activeSubSessionIds.value)
        assertEquals(1, bus.activeCount)
        assertFalse(bus.isFull)
    }

    @Test
    fun terminalEvents_freeSlot() {
        listOf(
            SubAgentEventType.COMPLETED,
            SubAgentEventType.FAILED,
            SubAgentEventType.STOPPED
        ).forEach { type ->
            val bus = SubAgentEventBus()
            spawn(bus, "sub-1")
            bus.emit(SubAgentEvent(subSessionId = "sub-1", parentSessionId = "parent", type = type))

            assertEquals("$type 应交回槽位", emptySet<String>(), bus.activeSubSessionIds.value)
        }
    }

    @Test
    fun isFull_atMaxRunning() {
        val bus = SubAgentEventBus()
        repeat(SubAgentEventBus.MAX_RUNNING) { spawn(bus, "sub-$it") }

        assertTrue(bus.isFull)

        bus.emit(SubAgentEvent(subSessionId = "sub-0", parentSessionId = "parent", type = SubAgentEventType.COMPLETED))
        assertFalse("腾出一个后应能再派发", bus.isFull)
    }

    /** 用户手动停止走 release：必须真的交回槽位，否则上限被永久占用。 */
    @Test
    fun release_freesSlotAndReportsWhetherItWasActive() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")

        assertTrue(bus.release("sub-1"))
        assertEquals(emptySet<String>(), bus.activeSubSessionIds.value)
    }

    /**
     * 幂等且如实回报：TaskTool 的 stop 已经 emit 过 STOPPED 时，ViewModel 随后的 release
     * 必须返回 false，否则会给父代理重复投递一条「已被终止」通知。
     */
    @Test
    fun release_isIdempotentForUnknownId() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        bus.release("sub-1")

        assertFalse(bus.release("sub-1"))
        assertFalse(bus.release("never-existed"))
    }

    @Test
    fun release_doesNotTouchOtherSubAgents() {
        val bus = SubAgentEventBus()
        spawn(bus, "sub-1")
        spawn(bus, "sub-2")

        bus.release("sub-1")

        assertEquals(setOf("sub-2"), bus.activeSubSessionIds.value)
    }
}
