package com.aharou.feature.agent.domain.tool.subagent

import com.aharou.feature.agent.domain.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理通信总开关的文案与拒绝（纯 JVM，不依赖 Robolectric）。
 *
 * 守的是「开关关掉后模型还能不能照着旧文案去调 team / tell」这条线：
 * 工具说明是模型唯一的动作索引，说明没跟着开关变，关掉也等于没关。
 */
class SubAgentCommGateTest {

    @Test
    fun `关闭时拒绝码与提示都应明确指向设置入口`() {
        val error = SubAgentCommGate.disabledError()
        assertTrue("应返回 Error 而不是 Success（成功会让模型以为消息已送达）", error is ToolResult.Error)
        error as ToolResult.Error
        assertEquals("COMMUNICATION_DISABLED", error.code)
        assertTrue("要告诉模型去哪开", error.message.contains("设置"))
        assertTrue("要给出替代路径", error.message.contains("send"))
    }

    @Test
    fun `开启时描述推广 team 与 tell`() {
        val hint = SubAgentCommGate.descriptionHint(enabled = true)
        assertTrue(hint.contains("team"))
        assertTrue(hint.contains("tell"))
        assertFalse("开启时不该出现关闭态措辞", hint.contains("关闭"))
    }

    @Test
    fun `关闭时描述不再教模型调 team tell`() {
        val hint = SubAgentCommGate.descriptionHint(enabled = false)
        assertTrue("关闭态要明确劝阻", hint.contains("不要"))
        assertTrue(hint.contains("关闭"))
    }

    @Test
    fun `简报与工具描述的口径各自独立`() {
        // 简报是写给子代理自己看的，关闭时该说的是「把结论写进最终回答」，而不是照搬给主代理的工具说明。
        val briefing = SubAgentCommGate.briefingLine(enabled = false)
        assertTrue(briefing.contains("最终结论"))
        assertFalse("简报不该出现给主代理看的 team 调用指引", briefing.contains("task(action=\"team\")"))
        assertTrue(
            "开启时简报要给出准确的动作名",
            SubAgentCommGate.briefingLine(enabled = true).contains("task(action=\"team\")")
        )
    }
}
