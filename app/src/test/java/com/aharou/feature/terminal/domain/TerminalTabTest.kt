package com.aharou.feature.terminal.domain

import com.termux.terminal.TerminalSession
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TerminalTab 及其配套纯逻辑：RunState、tail 行截断、tab → 摘要映射、后台标签过滤与可变状态。
 *
 * TerminalTab 依赖 TerminalSession/TerminalView：前者以 mockk 替身占位（不启动 PTY），
 * 后者保持 null（android.view 不触碰），故全部用例可在纯 JVM 下运行。
 */
class TerminalTabTest {

    private fun tab(
        id: String = "term-1",
        title: String = id,
        isBackground: Boolean = false,
        command: String? = null,
        startedAt: Long = 0L,
        sourceSessionId: String? = null,
        workspacePath: String? = null,
        runState: RunState = RunState.Running
    ) = TerminalTab(
        id = id,
        title = title,
        session = mockk<TerminalSession>(relaxed = true),
        isBackground = isBackground,
        command = command,
        startedAt = startedAt,
        sourceSessionId = sourceSessionId,
        workspacePath = workspacePath,
        runState = runState
    )

    private fun tabInfo(id: String) = TabInfo(
        id = id,
        title = id,
        isBackground = true,
        running = true,
        command = "npm run dev"
    )

    // ---------- takeTailLines ----------

    @Test
    fun take_tail_lines_returns_null_for_null_input() {
        assertNull((null as String?).takeTailLines(5))
    }

    @Test
    fun take_tail_lines_keeps_whole_text_when_shorter_than_limit() {
        assertEquals("a\nb", "a\nb".takeTailLines(5))
    }

    @Test
    fun take_tail_lines_keeps_only_last_n_lines() {
        assertEquals("c\nd", "a\nb\nc\nd".takeTailLines(2))
    }

    @Test
    fun take_tail_lines_with_zero_returns_empty_string() {
        assertEquals("", "a\nb".takeTailLines(0))
    }

    @Test
    fun take_tail_lines_treats_trailing_newline_as_a_line() {
        // lines() 保留尾部空行，故取最后 1 行拿到的是它；真实调用点固定 TAIL_LINES=50，尾部空行无影响
        assertEquals("", "a\nb\n".takeTailLines(1))
        assertEquals("b\n", "a\nb\n".takeTailLines(2))
    }

    @Test
    fun take_tail_lines_splits_crlf_and_lone_cr() {
        assertEquals("a\nb", "a\r\nb".takeTailLines(5))
        assertEquals("a\nb", "a\rb".takeTailLines(5))
    }

    @Test
    fun take_tail_lines_empty_string_stays_empty() {
        assertEquals("", "".takeTailLines(3))
    }

    @Test
    fun take_tail_lines_single_line_unaffected() {
        assertEquals("only", "only".takeTailLines(10))
    }

    @Test
    fun take_tail_lines_throws_for_negative_count() {
        assertThrows(IllegalArgumentException::class.java) { "a\nb".takeTailLines(-1) }
    }

    @Test
    fun tail_lines_constant_is_fifty() {
        assertEquals(50, TAIL_LINES)
    }

    // ---------- RunState ----------

    @Test
    fun running_state_is_a_singleton_object() {
        assertSame(RunState.Running, RunState.Running)
    }

    @Test
    fun finished_state_carries_exit_code_and_is_value_equal() {
        val a = RunState.Finished(3)
        assertEquals(RunState.Finished(3), a)
        assertEquals(RunState.Finished(3).hashCode(), a.hashCode())
        assertEquals(3, a.exitCode)
        assertEquals(RunState.Finished(0), a.copy(exitCode = 0))
    }

    @Test
    fun finished_is_not_equal_to_running() {
        assertFalse(RunState.Finished(0) == RunState.Running)
    }

    // ---------- TabInfo / TabFinishedEvent ----------

    @Test
    fun tab_info_optional_fields_default() {
        val info = TabInfo(id = "term-1", title = "t", isBackground = false, running = true, command = null)
        assertNull(info.workspacePath)
        assertNull(info.command)
        assertEquals(0L, info.startedAt)
    }

    @Test
    fun tab_info_is_value_equal() {
        assertEquals(tabInfo("term-1"), tabInfo("term-1"))
        assertFalse(tabInfo("term-1") == tabInfo("term-2"))
    }

    @Test
    fun tab_finished_event_defaults() {
        val event = TabFinishedEvent("term-1", "t", null, 0, null)
        assertEquals(0L, event.startedAt)
        assertNull(event.tailOutput)
        assertNull(event.sourceSessionId)
        assertNull(event.command)
    }

    @Test
    fun tab_finished_event_carries_routing_and_tail() {
        val event = TabFinishedEvent(
            tabId = "term-1",
            title = "build",
            command = "make",
            exitCode = 2,
            sourceSessionId = "sess-9",
            startedAt = 1234L,
            tailOutput = "tail"
        )
        assertEquals("sess-9", event.sourceSessionId)
        assertEquals(1234L, event.startedAt)
        assertEquals("tail", event.tailOutput)
        assertEquals(2, event.exitCode)
    }

    // ---------- TerminalTab 字段与可变状态 ----------

    @Test
    fun tab_keeps_background_metadata() {
        val t = tab(
            id = "term-1",
            isBackground = true,
            command = "sleep 1",
            sourceSessionId = "sess-2",
            workspacePath = "/ws"
        )
        assertTrue(t.isBackground)
        assertEquals("sleep 1", t.command)
        assertEquals("sess-2", t.sourceSessionId)
        assertEquals("/ws", t.workspacePath)
    }

    @Test
    fun view_and_client_default_to_null() {
        val t = tab()
        assertNull(t.view)
        assertNull(t.client)
    }

    @Test
    fun title_and_run_state_are_mutable() {
        val t = tab(title = "term-1")
        t.title = "renamed"
        assertEquals("renamed", t.title)

        t.runState = RunState.Finished(7)
        assertEquals(RunState.Finished(7), t.runState)
        assertFalse(t.toTabInfo().running)
    }

    @Test
    fun finished_notified_defaults_to_false_and_is_settable() {
        val t = tab()
        assertFalse(t.finishedNotified)
        t.finishedNotified = true
        assertTrue(t.finishedNotified)
    }

    // ---------- toTabInfo / runningBackgroundTabInfos ----------

    @Test
    fun to_tab_info_marks_running_tab_and_copies_metadata() {
        val info = tab(
            id = "term-1",
            title = "dev",
            isBackground = true,
            command = "npm run dev",
            startedAt = 100L,
            workspacePath = "/ws",
            runState = RunState.Running
        ).toTabInfo()

        assertEquals("term-1", info.id)
        assertEquals("dev", info.title)
        assertTrue(info.isBackground)
        assertTrue(info.running)
        assertEquals("npm run dev", info.command)
        assertEquals("/ws", info.workspacePath)
        assertEquals(100L, info.startedAt)
    }

    @Test
    fun to_tab_info_marks_finished_tab_as_not_running() {
        assertFalse(tab(runState = RunState.Finished(0)).toTabInfo().running)
    }

    @Test
    fun running_background_tab_infos_keeps_only_running_background_tabs() {
        val tabs = listOf(
            tab(id = "term-1", isBackground = true, runState = RunState.Running),
            tab(id = "term-2", isBackground = false, runState = RunState.Running),
            tab(id = "term-3", isBackground = true, runState = RunState.Finished(0)),
            tab(id = "term-4", isBackground = true, runState = RunState.Running)
        )
        assertEquals(listOf("term-1", "term-4"), tabs.runningBackgroundTabInfos().map { it.id })
    }

    @Test
    fun running_background_tab_infos_is_empty_without_running_background_tabs() {
        val tabs = listOf(
            tab(isBackground = false, runState = RunState.Running),
            tab(isBackground = true, runState = RunState.Finished(1))
        )
        assertTrue(tabs.runningBackgroundTabInfos().isEmpty())
    }
}
