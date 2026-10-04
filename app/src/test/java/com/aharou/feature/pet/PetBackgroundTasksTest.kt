package com.aharou.feature.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PetBackgroundTasksTest {

    private val named = "%1\$s · %2\$s"
    private val subagents = "子代理 %1\$d 个 · %2\$s"
    private val count = "后台 %1\$d 个任务 · %2\$s"

    private fun line(tasks: List<PetTaskRef>, now: Long) =
        backgroundTaskLine(tasks, now, named, subagents, count)

    @Test
    fun noTaskMeansNoBubble() {
        assertNull(line(emptyList(), 1_000L))
    }

    @Test
    fun singleNamedTaskShowsItsName() {
        val tasks = listOf(PetTaskRef("term:1", PetTaskKind.TERMINAL, "npm run dev", 1_000L))
        assertEquals("npm run dev · 1:05", line(tasks, 66_000L))
    }

    @Test
    fun multipleTerminalTasksFallBackToCount() {
        val tasks = listOf(
            PetTaskRef("term:1", PetTaskKind.TERMINAL, "npm run dev", 1_000L),
            PetTaskRef("term:2", PetTaskKind.TERMINAL, "vite build", 5_000L),
        )
        // 时长取最早启动的那个
        assertEquals("后台 2 个任务 · 1:05", line(tasks, 66_000L))
    }

    @Test
    fun subagentsUseTheirOwnWording() {
        val tasks = listOf(
            PetTaskRef("sub:a", PetTaskKind.SUBAGENT, "", 0L),
            PetTaskRef("sub:b", PetTaskKind.SUBAGENT, "", 0L),
        )
        assertEquals("子代理 2 个 · 0:09", line(tasks, 9_000L))
    }

    @Test
    fun elapsedFormatting() {
        assertEquals("0:00", formatElapsed(0L))
        assertEquals("0:59", formatElapsed(59_400L))
        assertEquals("1:05", formatElapsed(65_000L))
        assertEquals("59:59", formatElapsed(3_599_000L))
        assertEquals("1:00:00", formatElapsed(3_600_000L))
        assertEquals("2:03:04", formatElapsed(7_384_000L))
    }

    @Test
    fun negativeElapsedClampsToZero() {
        assertEquals("0:00", formatElapsed(-5_000L))
    }
}
