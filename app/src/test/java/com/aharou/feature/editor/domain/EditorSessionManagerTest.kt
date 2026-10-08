package com.aharou.feature.editor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * EditorSessionManager：标签页集合、激活页切换与编辑快照的增删读。
 *
 * 它是进程内单例（object），状态跨用例存活，故每个用例前先把标签全关掉、并忘掉本类可能写入的
 * 快照键。覆盖易回归的点：空白路径被忽略、重复 open 不产生两个标签、关闭激活页后切到相邻页、
 * 关掉最后一个标签后激活路径清空、关闭标签同时丢弃其快照。
 */
class EditorSessionManagerTest {

    @Before
    fun resetManager() {
        EditorSessionManager.tabs.value.toList().forEach { EditorSessionManager.close(it) }
        listOf("/a", "/b", "/c").forEach { EditorSessionManager.forget(it) }
    }

    private fun snapshot(content: String = "x") = EditorSessionManager.Snapshot(
        content = content,
        dirty = false,
        cursorLine = 0,
        cursorColumn = 0,
        scrollY = 0,
    )

    // ---------- open ----------

    @Test
    fun open_newPath_addsTabAndActivatesIt() {
        EditorSessionManager.open("/a")
        assertEquals(listOf("/a"), EditorSessionManager.tabs.value)
        assertEquals("/a", EditorSessionManager.activePath.value)
    }

    @Test
    fun open_blankOrEmptyPath_isIgnored() {
        EditorSessionManager.open("")
        EditorSessionManager.open("   ")
        assertTrue(EditorSessionManager.tabs.value.isEmpty())
        assertEquals("", EditorSessionManager.activePath.value)
    }

    @Test
    fun open_samePathTwice_keepsSingleTab() {
        EditorSessionManager.open("/a")
        EditorSessionManager.open("/a")
        assertEquals(listOf("/a"), EditorSessionManager.tabs.value)
    }

    @Test
    fun open_secondFile_appendsToEndAndActivatesIt() {
        EditorSessionManager.open("/a")
        EditorSessionManager.open("/b")
        assertEquals(listOf("/a", "/b"), EditorSessionManager.tabs.value)
        assertEquals("/b", EditorSessionManager.activePath.value)
    }

    // ---------- activate ----------

    @Test
    fun activate_existingTab_switchesActive() {
        EditorSessionManager.open("/a")
        EditorSessionManager.open("/b")
        EditorSessionManager.activate("/a")
        assertEquals("/a", EditorSessionManager.activePath.value)
        // 激活不改动标签顺序
        assertEquals(listOf("/a", "/b"), EditorSessionManager.tabs.value)
    }

    @Test
    fun activate_unknownPath_isIgnored() {
        EditorSessionManager.open("/a")
        EditorSessionManager.activate("/zz")
        assertEquals("/a", EditorSessionManager.activePath.value)
    }

    // ---------- close ----------

    @Test
    fun close_activeTab_switchesToLastRemaining() {
        EditorSessionManager.open("/a")
        EditorSessionManager.open("/b")
        EditorSessionManager.open("/c")
        EditorSessionManager.close("/c")
        assertEquals(listOf("/a", "/b"), EditorSessionManager.tabs.value)
        assertEquals("/b", EditorSessionManager.activePath.value)
    }

    @Test
    fun close_nonActiveTab_keepsActivePath() {
        EditorSessionManager.open("/a")
        EditorSessionManager.open("/b")
        EditorSessionManager.close("/a")
        assertEquals(listOf("/b"), EditorSessionManager.tabs.value)
        assertEquals("/b", EditorSessionManager.activePath.value)
    }

    @Test
    fun close_lastTab_clearsActivePath() {
        EditorSessionManager.open("/a")
        EditorSessionManager.close("/a")
        assertTrue(EditorSessionManager.tabs.value.isEmpty())
        assertEquals("", EditorSessionManager.activePath.value)
    }

    @Test
    fun close_dropsItsSnapshot() {
        EditorSessionManager.open("/a")
        EditorSessionManager.saveSnapshot("/a", snapshot("body"))
        EditorSessionManager.close("/a")
        assertNull(EditorSessionManager.snapshot("/a"))
    }

    // ---------- snapshots ----------

    @Test
    fun snapshot_saveThenRead_roundTrips() {
        EditorSessionManager.saveSnapshot("/a", snapshot("draft"))
        assertEquals("draft", EditorSessionManager.snapshot("/a")?.content)
    }

    @Test
    fun snapshot_unknownPath_returnsNull() {
        assertNull(EditorSessionManager.snapshot("/nope"))
    }

    @Test
    fun forget_removesSnapshotButKeepsTab() {
        EditorSessionManager.open("/a")
        EditorSessionManager.saveSnapshot("/a", snapshot("body"))
        EditorSessionManager.forget("/a")
        assertNull(EditorSessionManager.snapshot("/a"))
        assertEquals(listOf("/a"), EditorSessionManager.tabs.value)
    }
}
