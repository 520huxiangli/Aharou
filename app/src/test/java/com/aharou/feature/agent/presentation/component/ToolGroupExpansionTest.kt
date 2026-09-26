package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定。
 *
 * 展开与否 = **上层持久化的手动选择**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取）：
 * 没有手动记录就一律收起——工具调用统一默认不展开，组内还在跑、本轮仍在进行都不再自动弹开。
 *
 * 重点守两条：①手动选择跨重建稳定（持久化的语义就是每次重建都按同一份覆盖走）；
 * ②没有手动记录时任何分组都不自动展开。
 */
class ToolGroupExpansionTest {

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
    ) = buildChatItems(messages, overrides)

    @Test
    fun finishedTurn_collapsesByDefault() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertFalse(items.first().groupExpanded)
        // 收起时不生成成员行
        assertFalse(items.any { it.key == "t1" })
        assertEquals(1, items.size)
    }

    @Test
    fun groupWithSingleMember_collapsesByDefault() {
        // 单条工具调用同样折成一行且默认收起：工具调用的默认态统一，不因成员多少而变
        val items = items(listOf(tool("t1")))
        assertEquals(1, items.size)
        assertFalse(items.first().groupExpanded)
    }

    @Test
    fun noGroupAutoExpands() {
        // 回归：曾经「组内还在跑」或「本轮仍在进行且这是最后一个分组」会自动弹开整组。
        // 现在一律默认收起，历史分组与最新分组一视同仁。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val items = items(messages)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertTrue("默认全部收起", headers.none { it.groupExpanded })
        // 都没展开：两个分组头 + 中间那条助手消息，没有成员行
        assertEquals(3, items.size)
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(expanded.first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true))
        assertTrue(rebuilt.first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
        // 展开时成员行才生成：头 + 2 个成员
        assertTrue(rebuilt.any { it.key == "t1" })
        assertTrue(rebuilt.any { it.key == "t2" })
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
        )
        assertFalse("手动收起过：保持收起", items.first().groupExpanded)
        assertEquals(1, items.size)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            tool("t1"),
            AgentUIMessage(id = "u1", role = MessageRole.USER, content = "继续"),
            tool("t2"),
        )
        val items = items(messages)
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(2, headers.size)
        assertEquals("toolgroup:t1", headers[0].key)
        assertEquals("toolgroup:t2", headers[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // sendFile / generateImage 产出的文件行就是结果本身：不参与「N 次工具调用」折叠，
        // 否则分组默认收起就等于把发来的文件藏起来。它自己是一级 item，同时把左右两批工具切开。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages)
        assertEquals("带附件的工具自己要是一级 item", 1, items.count { it.key == "send" })
        // 两侧的连续工具各自成组（分组默认收起，所以只剩两个头）
        val headers = items.filter { it.key.startsWith("toolgroup:") }
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), headers.map { it.key })
        assertTrue("分组默认收起", headers.none { it.groupExpanded })
        // send 不是任何分组的成员：不会因为它落在两个分组之间而被折叠吞掉
        assertFalse(items.any { it.toolGroup?.any { member -> member.id == "send" } == true })
    }

    // ---- 成员行缩进判定（isExpandedGroupMember）----

    @Test
    fun membersOfExpandedGroup_areIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        // items = [头, t1, t2]
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, 0, isToolRow = false))
        assertTrue("展开组的成员应缩进", isExpandedGroupMember(items, 1, isToolRow = true))
        assertTrue(isExpandedGroupMember(items, 2, isToolRow = true))
    }

    @Test
    fun collapsedGroupHasNoMembers() {
        val items = items(listOf(tool("t1"), tool("t2")))
        assertEquals(1, items.size)
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = false))
    }

    @Test
    fun ordinaryToolRowOutsideGroup_isNotIndented() {
        // 两组都被手动展开：中间那条是单成员组，它必须从**自己的**分组头判定，
        // 不能越过助手正文去继承前一个分组的缩进。
        val messages = listOf(
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t9"),
        )
        val items = items(
            messages,
            overrides = mapOf(groupKey to true, "toolgroup:t9" to true),
        )
        val firstMember = items.indexOfFirst { it.key == "t1" }
        val secondHeader = items.indexOfFirst { it.key == "toolgroup:t9" }
        val secondMember = items.indexOfFirst { it.key == "t9" }
        assertTrue("前置条件：两个分组都应展开", firstMember > 0 && secondHeader > firstMember && secondMember > secondHeader)

        assertTrue("第一组与其成员连续，成员应缩进", isExpandedGroupMember(items, firstMember, isToolRow = true))
        assertFalse("分组头自身不缩进", isExpandedGroupMember(items, secondHeader, isToolRow = false))
        assertTrue("第二组自己的成员应缩进", isExpandedGroupMember(items, secondMember, isToolRow = true))
    }

    @Test
    fun nonToolRow_isNeverIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 1, isToolRow = false))
    }

    @Test
    fun outOfRangeIndex_isNotIndented() {
        val items = items(listOf(tool("t1"), tool("t2")), overrides = mapOf(groupKey to true))
        assertFalse(isExpandedGroupMember(items, 0, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, items.size, isToolRow = true))
        assertFalse(isExpandedGroupMember(items, -1, isToolRow = true))
    }
}
