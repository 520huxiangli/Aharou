package com.aharou.feature.editor.domain.diagnostics

import com.aharou.feature.agent.domain.container.CommandEngine
import com.aharou.feature.agent.domain.container.CommandResult
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.PathHomeResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DiagnosticsRunner：外部命令（py_compile / luac -p）输出 → 诊断列表的整条链路。
 *
 * 解析函数是文件私有的，只能经公开入口 [DiagnosticsRunner.check] 触达：用一个假 [CommandEngine]
 * 喂进检查器输出，断言解析结果。覆盖 Python 两种报错形态、中文本地化与重复词去重、Lua 定位、
 * 噪声行不误报、退出码/容器未就绪/体积阈值/扩展名不支持的静默跳过，以及命令与工作区参数的构造。
 */
class DiagnosticsRunnerTest {

    private val engine = mockk<CommandEngine>(relaxed = true)
    private val workspaceRepository = mockk<WorkspaceRepository>(relaxed = true)
    private val homeResolver = PathHomeResolver(
        ExecutionModeHolder().apply { setMode(ExecutionMode.LOCAL_PROOT) },
        mockk<RemoteSshConnection>(relaxed = true),
    )
    private val runner = DiagnosticsRunner(engine, homeResolver, workspaceRepository)

    /** 记录每次命令调用的 (命令, 工作区路径, 超时)。 */
    private val invocations = mutableListOf<Triple<String, String?, Long>>()

    private fun engineReturning(result: CommandResult?) {
        every { workspaceRepository.currentPath() } returns "/root/workspace"
        coEvery { engine.runCommandSyncIfReady(any(), any(), any()) } answers {
            val args = invocation.args
            invocations += Triple(args[0] as String, args[1] as String?, args[2] as Long)
            result
        }
    }

    private fun lines(vararg raw: String): String = raw.joinToString("\n")

    // ---------- Python ----------

    @Test
    fun pythonMissingParen_outputsWholeLineDiagnosticAtReportedLine() = runTest {
        engineReturning(
            CommandResult(
                lines(
                    "  File \"a.py\", line 4",
                    "    x = (",
                    "        ^",
                    "SyntaxError: '(' was never closed",
                ),
                exitCode = 1,
            )
        )

        val diagnostics = runner.check("~/workspace/a.py", 10)

        assertEquals(1, diagnostics.size)
        val d = diagnostics.single()
        assertEquals(4, d.line)
        // wholeLine 语义：列与 endColumn 相等，由 Applier 铺满整行
        assertEquals(1, d.column)
        assertEquals(1, d.endColumn)
        assertEquals(DiagnosticSeverity.ERROR, d.severity)
        assertEquals("语法错误：「(」没有闭合", d.message)
    }

    @Test
    fun pythonSorryIndentationError_readsLineFromTrailingParens() = runTest {
        engineReturning(
            CommandResult(
                "Sorry: IndentationError: expected an indented block after function definition on line 1 (a2.py, line 2)",
                exitCode = 1,
            )
        )

        val d = runner.check("~/workspace/a2.py", 10).single()

        assertEquals(2, d.line)
        assertEquals("缩进错误：第 1 行的 函数定义 后面需要缩进的代码块", d.message)
    }

    @Test
    fun pythonDuplicateLocalizedWords_collapseIntoOne() = runTest {
        engineReturning(
            CommandResult(
                lines("  File \"a.py\", line 1", "SyntaxError: invalid syntax"),
                exitCode = 1,
            )
        )

        // 异常名与细节都译成「语法错误」，不应出现「语法错误：语法错误」
        assertEquals("语法错误", runner.check("~/workspace/a.py", 10).single().message)
    }

    @Test
    fun pythonErrorWithoutLocation_isNotReported() = runTest {
        engineReturning(
            CommandResult(
                "python3: can't open file '/x.py': [Errno 2] No such file or directory",
                exitCode = 1,
            )
        )

        assertTrue(runner.check("~/workspace/x.py", 10).isEmpty())
    }

    // ---------- Lua ----------

    @Test
    fun luaError_parsesLineAndLocalizesToken() = runTest {
        engineReturning(CommandResult("lua: x.lua:3: unexpected symbol near '<eof>'", exitCode = 1))

        val d = runner.check("~/workspace/x.lua", 10).single()

        assertEquals(3, d.line)
        assertEquals(DiagnosticSeverity.ERROR, d.severity)
        assertEquals("「文件结尾」 附近有无法识别的符号", d.message)
    }

    @Test
    fun luaCannotOpen_isSkipped() = runTest {
        engineReturning(
            CommandResult("lua: cannot open a.lua: No such file or directory", exitCode = 1)
        )

        assertTrue(runner.check("~/workspace/a.lua", 10).isEmpty())
    }

    // ---------- 跳过与边界 ----------

    @Test
    fun zeroExitCode_returnsEmptyButRunsChecker() = runTest {
        engineReturning(CommandResult("(no output)", exitCode = 0))

        assertTrue(runner.check("~/workspace/a.py", 10).isEmpty())
        coVerify(exactly = 1) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test
    fun commandNotFoundExit127_isSilentlySkipped() = runTest {
        engineReturning(CommandResult("sh: luac: not found", exitCode = 127))

        assertTrue(runner.check("~/workspace/a.lua", 10).isEmpty())
    }

    @Test
    fun engineNotReady_returnsEmpty() = runTest {
        engineReturning(null)

        assertTrue(runner.check("~/workspace/a.py", 10).isEmpty())
    }

    @Test
    fun oversizeFile_skipsWithoutRunningChecker() = runTest {
        engineReturning(CommandResult("", exitCode = 1))

        assertTrue(runner.check("~/workspace/a.py", 512 * 1024 + 1).isEmpty())
        coVerify(exactly = 0) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test
    fun sizeExactlyAtThreshold_stillRunsChecker() = runTest {
        engineReturning(CommandResult("", exitCode = 0))

        runner.check("~/workspace/a.py", 512 * 1024)

        coVerify(exactly = 1) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    @Test
    fun unsupportedExtension_skipsWithoutRunningChecker() = runTest {
        engineReturning(CommandResult("", exitCode = 1))

        assertTrue(runner.check("~/workspace/notes.txt", 10).isEmpty())
        coVerify(exactly = 0) { engine.runCommandSyncIfReady(any(), any(), any()) }
    }

    // ---------- 命令构造 ----------

    @Test
    fun pythonCommand_quotesExpandedPathAndPassesWorkspace() = runTest {
        engineReturning(CommandResult("", exitCode = 0))

        runner.check("~/workspace/a.py", 10)

        val (command, projectPath, _) = invocations.single()
        assertEquals("PYTHONPYCACHEPREFIX=/tmp/.aharou_diag python3 -m py_compile '/root/workspace/a.py'", command)
        // 不带工作区路径时检查器只会报 No such file —— 该参数必须原样传到引擎
        assertEquals("/root/workspace", projectPath)
    }

    @Test
    fun luaCommand_usesLuacPrintSyntaxCheck() = runTest {
        engineReturning(CommandResult("", exitCode = 0))

        runner.check("/root/workspace/a.lua", 10)

        assertEquals("luac -p '/root/workspace/a.lua'", invocations.single().first)
    }

    @Test
    fun pathWithSingleQuote_isShellQuoted() = runTest {
        engineReturning(CommandResult("", exitCode = 0))

        runner.check("~/a'b.py", 10)

        assertTrue(invocations.single().first.contains("'/root/a'\"'\"'b.py'"))
    }

    @Test
    fun moreThanMaxDiagnostics_isTruncatedToTwoHundred() = runTest {
        val output = buildString {
            repeat(250) { i ->
                append("File \"a.py\", line ").append(i + 1).append("\n")
                append("SyntaxError: invalid syntax\n")
            }
        }
        engineReturning(CommandResult(output, exitCode = 1))

        val diagnostics = runner.check("~/workspace/a.py", 10)

        assertEquals(200, diagnostics.size)
        assertEquals(1, diagnostics.first().line)
        assertEquals(200, diagnostics.last().line)
    }
}
