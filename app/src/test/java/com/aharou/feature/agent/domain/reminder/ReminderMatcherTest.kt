package com.aharou.feature.agent.domain.reminder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 规则提醒的条件求值：正则忽略大小写、all/any 组合、is_error、坏正则不炸。
 *
 * 只测纯逻辑（[ReminderMatcher]），不碰 Android 的 assets 与文件系统。
 */
class ReminderMatcherTest {

    private fun args(pattern: String) = ReminderCondition.ArgsRegex(pattern)

    private fun result(pattern: String) = ReminderCondition.ResultRegex(pattern)

    @Test
    fun args_regex_ignores_case_and_matches_substring() {
        assertTrue(ReminderMatcher.matchesAll(listOf(args("git\\s+push")), MatchMode.ALL, "git push origin master", "", false))
        assertTrue(ReminderMatcher.matchesAll(listOf(args("git\\s+push")), MatchMode.ALL, "GIT PUSH", "", false))
        assertFalse(ReminderMatcher.matchesAll(listOf(args("git\\s+push")), MatchMode.ALL, "git status", "", false))
    }

    @Test
    fun all_mode_requires_every_condition() {
        val conditions = listOf(args("git\\s+push"), result("GnuTLS"))
        assertTrue(ReminderMatcher.matchesAll(conditions, MatchMode.ALL, "git push", "fatal: GnuTLS recv error", true))
        assertFalse(ReminderMatcher.matchesAll(conditions, MatchMode.ALL, "git push", "Everything up-to-date", false))
    }

    @Test
    fun any_mode_needs_only_one_condition() {
        val conditions = listOf(args("git\\s+push"), result("Connection timed out"))
        assertTrue(ReminderMatcher.matchesAll(conditions, MatchMode.ANY, "git status", "Connection timed out", true))
        assertFalse(ReminderMatcher.matchesAll(conditions, MatchMode.ANY, "git status", "clean", false))
    }

    @Test
    fun empty_condition_list_never_matches() {
        assertFalse(ReminderMatcher.matchesAll(emptyList(), MatchMode.ALL, "anything", "anything", false))
    }

    @Test
    fun is_error_condition_checks_status() {
        val condition = ReminderCondition.IsError(value = true)
        assertTrue(ReminderMatcher.matches(condition, "", "", true))
        assertFalse(ReminderMatcher.matches(condition, "", "", false))
    }

    @Test
    fun invalid_regex_is_treated_as_no_match() {
        assertFalse(ReminderMatcher.matchesAll(listOf(args("([unclosed")), MatchMode.ALL, "git push", "", false))
    }

    @Test
    fun nested_groups_are_not_evaluated_inline() {
        val nested = ReminderCondition.TriggerCondition(conditions = listOf(args("git\\s+push")))
        assertFalse(ReminderMatcher.matches(nested, "git push", "", false))
    }
}
