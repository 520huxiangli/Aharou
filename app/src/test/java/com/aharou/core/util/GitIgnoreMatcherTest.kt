package com.aharou.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GitIgnoreMatcher] 的路径段匹配行为（含锚定/非锚定差异）。 */
class GitIgnoreMatcherTest {

    @Test
    fun star_ext_matches_any_level() {
        assertTrue(GitIgnoreMatcher.matches("*.log", listOf("debug.log")))
        assertTrue(GitIgnoreMatcher.matches("*.log", listOf("src", "app.log")))
        assertTrue(GitIgnoreMatcher.matches("*.log", listOf("a", "b", "c.log")))
        assertFalse(GitIgnoreMatcher.matches("*.log", listOf("a.txt")))
        assertFalse(GitIgnoreMatcher.matches("*.log", listOf("a", "b", "log.txt")))
    }

    @Test
    fun exact_segment_matches_dir_at_any_level_when_not_anchored() {
        assertTrue(GitIgnoreMatcher.matches("build", listOf("build")))
        assertTrue(GitIgnoreMatcher.matches("build", listOf("src", "build")))
        assertFalse(GitIgnoreMatcher.matches("build", listOf("src", "builder")))
    }

    @Test
    fun exact_segment_anchored_matches_root_only() {
        assertTrue(GitIgnoreMatcher.matches("build", listOf("build"), anchored = true))
        assertFalse(GitIgnoreMatcher.matches("build", listOf("src", "build"), anchored = true))
    }

    @Test
    fun multi_segment_matches_at_any_position_when_not_anchored() {
        assertTrue(GitIgnoreMatcher.matches("build/*.log", listOf("build", "out.log")))
        assertTrue(GitIgnoreMatcher.matches("build/*.log", listOf("src", "build", "out.log")))
        assertFalse(GitIgnoreMatcher.matches("build/*.log", listOf("build", "keep.txt")))
    }

    @Test
    fun multi_segment_anchored_matches_root_prefix_only() {
        assertTrue(GitIgnoreMatcher.matches("build/*.log", listOf("build", "out.log"), anchored = true))
        assertFalse(GitIgnoreMatcher.matches("build/*.log", listOf("src", "build", "out.log"), anchored = true))
        assertFalse(GitIgnoreMatcher.matches("build/*.log", listOf("src", "build"), anchored = true))
    }

    @Test
    fun double_star_prefix_matches_any_depth() {
        assertTrue(GitIgnoreMatcher.matches("**/build/*.log", listOf("build", "x.log"), anchored = true))
        assertTrue(GitIgnoreMatcher.matches("**/build/*.log", listOf("a", "b", "build", "x.log"), anchored = true))
        assertFalse(GitIgnoreMatcher.matches("**/build/*.log", listOf("a", "build", "y.txt"), anchored = true))
    }

    @Test
    fun wildcard_within_segment() {
        assertTrue(GitIgnoreMatcher.matches("*.min.js", listOf("app.min.js")))
        assertTrue(GitIgnoreMatcher.matches("*.min.js", listOf("src", "lib.min.js")))
        assertFalse(GitIgnoreMatcher.matches("*.min.js", listOf("app.js")))
    }

    @Test
    fun special_chars_in_pattern_are_escaped() {
        // `.` 是正则特殊字符，必须按字面匹配
        assertTrue(GitIgnoreMatcher.matches("*.tar.gz", listOf("a.tar.gz")))
        assertFalse(GitIgnoreMatcher.matches("*.tar.gz", listOf("a.targz")))
        assertFalse(GitIgnoreMatcher.matches("*.tar.gz", listOf("a.tar.gz.bak")))
    }

    @Test
    fun is_ignored_returns_true_if_any_pattern_matches() {
        assertTrue(GitIgnoreMatcher.isIgnored(listOf("*.log", "tmp"), listOf("tmp", "x.log")))
        assertTrue(GitIgnoreMatcher.isIgnored(listOf("*.log", "tmp"), listOf("x.log")))
        assertFalse(GitIgnoreMatcher.isIgnored(listOf("*.log", "tmp"), listOf("src", "main.kt")))
    }
}
