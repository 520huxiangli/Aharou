package com.aharou.core.watch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileChangeModelsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun mergeKind_merges_created_then_deleted_into_modified() {
        assertEquals(ChangeKind.CREATED, mergeKind(ChangeKind.CREATED, ChangeKind.CREATED))
        assertEquals(ChangeKind.DELETED, mergeKind(ChangeKind.CREATED, ChangeKind.MODIFIED).let {
            mergeKind(it, ChangeKind.DELETED)
        })
        assertEquals(ChangeKind.MODIFIED, mergeKind(ChangeKind.CREATED, ChangeKind.DELETED))
        assertEquals(ChangeKind.MODIFIED, mergeKind(ChangeKind.DELETED, ChangeKind.CREATED))
    }

    @Test
    fun diffSnapshot_reports_added_changed_removed() {
        val prev = mapOf("a" to "f:1:1", "b" to "f:2:2", "gone" to "f:3:3")
        val now = mapOf("a" to "f:1:1", "b" to "f:9:2", "new" to "d")

        val diff = diffSnapshot(prev, now).toMap()

        assertEquals(ChangeKind.MODIFIED, diff["b"])
        assertEquals(ChangeKind.CREATED, diff["new"])
        assertEquals(ChangeKind.DELETED, diff["gone"])
        assertFalse(diff.containsKey("a"))
        assertTrue(diffSnapshot(emptyMap(), emptyMap()).isEmpty())
    }

    @Test
    fun snapshotDir_records_file_stamp_and_directory_marker_only() {
        val root = tempFolder.newFolder("snap")
        File(root, "file.txt").writeText("hello")
        File(root, "sub").mkdirs()

        val snapshot = snapshotDir(root)

        assertTrue(snapshot.getValue("file.txt").startsWith("f:"))
        assertEquals("d", snapshot.getValue("sub"))
        assertTrue(snapshotDir(File(root, "not-exists")).isEmpty())
    }

    @Test
    fun relativePartsOf_returns_segments_below_root() {
        val root = File("/tmp/ws")
        assertEquals(listOf("a", "b"), relativePartsOf(root, File("/tmp/ws/a/b")))
        assertTrue(relativePartsOf(root, root).isEmpty())
    }

    @Test
    fun readGitignorePatterns_skips_blank_and_comments() {
        val root = tempFolder.newFolder("gitignore")
        File(root, ".gitignore").writeText("# comment\n\nbuild/\nnode_modules\n *.log \n")

        assertEquals(listOf("build", "node_modules", "*.log"), readGitignorePatterns(root))
        assertTrue(readGitignorePatterns(tempFolder.newFolder("no-gitignore")).isEmpty())
    }

    @Test
    fun ignoreRules_matches_name_segments_and_patterns() {
        val rules = IgnoreRules.of(
            WatchFilter(ignoredNames = setOf("build", "node_modules")),
            gitignorePatterns = emptyList()
        )

        assertTrue(rules.isIgnoredDir(listOf("build")))
        assertTrue(rules.isIgnoredDir(listOf("app", "build", "tmp")))
        assertFalse(rules.isIgnoredDir(listOf("src", "main")))

        val withGitignore = IgnoreRules.of(WatchFilter(), gitignorePatterns = listOf("out"))
        assertTrue(withGitignore.isIgnoredDir(listOf("out")))
        assertFalse(withGitignore.isIgnoredDir(listOf("src")))

        // 空父段（订阅根本身）永不剪枝
        assertFalse(rules.isIgnoredDir(emptyList()))
    }

    @Test
    fun ignoreRules_honours_extra_patterns_from_filter() {
        val rules = IgnoreRules.of(
            WatchFilter(extraPatterns = listOf("*.cache"), followGitignore = false),
            gitignorePatterns = emptyList()
        )

        assertTrue(rules.isIgnoredDir(listOf("a", "x.cache")))
        assertFalse(rules.isIgnoredDir(listOf("a", "b")))
    }
}