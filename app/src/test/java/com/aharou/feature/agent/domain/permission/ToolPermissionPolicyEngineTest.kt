package com.aharou.feature.agent.domain.permission

import com.aharou.feature.agent.domain.model.AgentMode
import com.aharou.feature.agent.domain.tool.AgentTool
import com.aharou.feature.agent.domain.tool.ToolCapability
import com.aharou.feature.settings.data.repository.ToolSafetySettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具授权策略引擎的分支覆盖。规则加载经 mock 注入，聚焦 [ToolPermissionPolicyEngine.evaluate]
 * 的裁决逻辑：PLAN/AUTO 模式特判、DENY 优先、白名单/记忆规则、rm 高危删除防护与不可记忆降级。
 * 底层 [ShellCommandParser] / [BuiltInSafeCommands] 的解析细节由各自独立测试覆盖，不在此重复。
 */
class ToolPermissionPolicyEngineTest {

    /** 多数用例不关心工作区作用域，统一用它；作用域隔离另有专门用例。 */
    private companion object {
        const val TEST_WORKSPACE = "/workspace/test"
    }

    private fun engine(vararg rules: PermissionRule, safetyDisabled: Boolean = false): ToolPermissionPolicyEngine {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveFor(any()) } returns rules.toList()
        val safety = mockk<ToolSafetySettingsRepository>(relaxed = true)
        coEvery { safety.isSafetyInterceptionDisabled() } returns safetyDisabled
        return ToolPermissionPolicyEngine(repo, safety)
    }

    private fun tool(vararg caps: ToolCapability): AgentTool {
        val t = mockk<AgentTool>(relaxed = true)
        every { t.capabilities } returns caps.toSet()
        every { t.effectiveCapabilities(any()) } returns caps.toSet()
        return t
    }

    private fun bash(command: String) = mapOf("command" to JsonPrimitive(command))

    private fun terminal(action: String) = mapOf("action" to JsonPrimitive(action))

    // ── PLAN 模式：写/执行类工具一律拒绝 ─────────────────────────────

    @Test
    fun planMode_deniesWorkspaceWriteTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.PLAN, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertNotNull(r.denyReason)
    }

    @Test
    fun planMode_deniesBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.PLAN, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_deniesTerminalStart() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "terminal", terminal("start"), AgentMode.PLAN, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun planMode_allowsReadOnlyExplorerTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "list", emptyMap(), AgentMode.PLAN, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    @Test
    fun planMode_allowsTerminalRead() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_WORKSPACE), "terminal", terminal("read"), AgentMode.PLAN, TEST_WORKSPACE)
        // 无任何规则时按整工具 ASK（可记忆），而非 DENY
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── AUTO 模式：放行但保留灾难性 rm 防护 ──────────────────────────

    @Test
    fun autoMode_allowsAnyTool() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_allowsBash() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun autoMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.startsWith("安全防护：禁止执行高危删除操作（根目录删除）") == true)
    }

    @Test
    fun autoMode_stillBlocksWorkspaceRm() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf ~/workspace/*"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun autoMode_disabledSafety_allowsCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun disabledSafety_buildMode_stillBlocksCatastrophicRm() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 只读 Agent 配置自动放行 ─────────────────────────────────────

    @Test
    fun readAgentConfig_capabilityAutoAllow() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.READ_AGENT_CONFIG), "customTool", emptyMap(), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── task 只读动作放行，whole DENY 仍生效 ─────────────────────────

    @Test
    fun taskReadAction_allowsWithoutRules() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun taskReadAction_deniedWhenWholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("task", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXTERNAL_TOOL), "task", terminal("read"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── DENY 优先于内置白名单 ───────────────────────────────────────

    @Test
    fun denyRule_overridesSafeWhitelist() = runTest {
        val e = engine(
            PermissionRule("Bash", "ls", PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 不可静态判定：必弹窗、不可记忆 ───────────────────────────────

    @Test
    fun unanalyzableCommand_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo $(whoami)"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    // ── 内置安全白名单自动放行 ───────────────────────────────────────

    @Test
    fun safeCommand_autoAllowed() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun mixOfSafeAndUnsafe_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls -la && rm x"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 已记忆 ALLOW 规则 ───────────────────────────────────────────

    @Test
    fun rememberedPrefix_allows() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git pull origin main"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun rememberedPrefix_doesNotMatchOtherSubcommand() = runTest {
        val e = engine(
            PermissionRule("Bash", "git pull", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("git clone https://x"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── rm 精细校验：无目标 / 递归 / 通配的规则不得放行 ───────────────

    @Test
    fun bareRmRule_doesNotAllowRmWithTarget() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm file.txt"), AgentMode.BUILD, TEST_WORKSPACE)
        // 规则无目标路径 → 不匹配；且 rm 单文件可记忆为完整前缀
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertEquals(listOf("rm file.txt"), r.rememberablePatterns)
    }

    @Test
    fun nonRecursiveRmRule_doesNotAllowRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm file.txt", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /some/dir"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty()) // 递归删除不可记忆
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun recursiveRmRule_allowsRecursiveRm() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf /tmp/build", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /tmp/build"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    // ── 灾难性 rm 防护在 BUILD 模式同样生效 ─────────────────────────

    @Test
    fun catastrophicRm_deniedEvenWithAllowRule() = runTest {
        val e = engine(
            PermissionRule("Bash", "rm -rf", PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun systemDirRm_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /etc"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 提权：非 AUTO 模式下携带 elevate 将硬拒绝降级为一次性授权 ──────

    @Test
    fun catastrophicRm_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.BUILD,
            TEST_WORKSPACE
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.askTitle)
    }

    @Test
    fun catastrophicRm_denyReasonHintsElevate() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf /"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
        assertTrue(r.denyReason?.contains("elevate") == true)
    }

    @Test
    fun catastrophicRm_autoMode_elevate_asksUser() = runTest {
        val e = engine()
        val r = e.evaluate(
            tool(ToolCapability.EXECUTE_COMMANDS),
            "Bash",
            mapOf("command" to JsonPrimitive("rm -rf /"), "elevate" to JsonPrimitive(true)),
            AgentMode.AUTO,
            TEST_WORKSPACE
        )
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
    }

    // ── 路径归一化：多种等价写法均需拦截，工作区子目录放行 ─────────────

    private suspend fun denied(command: String): Boolean =
        engine().evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash(command), AgentMode.BUILD, TEST_WORKSPACE).verdict ==
            ToolPermissionPolicyEngine.Verdict.DENY

    @Test
    fun abnormalPathSpellings_denied() = runTest {
        assertTrue(denied("rm -rf //etc"))
        assertTrue(denied("rm -rf /./etc"))
        assertTrue(denied("rm -rf /etc/../etc"))
        assertTrue(denied("rm -rf /tmp/../etc"))
        assertTrue(denied("rm -rf /foo/../"))
        assertTrue(denied("rm -rf \$HOME"))
        assertTrue(denied("rm -rf \${HOME}/foo"))
        assertTrue(denied("rm -rf /et*"))
        assertTrue(denied("rm -rf /usr*"))
        assertTrue(denied("rm -rf /srv"))
        assertTrue(denied("rm -rf /mnt"))
    }

    @Test
    fun workspaceSubdir_allowed() = runTest {
        assertTrue(!denied("rm -rf ~/workspace/build"))
        assertTrue(!denied("rm -rf ~/workspace/app/src"))
        assertTrue(!denied("rm -rf /tmp/build"))
    }

    @Test
    fun workspaceRoot_stillDenied() = runTest {
        assertTrue(denied("rm -rf ~/workspace"))
        assertTrue(denied("rm -rf ~/workspace/*"))
        assertTrue(denied("rm -rf ~/workspace/.."))
    }

    // ── AUTO：无法静态判定的疑似破坏性命令保守拦截 ─────────────────

    @Test
    fun autoMode_unanalyzableDestructive_denied() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("rm -rf \$(echo /etc)"), AgentMode.AUTO, TEST_WORKSPACE).verdict
        )
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.DENY,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO, TEST_WORKSPACE).verdict
        )
    }

    @Test
    fun autoMode_unanalyzableBenign_allowed() = runTest {
        val e = engine()
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("echo \$(date)"), AgentMode.AUTO, TEST_WORKSPACE).verdict
        )
    }

    @Test
    fun autoMode_safetyDisabled_unanalyzable_allowed() = runTest {
        val e = engine(safetyDisabled = true)
        assertEquals(
            ToolPermissionPolicyEngine.Verdict.ALLOW,
            e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("cat > /etc/passwd"), AgentMode.AUTO, TEST_WORKSPACE).verdict
        )
    }

    // ── Shizuku 高危工具：一律弹窗、不可记忆，AUTO 不豁免 ─────────────

    private fun shizuku(command: String) = mapOf("command" to JsonPrimitive(command))

    @Test
    fun shizuku_buildMode_asksWithoutRememberable() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_safeCommandStillAsks() = runTest {
        val e = engine()
        // 内置安全白名单（ls）对 Shizuku 不适用，仍需弹窗
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("ls -la /sdcard"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_rememberedAllowRuleIgnored() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.ALLOW))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_denyRuleStillDenies() = runTest {
        val e = engine(PermissionRule("Shizuku", "pm", PermissionDecision.DENY))
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    @Test
    fun shizuku_autoMode_asks() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun shizuku_autoMode_safetyDisabled_allows() = runTest {
        val e = engine(safetyDisabled = true)
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.AUTO, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun shizuku_buildMode_safetyDisabled_stillAsksWithoutRememberable() = runTest {
        val e = engine(safetyDisabled = true)
        // 开关仅解除 AUTO 豁免，不可记忆在 BUILD 下始终生效
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
    }

    @Test
    fun shizuku_planMode_denied() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Shizuku", shizuku("pm list packages"), AgentMode.PLAN, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── 非 shell 工具 ───────────────────────────────────────────────

    @Test
    fun genericTool_wholeRuleAllows() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.ALLOW)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, r.verdict)
    }

    @Test
    fun genericTool_unrememberableCapability_asksOnce() = runTest {
        val e = engine()
        val r = e.evaluate(tool(ToolCapability.MODIFY_AGENT_CONFIG), "editFile", emptyMap(), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, r.verdict)
        assertTrue(r.rememberablePatterns.isEmpty())
        assertNotNull(r.rememberDisabledReason)
    }

    @Test
    fun genericTool_wholeRuleDenies() = runTest {
        val e = engine(
            PermissionRule("writeFile", PermissionRule.WHOLE_TOOL, PermissionDecision.DENY)
        )
        val r = e.evaluate(tool(ToolCapability.WRITE_WORKSPACE), "writeFile", emptyMap(), AgentMode.BUILD, TEST_WORKSPACE)
        assertEquals(ToolPermissionPolicyEngine.Verdict.DENY, r.verdict)
    }

    // ── remember：去重后逐条落库 ────────────────────────────────────

    @Test
    fun remember_dedupesAndAddsEach() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.add(any(), any(), any()) } just runs
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true))

        e.remember("Bash", listOf("git pull", "git pull", "ls"), PermissionScope.PROJECT, TEST_WORKSPACE)

        coVerify(exactly = 2) { repo.add(PermissionScope.PROJECT, any(), any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "git pull", PermissionDecision.ALLOW), any()) }
        coVerify { repo.add(PermissionScope.PROJECT, PermissionRule("Bash", "ls", PermissionDecision.ALLOW), any()) }
    }

    @Test
    fun workspaceScope_readsRulesOfItsOwnWorkspace() = runTest {
        val repo = mockk<PermissionRulesRepository>(relaxed = true)
        coEvery { repo.loadEffectiveFor("/ws/a") } returns listOf(PermissionRule("Bash", "ls", PermissionDecision.ALLOW))
        coEvery { repo.loadEffectiveFor("/ws/b") } returns emptyList()
        val e = ToolPermissionPolicyEngine(repo, mockk(relaxed = true))

        val a = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls"), AgentMode.BUILD, "/ws/a")
        val b = e.evaluate(tool(ToolCapability.EXECUTE_COMMANDS), "Bash", bash("ls"), AgentMode.BUILD, "/ws/b")

        assertEquals(ToolPermissionPolicyEngine.Verdict.ALLOW, a.verdict)
        assertEquals(ToolPermissionPolicyEngine.Verdict.ASK, b.verdict)
    }
}