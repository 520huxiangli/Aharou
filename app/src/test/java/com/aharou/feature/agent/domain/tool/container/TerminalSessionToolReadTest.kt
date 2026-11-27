package com.aharou.feature.agent.domain.tool.container

import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.tool.ToolResult
import com.aharou.feature.terminal.domain.TabInfo
import com.aharou.feature.terminal.domain.TerminalSessionProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `terminal` 工具 read 的增量语义：默认只回新增、full 回看全量、filter 只作用于本次新增。
 * 同时守住筛选顺序（先筛正文再拼 mismatchNote）与「非法正则不先取输出」这两条既有行为。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TerminalSessionToolReadTest {

    private val tabInfo = TabInfo(
        id = "term-1",
        title = "test",
        isBackground = true,
        running = true,
        command = null
    )

    private lateinit var provider: TerminalSessionProvider
    private lateinit var tool: TerminalSessionTool

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkObject(FileLogger)
        every { FileLogger.v(any(), any()) } returns Unit
        every { FileLogger.i(any(), any()) } returns Unit
        every { FileLogger.e(any(), any(), any()) } returns Unit
        provider = mockk()
        every { provider.listTabs() } returns listOf(tabInfo)
        tool = TerminalSessionTool(provider, TerminalOutputCursor())
    }

    @After
    fun tearDown() {
        unmockkObject(FileLogger)
        Dispatchers.resetMain()
    }

    private suspend fun read(vararg extra: Pair<String, JsonElement>): ToolResult {
        val args = mapOf<String, JsonElement>(
            "action" to JsonPrimitive("read"),
            "tab_id" to JsonPrimitive("term-1")
        ) + extra.toMap()
        return tool.execute(args)
    }

    private fun ToolResult.text(): String {
        assertTrue("期望成功，实际 $this", this is ToolResult.Success)
        return (this as ToolResult.Success).data.jsonPrimitive.content
    }

    @Test
    fun firstRead_returnsWholeOutput() = runTest {
        every { provider.getTabOutput("term-1") } returns "line1\nline2"
        assertEquals("line1\nline2", read().text())
    }

    @Test
    fun secondRead_returnsOnlyNewOutput() = runTest {
        every { provider.getTabOutput("term-1") } returns "line1\nline2"
        read()
        every { provider.getTabOutput("term-1") } returns "line1\nline2\nline3"
        // 游标停在上次返回的原文末尾，新增部分带上了分隔两个段落的换行。
        assertEquals("\nline3", read().text())
    }

    @Test
    fun noNewOutput_returnsHint() = runTest {
        every { provider.getTabOutput("term-1") } returns "line1"
        read()
        assertTrue(read().text().contains("没有新的输出"))
    }

    @Test
    fun readWithFull_returnsWholeHistoryAndKeepsCursor() = runTest {
        every { provider.getTabOutput("term-1") } returns "line1"
        read()
        every { provider.getTabOutput("term-1") } returns "line1\nline2"
        read()
        // full 回看整段历史。
        assertEquals("line1\nline2", read("full" to JsonPrimitive("true")).text())
        // full 已经把游标推到末尾，紧接着的普通 read 不应再把同一段重报一遍。
        assertTrue(read().text().contains("没有新的输出"))
    }

    @Test
    fun filter_appliesOnlyToNewOutput() = runTest {
        every { provider.getTabOutput("term-1") } returns "INFO start"
        read()
        every { provider.getTabOutput("term-1") } returns "INFO start\nERROR boom"
        assertEquals("ERROR boom", read("filter" to JsonPrimitive("ERROR")).text())
    }

    @Test
    fun filter_notMatchingNewOutput_reportsNewLineCount() = runTest {
        every { provider.getTabOutput("term-1") } returns "INFO start"
        read()
        every { provider.getTabOutput("term-1") } returns "INFO start\nINFO later"
        val text = read("filter" to JsonPrimitive("ERROR")).text()
        // 新增部分是前一段末尾往后的内容，以换行开头，行戳按增量原文算。
        assertTrue(text, text.contains("本次新增共 2 行"))
    }

    @Test
    fun invalidRegex_failsBeforeReadingOutput() = runTest {
        val result = read("filter" to JsonPrimitive("("))
        assertTrue(result is ToolResult.Error)
        assertEquals("INVALID_REGEX", (result as ToolResult.Error).code)
        verify(exactly = 0) { provider.getTabOutput(any()) }
    }

    @Test
    fun close_clearsCursorSoReusedTabReportsFullOutput() = runTest {
        every { provider.getTabOutput("term-1") } returns "line1"
        every { provider.closeTab("term-1") } returns true
        assertEquals("line1", read().text())
        tool.execute(mapOf("action" to JsonPrimitive("close"), "tab_id" to JsonPrimitive("term-1")))
        // 标签 id 会被后续 start 复用：清过游标后应当重新报全量，而不是把旧内容当成已读。
        every { provider.getTabOutput("term-1") } returns "line1\nline2"
        assertEquals("line1\nline2", read().text())
    }
}
