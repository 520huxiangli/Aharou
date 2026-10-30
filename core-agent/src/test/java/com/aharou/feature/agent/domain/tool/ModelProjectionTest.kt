package com.aharou.feature.agent.domain.tool

import com.aharou.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    private val backgroundNotification =
        """{"kind":"background_task","notice":"notice","task_id":"bg-1","title":"gradle build","exit_code":0}"""

    private val subagentNotification =
        """{"kind":"subagent","notice":"notice","subagent_id":"sub-7","subagent_title":"model research"}"""

    private val expectedEditText =
        "Edited file successfully: /root/x.kt\nReplacements: 2\nChanged lines: +3 -1"

    private val expectedWriteText =
        "Created file successfully: /root/y.kt (lines: 10, +10 -0)"

    private fun editResult(notificationsJson: String? = null): String =
        """{"status":"success","data":{"path":"/root/x.kt","replacements":2,"added_lines":3,"removed_lines":1}""" +
            (notificationsJson?.let { ""","notifications":$it""" } ?: "") + "}"

    private fun writeResult(notificationsJson: String? = null): String =
        """{"status":"success","data":{"path":"/root/y.kt","created":true,"added_lines":10,""" +
            """"removed_lines":0,"lines_written":10}""" +
            (notificationsJson?.let { ""","notifications":$it""" } ?: "") + "}"

    /** 搭车通知必须原样跟着 editFile 投影回到模型，否则结果落库后 ack 就永久丢了。 */
    @Test
    fun projection_editFileKeepsInjectedNotifications() {
        val text = modelToolResultText("editFile", editResult("[$backgroundNotification,$subagentNotification]"))
        assertNotNull(text)
        assertTrue(text!!.startsWith(expectedEditText))
        assertTrue("通知数组应整段补回", text.contains("\"kind\":\"background_task\""))
        assertTrue(text.contains("\"task_id\":\"bg-1\""))
        assertTrue(text.contains("\"subagent_id\":\"sub-7\""))
    }

    /** writeFile 投影同样要带回通知。 */
    @Test
    fun projection_writeFileKeepsInjectedNotifications() {
        val text = modelToolResultText("writeFile", writeResult("[$subagentNotification]"))
        assertNotNull(text)
        assertTrue(text!!.startsWith(expectedWriteText))
        assertTrue(text.contains("\"subagent_id\":\"sub-7\""))
    }

    /** 空通知数组：与没有通知完全等价，不多一行说明。 */
    @Test
    fun projection_ignoresEmptyNotifications() {
        assertEquals(expectedEditText, modelToolResultText("editFile", editResult("[]")))
    }

    /** 没有 notifications 字段时投影保持原样。 */
    @Test
    fun projection_unchangedWithoutNotifications() {
        assertEquals(expectedEditText, modelToolResultText("editFile", editResult()))
        assertEquals(expectedWriteText, modelToolResultText("writeFile", writeResult()))
    }

    /** 失败结果不投影（即使带了通知），交给调用方回退完整 result，通知不会丢。 */
    @Test
    fun projection_returnsNullForFailedResultWithNotifications() {
        val failed = """{"status":"error","code":"STALE_FILE","message":"conflict",""" +
            """"data":{"path":"/root/x.kt"},"notifications":[$backgroundNotification]}"""
        assertNull(modelToolResultText("editFile", failed))
    }

    /** 非文件工具返回 null，由调用方回退完整 result。 */
    @Test
    fun projection_returnsNullForNonFileTool() {
        val bash = """{"status":"success","data":{"output":"ok"},""" +
            """"notifications":[$backgroundNotification]}"""
        assertNull(modelToolResultText("bash", bash))
    }

    /** 成功但 data 里取不到路径（投影无法生成）也返回 null，同样回退完整 result。 */
    @Test
    fun projection_returnsNullWhenProjectionCannotBeBuilt() {
        val noPath = """{"status":"success","data":{},"notifications":[$backgroundNotification]}"""
        assertNull(modelToolResultText("editFile", noPath))
    }
}
