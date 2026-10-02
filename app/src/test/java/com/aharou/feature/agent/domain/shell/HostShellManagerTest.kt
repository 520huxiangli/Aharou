package com.aharou.feature.agent.domain.shell

import com.aharou.feature.agent.domain.shizuku.ShizukuState
import org.junit.Assert.assertEquals
import org.junit.Test

/** [HostShellManager.resolve] 的通道挑选：root 优先、Shizuku 兜底、都没有才是不可用。 */
class HostShellManagerTest {

    @Test
    fun root_takes_priority_when_available() {
        assertEquals(HostShellMode.ROOT, HostShellManager.resolve(true, ShizukuState.READY))
        assertEquals(HostShellMode.ROOT, HostShellManager.resolve(true, ShizukuState.NOT_INSTALLED))
        assertEquals(HostShellMode.ROOT, HostShellManager.resolve(true, ShizukuState.PERMISSION_DENIED))
    }

    @Test
    fun falls_back_to_shizuku_when_root_missing() {
        assertEquals(HostShellMode.SHIZUKU, HostShellManager.resolve(false, ShizukuState.READY))
        assertEquals(HostShellMode.SHIZUKU, HostShellManager.resolve(null, ShizukuState.READY))
    }

    @Test
    fun unavailable_when_neither_channel_works() {
        assertEquals(HostShellMode.UNAVAILABLE, HostShellManager.resolve(false, ShizukuState.NOT_INSTALLED))
        assertEquals(HostShellMode.UNAVAILABLE, HostShellManager.resolve(null, ShizukuState.NOT_RUNNING))
        assertEquals(HostShellMode.UNAVAILABLE, HostShellManager.resolve(false, ShizukuState.PERMISSION_DENIED))
    }
}
