package com.aharou.feature.agent.domain.inbox

import com.aharou.feature.workspace.domain.WorkspaceIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedInboxTest {
    @Test
    fun workspaceIdIsStableAndCarriesDirectoryName() {
        val id = WorkspaceIdentity.idFor("/sdcard/projects/demo")

        assertEquals("demo-${WorkspaceIdentity.shortHash("/sdcard/projects/demo")}", id)
        assertEquals(id, WorkspaceIdentity.idFor("/sdcard/projects/demo/"))
        assertTrue(WorkspaceIdentity.idFor("/sdcard/a/demo") != WorkspaceIdentity.idFor("/sdcard/b/demo"))
        assertNull(WorkspaceIdentity.idFor("  "))
    }

    @Test
    fun shortHashIsTheSameMd5PrefixUsedByProjectConfigDir() {
        // MD5("abc") = 900150983cd24fb0d6963f7d28e17f72，取前 8 位。
        assertEquals("90015098", WorkspaceIdentity.shortHash("abc"))
    }

    @Test
    fun splitMessagesDropsBlankLinesAndTrims() {
        val text = "- 2026-10-20 15:40 [软件] 第一条\n\n   \n- 2026-10-20 15:41 [写作] 第二条  \n"

        assertEquals(
            listOf("- 2026-10-20 15:40 [软件] 第一条", "- 2026-10-20 15:41 [写作] 第二条"),
            SharedInbox.splitMessages(text)
        )
    }

    @Test
    fun emptyFileHasNoMessages() {
        assertTrue(SharedInbox.splitMessages("").isEmpty())
        assertTrue(SharedInbox.unreadMessages(emptyList(), 0).isEmpty())
    }

    @Test
    fun firstReadReturnsEverything() {
        val all = listOf("a", "b", "c")

        assertEquals(all, SharedInbox.unreadMessages(all, 0))
    }

    @Test
    fun alreadyReadLinesAreSkipped() {
        val all = listOf("a", "b", "c", "d")

        assertEquals(listOf("c", "d"), SharedInbox.unreadMessages(all, 2))
    }

    @Test
    fun readPositionAtEndHasNoUnread() {
        val all = listOf("a", "b")

        assertTrue(SharedInbox.unreadMessages(all, 2).isEmpty())
    }

    @Test
    fun shrunkFileTreatsEveryLineAsNew() {
        // 已读位置比现有行数大：文件被重写过，旧位置失效，宁可重提示一遍也不让新消息被吃掉。
        val all = listOf("a", "b")

        assertEquals(all, SharedInbox.unreadMessages(all, 5))
    }

    @Test
    fun latestKeepsOnlyTheMostRecent() {
        val all = (1..5).map { "m$it" }

        assertEquals(listOf("m4", "m5"), SharedInbox.latest(all, max = 2))
        assertEquals(all, SharedInbox.latest(all, max = 5))
        assertEquals(all, SharedInbox.latest(all, max = 0))
    }

    @Test
    fun defaultLimitLeavesSmallBacklogUntouched() {
        val all = (1..SharedInbox.MAX_INJECTED).map { "m$it" }

        assertEquals(all, SharedInbox.latest(all))
    }
}
