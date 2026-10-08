package com.aharou.feature.terminal.domain

import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DelegatingTerminalSessionProvider：按 ExecutionModeHolder 的当前模式，在**每次调用**时把请求
 * 转发到本地 / 远程实现。
 *
 * 本地与远程管理器都是 Android/Hilt 重的具体类，这里以 mockk 替身注入，不触碰 Android 运行时；
 * ExecutionModeHolder 仅含一个 StateFlow，直接用真实实例，向真实切换语义负责。
 *
 * runningBackgroundTabs 在构造时就被 combine 捕获两侧的 Flow 实例，故用 [newProvider] 在构造前
 * 按需给出两侧内容，而非构造后再改桩。
 */
class DelegatingTerminalSessionProviderTest {

    private val modeHolder = ExecutionModeHolder()
    private val local = mockk<TerminalSessionManager>(relaxed = true)
    private val remote = mockk<RemoteTerminalSessionManager>(relaxed = true)

    private fun tabInfo(id: String) = TabInfo(
        id = id,
        title = id,
        isBackground = true,
        running = true,
        command = "npm run dev"
    )

    private fun newProvider(
        localTabs: List<TabInfo> = emptyList(),
        remoteTabs: List<TabInfo> = emptyList()
    ): DelegatingTerminalSessionProvider {
        every { local.runningBackgroundTabs } returns flowOf(localTabs)
        every { remote.runningBackgroundTabs } returns flowOf(remoteTabs)
        return DelegatingTerminalSessionProvider(modeHolder, local, remote)
    }

    @Test
    fun default_mode_routes_reads_to_local() {
        val provider = newProvider()
        every { local.listTabs() } returns listOf(tabInfo("term-1"))

        assertEquals(listOf("term-1"), provider.listTabs().map { it.id })
        verify(exactly = 0) { remote.listTabs() }
    }

    @Test
    fun default_mode_routes_input_and_output_to_local() {
        val provider = newProvider()
        every { local.sendInput(any(), any(), any()) } returns true
        every { local.getTabOutput("term-1") } returns "hello"

        assertTrue(provider.sendInput("term-1", "ls", true))
        assertEquals("hello", provider.getTabOutput("term-1"))
        verify(exactly = 0) { remote.sendInput(any(), any(), any()) }
        verify(exactly = 0) { remote.getTabOutput(any()) }
    }

    @Test
    fun remote_ssh_mode_routes_to_remote() {
        val provider = newProvider()
        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        every { remote.getTabOutput("term-9") } returns "remote-out"

        assertEquals("remote-out", provider.getTabOutput("term-9"))
        verify(exactly = 0) { local.getTabOutput(any()) }
    }

    @Test
    fun mode_is_read_on_every_call_not_cached_at_construction() {
        val provider = newProvider()
        every { local.closeTab(any()) } returns true
        every { remote.closeTab(any()) } returns false

        assertTrue(provider.closeTab("a"))

        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        assertFalse(provider.closeTab("b"))

        modeHolder.setMode(ExecutionMode.LOCAL_PROOT)
        assertTrue(provider.closeTab("c"))

        verify(exactly = 2) { local.closeTab(any()) }
        verify(exactly = 1) { remote.closeTab(any()) }
    }

    @Test
    fun write_bytes_forwards_vararg_to_current_delegate() {
        val provider = newProvider()

        provider.writeBytesToTab("term-1", 3, 4)

        verify { local.writeBytesToTab("term-1", 3, 4) }
    }

    @Test
    fun start_background_command_returns_delegate_tab_id() = runTest {
        val provider = newProvider()
        coEvery {
            local.startBackgroundCommand("npm run dev", "dev", true, "sess-1", "/ws")
        } returns "term-5"

        assertEquals(
            "term-5",
            provider.startBackgroundCommand("npm run dev", "dev", true, "sess-1", "/ws")
        )
        coVerify(exactly = 0) {
            remote.startBackgroundCommand(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun tab_finished_events_follow_current_mode() {
        val provider = newProvider()
        val localEvents = MutableSharedFlow<TabFinishedEvent>()
        val remoteEvents = MutableSharedFlow<TabFinishedEvent>()
        every { local.tabFinishedEvents } returns localEvents
        every { remote.tabFinishedEvents } returns remoteEvents

        assertSame(localEvents, provider.tabFinishedEvents)

        modeHolder.setMode(ExecutionMode.REMOTE_SSH)
        assertSame(remoteEvents, provider.tabFinishedEvents)
    }

    @Test
    fun running_background_tabs_merges_local_and_remote_side() = runTest {
        val provider = newProvider(
            localTabs = listOf(tabInfo("term-1")),
            remoteTabs = listOf(tabInfo("term-2"))
        )

        val merged = provider.runningBackgroundTabs.first()

        assertEquals(listOf("term-1", "term-2"), merged.map { it.id })
    }
}
