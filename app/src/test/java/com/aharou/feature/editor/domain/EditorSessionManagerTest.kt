package com.aharou.feature.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EditorSessionManager：标签页集合、激活页切换与编辑快照的增删读。
 *
 * 每个用例使用独立的工作区身份，避免进程内会话跨用例污染。
 * 覆盖易回归的点：空白路径被忽略、重复 open 不产生两个标签、关闭激活页后切到相邻页、
 * 关掉最后一个标签后激活路径清空、关闭标签同时丢弃其快照。
 */
class EditorSessionManagerTest {

    private lateinit var session: EditorSessionManager.Session

    @Before
    fun resetManager() {
        session = EditorSessionManager.forWorkspace(java.util.UUID.randomUUID().toString())
    }

    private fun snapshot(content: String = "x") = EditorSessionManager.Snapshot(
        content = content,
        dirty = false,
        cursorLine = 0,
        cursorColumn = 0,
        scrollY = 0,
    )

    @Test
    fun samePath_inDifferentWorkspaces_keepsIndependentTabsAndDrafts() {
        val other = EditorSessionManager.forWorkspace(java.util.UUID.randomUUID().toString())
        session.open("~/workspace/a")
        other.open("~/workspace/a")
        session.saveSnapshot("~/workspace/a", snapshot("first"))
        other.saveSnapshot("~/workspace/a", snapshot("second"))
        session.close("~/workspace/a")
        assertEquals(listOf("~/workspace/a"), other.tabs.value)
        assertEquals("second", other.snapshot("~/workspace/a")?.content)
    }

    @Test
    fun returningToWorkspace_restoresDraftAndBaseline() {
        val root = java.util.UUID.randomUUID().toString()
        val original = EditorSessionManager.forWorkspace(root)
        original.open("/a")
        original.saveSnapshot("/a", snapshot("draft").copy(dirty = true, baselineContent = "disk"))
        val restored = EditorSessionManager.forWorkspace(root)
        assertEquals("draft", restored.snapshot("/a")?.content)
        assertEquals("disk", restored.snapshot("/a")?.baselineContent)
        assertEquals("/a", restored.activePath.value)
    }

    @Test
    fun saveCompletion_preservesNewerDraftAndUpdatesOnlyItsBaseline() {
        session.saveSnapshot("/a", snapshot("newer").copy(dirty = true, baselineContent = "disk"))
        session.markSaved("/a", "saved")
        assertEquals("newer", session.snapshot("/a")?.content)
        assertEquals("saved", session.snapshot("/a")?.baselineContent)
        assertTrue(session.snapshot("/a")?.dirty == true)
        session.markSaved("/a", "newer")
        assertEquals(false, session.snapshot("/a")?.dirty)
    }

    // ---------- open ----------

    @Test
    fun open_newPath_addsTabAndActivatesIt() {
        session.open("/a")
        assertEquals(listOf("/a"), session.tabs.value)
        assertEquals("/a", session.activePath.value)
    }

    @Test
    fun open_blankOrEmptyPath_isIgnored() {
        session.open("")
        session.open("   ")
        assertTrue(session.tabs.value.isEmpty())
        assertEquals("", session.activePath.value)
    }

    @Test
    fun open_samePathTwice_keepsSingleTab() {
        session.open("/a")
        session.open("/a")
        assertEquals(listOf("/a"), session.tabs.value)
    }

    @Test
    fun open_secondFile_appendsToEndAndActivatesIt() {
        session.open("/a")
        session.open("/b")
        assertEquals(listOf("/a", "/b"), session.tabs.value)
        assertEquals("/b", session.activePath.value)
    }

    // ---------- activate ----------

    @Test
    fun activate_existingTab_switchesActive() {
        session.open("/a")
        session.open("/b")
        session.activate("/a")
        assertEquals("/a", session.activePath.value)
        // 激活不改动标签顺序
        assertEquals(listOf("/a", "/b"), session.tabs.value)
    }

    @Test
    fun activate_unknownPath_isIgnored() {
        session.open("/a")
        session.activate("/zz")
        assertEquals("/a", session.activePath.value)
    }

    // ---------- close ----------

    @Test
    fun close_activeTab_switchesToLastRemaining() {
        session.open("/a")
        session.open("/b")
        session.open("/c")
        session.close("/c")
        assertEquals(listOf("/a", "/b"), session.tabs.value)
        assertEquals("/b", session.activePath.value)
    }

    @Test
    fun close_nonActiveTab_keepsActivePath() {
        session.open("/a")
        session.open("/b")
        session.close("/a")
        assertEquals(listOf("/b"), session.tabs.value)
        assertEquals("/b", session.activePath.value)
    }

    @Test
    fun close_lastTab_clearsActivePath() {
        session.open("/a")
        session.close("/a")
        assertTrue(session.tabs.value.isEmpty())
        assertEquals("", session.activePath.value)
    }

    @Test
    fun close_dropsItsSnapshot() {
        session.open("/a")
        session.saveSnapshot("/a", snapshot("body"))
        session.close("/a")
        assertNull(session.snapshot("/a"))
    }

    // ---------- snapshots ----------

    @Test
    fun snapshot_saveThenRead_roundTrips() {
        session.saveSnapshot("/a", snapshot("draft"))
        assertEquals("draft", session.snapshot("/a")?.content)
    }

    @Test
    fun snapshot_unknownPath_returnsNull() {
        assertNull(session.snapshot("/nope"))
    }

    @Test
    fun forget_removesSnapshotButKeepsTab() {
        session.open("/a")
        session.saveSnapshot("/a", snapshot("body"))
        session.forget("/a")
        assertNull(session.snapshot("/a"))
        assertEquals(listOf("/a"), session.tabs.value)
    }
}
