package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelProjectionTest {

    private fun storedResult(index: Int, pathAtTopLevel: Boolean = false): AgentMessage.ToolResultMessage =
        AgentMessage.ToolResultMessage(
            toolName = "bash",
            result = if (pathAtTopLevel) {
                """{"status":"success","data":{"output":"head ... tail"},""" +
                    """"output_truncated":true,"output_total_chars":43210,""" +
                    """"output_path":"/root/.aharou/tool-output/2026-$index.log"}"""
            } else {
                """{"status":"success","data":{"output":"head ... tail","output_truncated":true,""" +
                    """"output_total_chars":43210,"output_path":"/root/.aharou/tool-output/2026-$index.log"}}"""
            }
        )

    private fun plainResult(index: Int): AgentMessage.ToolResultMessage =
        AgentMessage.ToolResultMessage(
            toolName = "bash",
            result = """{"status":"success","data":{"output":"ok-$index"}}"""
        )

    private fun userMessage(index: Int): AgentMessage = AgentMessage.UserMessage(content = "u$index")

    /** 最近 8 条里的工具结果一律不动，整份列表原样返回。 */
    @Test
    fun fold_keepsListWhenToolResultsWithinKeepRecent() {
        val messages = (1..8).map { storedResult(it) }
        assertSame(messages, foldStoredToolResults(messages))
    }

    /** 第 9 条起开始折叠，且只折带 output_path 的那种。 */
    @Test
    fun fold_replacesOnlyOldStoredResults() {
        val messages = listOf(
            storedResult(1),
            plainResult(2),
            userMessage(3)
        ) + (4..10).map { plainResult(it) }

        val folded = foldStoredToolResults(messages)

        val first = folded[0] as AgentMessage.ToolResultMessage
        assertTrue("最早的已落盘结果应被折叠", first.result.contains("早期工具结果已折叠"))
        assertTrue(first.result.contains("/root/.aharou/tool-output/2026-1.log"))
        assertTrue(first.result.contains("43210"))

        val second = folded[1] as AgentMessage.ToolResultMessage
        assertFalse("没有 output_path 的结果不该被折", second.result.contains("已折叠"))
        assertSame(messages[1], second)
    }

    /** 折叠后不再含 output_path，重复调用是幂等的（每轮发送前都会调一次）。 */
    @Test
    fun fold_isIdempotent() {
        val messages = listOf(storedResult(1)) + (2..10).map { plainResult(it) }
        val once = foldStoredToolResults(messages)
        val twice = foldStoredToolResults(once)
        assertSame("第二次应直接返回同一份列表", once, twice)
        val reference = (once[0] as AgentMessage.ToolResultMessage).result
        assertEquals((twice[0] as AgentMessage.ToolResultMessage).result, reference)
    }

    /** output_path 落在顶层也要认（落盘标记的位置随工具结果结构变）。 */
    @Test
    fun fold_findsOutputPathAtTopLevel() {
        val messages = listOf(storedResult(1, pathAtTopLevel = true)) + (2..10).map { plainResult(it) }
        val folded = foldStoredToolResults(messages)
        assertTrue((folded[0] as AgentMessage.ToolResultMessage).result.contains("已折叠"))
    }

    /** 结果里没有 output_path 时不做任何改动，返回同一实例。 */
    @Test
    fun fold_leavesEverythingWhenNothingStored() {
        val messages = (1..12).map { plainResult(it) }
        assertSame(messages, foldStoredToolResults(messages))
    }

    /** 非工具消息不参与计数，也不会被改写。 */
    @Test
    fun fold_ignoresNonToolMessages() {
        val messages = listOf(userMessage(1), storedResult(2)) + (3..12).map { userMessage(it) }
        assertSame(messages, foldStoredToolResults(messages))
    }
}
