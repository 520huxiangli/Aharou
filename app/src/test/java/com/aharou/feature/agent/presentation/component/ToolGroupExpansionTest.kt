package com.aharou.feature.agent.presentation.component

import com.aharou.feature.agent.presentation.AgentAttachment
import com.aharou.feature.agent.presentation.AgentUIMessage
import com.aharou.feature.agent.presentation.MessageRole
import com.aharou.feature.agent.presentation.flattenTurnProcesses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连续工具调用分组的展开判定 + 整轮任务折叠。
 *
 * 分组展开与否 = **上层持久化的手动选择**（[AIAgentViewModel.toolExpansionOverrides]，按 [toolGroupKey] 取）：
 * 没有手动记录就一律收起——工具调用统一默认不展开。
 *
 * 整轮折叠（最外层）只认 [AIAgentViewModel.turnExpansionOverrides]；没记录时「末轮运行中默认展开、
 * 其余默认收起」，任务收工由 activeTurnKey 归 null 自动收起。
 *
 * 结构：工具分组与过程项都挂在轮头 item 的 [ChatRenderItem.turnProcess] 里（与折叠头同一 Composable，
 * 展开时整体动画）；顶层 items 只有「用户消息 / 轮头 / 常显项与结果」。过程项超过阈值的轮由
 * [flattenTurnProcesses] 平铺成顶层 item（无条件平铺），断言走它的用例会显式调用。
 */
class ToolGroupExpansionTest {

    private fun user(id: String) =
        AgentUIMessage(id = id, role = MessageRole.USER, content = "hi")

    private fun tool(id: String) = AgentUIMessage(id = id, role = MessageRole.TOOL, content = "done")

    private fun assistant(id: String) =
        AgentUIMessage(id = id, role = MessageRole.ASSISTANT, content = "看一下")

    private val groupKey = "toolgroup:t1"

    private fun items(
        messages: List<AgentUIMessage>,
        overrides: Map<String, Boolean> = emptyMap(),
        turnOverrides: Map<String, Boolean> = emptyMap(),
        activeTurnKey: String? = null,
        sessionId: String? = null,
    ) = buildChatItems(messages, overrides, turnOverrides, activeTurnKey, sessionId)

    /** 顶层 item 里唯一的轮头 item。 */
    private fun List<ChatRenderItem>.header() = first { it.turnHeader != null }

    /** 所有轮头携带的工具分组项（按轮头顺序展开）。 */
    private fun List<ChatRenderItem>.groupItems(): List<ChatRenderItem> =
        flatMap { it.turnProcess }.filter { it.toolGroup != null }

    @Test
    fun backgroundNotification_foldsIntoTurnProcess() {
        // 后台完成通知是过程性提示：随整轮折叠收起，不再常驻
        val notice = AgentUIMessage(id = "n1", role = MessageRole.USER, content = "done")
            .copy(isBackgroundNotification = true)
        val messages = listOf(user("u0"), tool("t1"), notice, assistant("answer"))
        val rendered = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertFalse("后台通知折进过程", rendered.any { it.key == "n1" })
        assertTrue(rendered.header().turnProcess.any { it.key == "n1" })
        assertTrue("结果正文仍常显", rendered.any { it.key == "answer" })
    }

    @Test
    fun trailingCompactionSummary_isNotPickedAsResult() {
        // 压缩摘要是 ASSISTANT 角色：不得被选为轮「结果」，否则真正的回复正文会被折进过程
        val summary = assistant("summary").copy(isContextSummary = true)
        val messages = listOf(user("u0"), tool("t1"), assistant("answer"), summary)
        val rendered = items(messages)
        assertTrue("回复正文仍是结果", rendered.any { it.key == "answer" })
        assertFalse("摘要折进过程", rendered.any { it.key == "summary" })
        assertTrue(rendered.header().turnProcess.any { it.key == "summary" })
    }

    @Test
    fun samplingLoopStop_foldsIntoTurnProcess() {
        // 采样熔断提示（Aharou 特有）是系统卡片：同样随过程折叠，不当成普通工具行也不当结果
        val halted = AgentUIMessage(id = "halt", role = MessageRole.TOOL, content = "重复输出已中止")
            .copy(isSamplingLoopStop = true)
        val messages = listOf(user("u0"), tool("t1"), halted, assistant("answer"))
        val rendered = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertFalse("熔断提示折进过程", rendered.any { it.key == "halt" })
        assertTrue(rendered.header().turnProcess.any { it.key == "halt" })
        assertTrue("结果正文仍常显", rendered.any { it.key == "answer" })
    }

    // ---- 连续工具调用分组 ----

    @Test
    fun expandedTurn_groupCollapsesByDefault() {
        // 整轮展开（末轮运行中）时，连续工具分组仍默认收起
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val groups = items.groupItems()
        assertEquals(1, groups.size)
        assertFalse(groups.first().groupExpanded)
    }

    @Test
    fun singleTool_isNotGrouped() {
        // 只有一条工具时不折成「1 次工具调用」，直接当普通工具行显示
        val items = items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0")
        assertEquals(0, items.groupItems().size)
        assertTrue(items.header().turnProcess.any { it.key == "t1" })
    }

    @Test
    fun noGroupAutoExpands() {
        // 回归：曾经「组内还在跑」或「本轮仍在进行且这是最后一个分组」会自动弹开整组。
        // 现在一律默认收起，历史分组与最新分组一视同仁。
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            assistant("a1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, activeTurnKey = "turn:u0").groupItems()
        assertEquals(2, groups.size)
        assertTrue("默认全部收起", groups.none { it.groupExpanded })
    }

    @Test
    fun manualExpand_survivesRebuild() {
        val messages = listOf(user("u0"), tool("t1"), tool("t2"))
        val expanded = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(expanded.groupItems().first().groupExpanded)
        // 同一份覆盖重建（列表滚动回收 / 切页返回后重组走的就是这条路径）：状态必须一致
        val rebuilt = items(messages, overrides = mapOf(groupKey to true), activeTurnKey = "turn:u0")
        assertTrue(rebuilt.groupItems().first().groupExpanded)
        assertEquals(expanded.size, rebuilt.size)
    }

    @Test
    fun manualCollapse_isRespected() {
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            overrides = mapOf(groupKey to false),
            activeTurnKey = "turn:u0",
        )
        assertFalse("手动收起过：保持收起", items.groupItems().first().groupExpanded)
    }

    @Test
    fun userMessageBreaksGrouping() {
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            user("u1"),
            tool("t3"), tool("t4"),
        )
        val groups = items(messages, turnOverrides = mapOf("turn:u0" to true, "turn:u1" to true)).groupItems()
        assertEquals(2, groups.size)
        assertEquals("toolgroup:t1", groups[0].key)
        assertEquals("toolgroup:t3", groups[1].key)
    }

    @Test
    fun toolWithAttachments_isNotGrouped() {
        // sendFile / generateImage 产出的文件行就是结果本身：不参与「N 次工具调用」折叠，
        // 也不参与整轮折叠（常显）。它自己是一级 item，同时把左右两批工具切开。
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            user("u0"),
            tool("t1"), tool("t2"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t3"), tool("t4"),
        )
        val items = items(messages, activeTurnKey = "turn:u0")
        // send 是常显项：顶层独立 item
        assertTrue("带附件的工具是顶层常显项", items.any { it.key == "send" })
        val groups = items.groupItems()
        assertEquals(listOf("toolgroup:t1", "toolgroup:t3"), groups.map { it.key })
        assertTrue("分组默认收起", groups.none { it.groupExpanded })
        // send 不是任何分组的成员
        assertFalse(groups.any { group -> group.toolGroup.orEmpty().any { it.id == "send" } })
    }

    // ---- 整轮任务折叠 ----

    @Test
    fun activeTurn_expandsByDefault() {
        // 末轮且 agent 忙：折叠头显示「执行中」并默认展开，过程项照常挂载
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = "turn:u0")
        val header = items.header().turnHeader!!
        assertTrue(header.running)
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun activeTurn_keepsAssistantBeforeFollowingTools() {
        val items = items(
            listOf(user("u0"), assistant("a1"), tool("t1"), tool("t2")),
            activeTurnKey = "turn:u0",
        )
        assertEquals(listOf("a1", "toolgroup:t1"), items.header().turnProcess.map { it.key })
        assertFalse(items.any { it.key == "a1" })
    }

    @Test
    fun activeTurn_keepsSuccessiveAssistantMessagesInOrder() {
        val messages = listOf(user("u0"), assistant("a1"), tool("t1"), assistant("a2"))
        val running = items(messages, activeTurnKey = "turn:u0")
        assertEquals(listOf("a1", "t1", "a2"), running.header().turnProcess.map { it.key })
        assertFalse(running.any { it.key == "a2" })

        val finished = items(messages, turnOverrides = mapOf("turn:u0" to true))
        assertEquals(listOf("a1", "t1"), finished.header().turnProcess.map { it.key })
        assertEquals(1, finished.count { it.key == "a2" })
    }

    @Test
    fun finishedTurn_autoCollapses() {
        // 收工（activeTurnKey 归 null）：折叠头转为「已完成」并默认收起；过程数据仍在，由动画收起
        val items = items(listOf(user("u0"), tool("t1"), tool("t2")), activeTurnKey = null)
        val header = items.header().turnHeader!!
        assertFalse(header.running)
        assertFalse(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    @Test
    fun manualTurnExpand_survivesFinish() {
        // 用户手动展开过：任务收工也不自动收起
        val items = items(
            listOf(user("u0"), tool("t1"), tool("t2")),
            turnOverrides = mapOf("turn:u0" to true),
            activeTurnKey = null,
        )
        val header = items.header().turnHeader!!
        assertTrue(header.expanded)
        assertEquals(1, items.groupItems().size)
    }

    // ---- 窗口按条数截断：最前面那一截半轮 ----

    @Test
    fun truncatedWindow_foldsLeadingHalfTurn() {
        // 窗口从某一轮中间切开时，最前面那段缺的是轮首用户消息、不是折叠头：
        // 过程项同样要折进折叠头，不能摊在历史最前面
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "答"),
        )
        val rendered = items(messages)
        val heads = rendered.filter { it.turnHeader != null }
        assertEquals("只有截断那一截需要补折叠头", 1, heads.size)
        val leading = heads.first().turnHeader!!
        assertEquals("turn:head:t2", leading.key)
        assertFalse(leading.running)
        assertFalse("默认收起", leading.expanded)
        assertEquals(listOf("a1", "toolgroup:t1"), heads.first().turnProcess.map { it.key })
        assertFalse("过程项不再占顶层 item", rendered.any { it.key == "a1" || it.key == "toolgroup:t1" })
    }

    @Test
    fun truncatedWindow_keepsTrailingAnswerVisible() {
        // 这一截的结尾通常就是上一轮的最终回复：它照旧常显，不跟着过程一起折起来
        val messages = listOf(
            tool("t1"), tool("t2"),
            AgentUIMessage(id = "a9", role = MessageRole.ASSISTANT, content = "上一轮的答案"),
            user("u0"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "本轮答案"),
        )
        val rendered = items(messages)
        val leadingHead = rendered.first { it.turnHeader?.key == "turn:head:a9" }
        assertEquals(listOf("toolgroup:t1"), leadingHead.turnProcess.map { it.key })
        assertTrue("上一轮答案常显", rendered.any { it.key == "a9" })
        assertFalse(rendered.first { it.key == "a9" }.reasoningVisible)
    }

    @Test
    fun truncatedWindow_headKeySurvivesOlderMessagesPrepended() {
        // 窗口继续向前补加载（新消息插在块首）时块尾不变、key 不变，手动展开态跟着保住
        val older = listOf(assistant("m0"), tool("m1"))
        val tail = listOf(assistant("a1"), tool("t1"), tool("t2"), user("u0"))
        val before = items(tail, turnOverrides = mapOf("turn:head:t2" to true))
        val after = items(older + tail, turnOverrides = mapOf("turn:head:t2" to true))
        assertTrue(before.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
        assertTrue(after.first { it.turnHeader?.key == "turn:head:t2" }.turnHeader!!.expanded)
    }

    @Test
    fun truncatedWindow_notificationFoldsIntoLeadingProcess() {
        // 后台通知随过程折叠：折进这一截的折叠头里，不占顶层 item
        val notice = AgentUIMessage(id = "n1", role = MessageRole.USER, content = "done")
            .copy(isBackgroundNotification = true)
        val messages = listOf(
            assistant("a1"),
            tool("t1"), tool("t2"),
            notice,
            user("u0"),
        )
        val rendered = items(messages)
        assertFalse("后台通知折进过程", rendered.any { it.key == "n1" })
        val head = rendered.first { it.turnHeader != null }
        assertEquals("turn:head:n1", head.turnHeader!!.key)
        assertTrue(head.turnProcess.any { it.key == "n1" })
    }

    @Test
    fun truncatedWindow_windowWithoutTurnHeadUsesSessionKey() {
        // 窗口里一个轮首都找不到（整段落在同一轮内部）时块尾会随工具输出持续移动：
        // 折叠 key 必须由会话 id 兜住，否则每来一条输出就重建折叠头、手动展开态丢失
        val key = "turn:head:session:s1"
        val override = mapOf(key to true)
        val windows = listOf(
            listOf(assistant("a1"), tool("t1"), tool("t2")),
            listOf(assistant("a1"), tool("t1"), tool("t2"), tool("t3")),
            listOf(tool("t2"), tool("t3"), tool("t4")),
            listOf(assistant("older"), tool("t2"), tool("t3"), tool("t4")),
        )
        windows.forEach { messages ->
            val header = items(messages, turnOverrides = override, sessionId = "s1").header().turnHeader!!
            assertEquals(key, header.key)
            assertTrue("手动展开态跨窗口保持", header.expanded)
        }
        // 换会话：同一份覆盖不该串过去
        val other = items(windows.first(), turnOverrides = override, sessionId = "s2")
        assertFalse(other.header().turnHeader!!.expanded)
        // 会话 id 缺失（旧调用点 / 未传）时退回块尾 id，不崩
        assertEquals("turn:head:t2", items(windows.first()).header().turnHeader!!.key)
    }

    @Test
    fun longProcess_flattensProcessIntoMainList() {
        // 过程项（含分组成员）超过 8 项：不再内联展开，改平铺成主列表 item，由 LazyColumn 按需组合
        val messages = listOf(user("u0"), assistant("a1")) + (1..12).map { tool("t$it") }
        val expanded = flattenTurnProcesses(
            items(
                messages,
                overrides = mapOf(groupKey to true),
                turnOverrides = mapOf("turn:u0" to true),
            )
        )
        assertTrue("过程项不再挂在折叠头里", expanded.header().turnProcess.isEmpty())
        assertEquals("顺序：用户消息 → 折叠头 → 过程子项（分组头、成员依次）→ 结果",
            listOf("u0", "turn:u0#header", "toolgroup:t1", "t1", "t2"),
            expanded.take(5).map { it.key })
        assertEquals((1..12).map { "t$it" }, expanded.filter { it.groupMember }.map { it.message.id })
        assertTrue(
            "平铺后的成员行走 else 分支，contentType 为角色名",
            expanded.filter { it.groupMember }.all { it.contentType == MessageRole.TOOL.name }
        )
        assertEquals("a1", expanded.last().key)
        assertEquals("平铺后 item key 不重复", expanded.size, expanded.map { it.key }.toSet().size)
    }

    @Test
    fun longProcess_keepsSoulHeaderOncePerTurn() {
        // 平铺不能把身份行（用户那条 + 本轮首条助手回复）弄成零次或重复
        val messages = listOf(user("u0"), assistant("a1")) + (1..12).map { tool("t$it") }
        val flat = flattenTurnProcesses(
            items(messages, turnOverrides = mapOf("turn:u0" to true))
        )
        assertEquals(listOf("u0", "a1"), flat.filter { it.showSoulHeader }.map { it.message.id })
    }

    @Test
    fun longProcess_keepsPersistentItemsInOrder() {
        // 常显项（带附件工具 / 结果）不进过程：平铺后仍按原顺序排在过程子项之后，contentType 不变
        val file = AgentAttachment(
            fileName = "report.pdf",
            containerPath = "~/workspace/report.pdf",
            localPath = "/tmp/report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 1024,
            isImage = false,
        )
        val messages = listOf(
            user("u0"),
            assistant("a1"),
            tool("t1"), tool("t2"), tool("t3"), tool("t4"), tool("t5"),
            tool("send").copy(toolName = "sendFile", attachments = listOf(file)),
            tool("t6"), tool("t7"), tool("t8"), tool("t9"),
            AgentUIMessage(id = "a2", role = MessageRole.ASSISTANT, content = "最终答案"),
        )
        val flat = flattenTurnProcesses(items(messages, turnOverrides = mapOf("turn:u0" to true)))
        assertEquals(
            listOf("u0", "turn:u0#header", "a1", "toolgroup:t1", "toolgroup:t6", "send", "a2"),
            flat.map { it.key },
        )
        assertEquals(MessageRole.TOOL.name, flat.first { it.key == "send" }.contentType)
        assertEquals(MessageRole.ASSISTANT.name, flat.first { it.key == "a2" }.contentType)
        assertFalse(flat.first { it.key == "send" }.groupMember)
    }

    @Test
    fun longProcess_collapsedStaysFolded() {
        // 收起时不产出任何子项；展开但分组仍收起时也不产出成员行
        val messages = listOf(user("u0")) + (1..12).map { tool("t$it") }
        val collapsed = flattenTurnProcesses(items(messages))
        assertEquals(listOf("u0", "turn:u0#header"), collapsed.map { it.key })
        assertFalse(collapsed.any { it.groupMember })

        val groupCollapsed = flattenTurnProcesses(
            items(messages, turnOverrides = mapOf("turn:u0" to true))
        )
        assertEquals(
            listOf("u0", "turn:u0#header", "toolgroup:t1"),
            groupCollapsed.map { it.key },
        )
        assertFalse(groupCollapsed.any { it.groupMember })
    }

    @Test
    fun shortProcess_alsoFlattens() {
        // 无条件平铺：短过程不再内联在折叠头里，同样落到顶层
        val rendered = flattenTurnProcesses(
            items(listOf(user("u0"), tool("t1")), activeTurnKey = "turn:u0")
        )
        assertTrue(rendered.header().turnProcess.isEmpty())
        assertEquals(
            listOf("u0", "turn:u0#header", "t1"),
            rendered.map { it.key },
        )
    }

    @Test
    fun plainTextTurn_hasNoHeader() {
        // 无过程（无思考、无工具）的纯文本轮：退化为现状，不加折叠头
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "直接回答"),
        )
        val items = items(messages, activeTurnKey = null)
        assertTrue(items.none { it.turnHeader != null })
        assertTrue(items.any { it.key == "a1" })
    }

    @Test
    fun resultReasoning_becomesProcessItem() {
        // 轮末助手的思考抽为过程项；结果正文块不再重复渲染思考
        val messages = listOf(
            user("u0"),
            AgentUIMessage(id = "a1", role = MessageRole.ASSISTANT, content = "最终答案", reasoning = "想一下"),
        )
        val items = items(messages, turnOverrides = mapOf("turn:u0" to true), activeTurnKey = null)
        assertTrue("思考成为轮头过程项", items.header().turnProcess.any { it.key == "a1#reasoning" })
        assertFalse("结果正文块不重复渲染思考", items.first { it.key == "a1" }.reasoningVisible)
    }
}
