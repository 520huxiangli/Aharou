package com.aharou.feature.agent.domain.workflow

import com.aharou.feature.agent.domain.model.AgentMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 软精简（不调模型、不落库）的纯逻辑：只截断喂模型的 `modelResult`，且幂等。
 * 这条路径是「压缩卡死」的第一道减压阀——命中时不该产生任何模型调用或事件。
 */
class ContextCompactorSoftTrimTest {

    private fun toolResult(result: String, modelResult: String? = null) =
        AgentMessage.ToolResultMessage(toolName = "bash", result = result, modelResult = modelResult)

    @Test
    fun softTrim_trimsOversizedResultAndKeepsFullText() {
        val big = "x".repeat(ContextCompactor.SOFT_TRIM_TOOL_CHARS + 500)
        val messages = listOf(toolResult(big), toolResult("ok"), AgentMessage.UserMessage(content = "hi"))

        val trimmed = ContextCompactor.softTrimToolOutputs(messages)

        val first = trimmed[0] as AgentMessage.ToolResultMessage
        assertEquals(
            ContextCompactor.SOFT_TRIM_TOOL_CHARS + ContextCompactor.SOFT_TRIM_MARKER.length,
            first.modelResult!!.length
        )
        assertTrue(first.modelResult!!.endsWith(ContextCompactor.SOFT_TRIM_MARKER))
        assertEquals("完整正文必须留在 result 里（UI 与持久化要用）", big, first.result)

        val second = trimmed[1] as AgentMessage.ToolResultMessage
        assertNull("没超限的结果不该被精简", second.modelResult)
    }

    @Test
    fun softTrim_leavesExistingModelResultAlone() {
        val big = "y".repeat(ContextCompactor.SOFT_TRIM_TOOL_CHARS * 2)
        val messages = listOf(toolResult(big, modelResult = "already compact"))
        assertSame("已有紧凑投影的工具不该被再截一遍", messages, ContextCompactor.softTrimToolOutputs(messages))
    }

    @Test
    fun softTrim_isIdempotent() {
        val big = "z".repeat(ContextCompactor.SOFT_TRIM_TOOL_CHARS + 1)
        val once = ContextCompactor.softTrimToolOutputs(listOf(toolResult(big)))
        val twice = ContextCompactor.softTrimToolOutputs(once)
        assertSame("第二次应原样返回，不重建列表", once, twice)
    }

    @Test
    fun softTrim_returnsSameListWhenNothingToTrim() {
        val messages = listOf(toolResult("short"), AgentMessage.UserMessage(content = "hi"))
        assertSame(messages, ContextCompactor.softTrimToolOutputs(messages))
    }
}
