package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 时间衰减系数：近期改动过的记忆更可能仍然有效，命中强度按年龄分层打折。
 * 只测纯函数——[recencyFactor] 不碰 Android，用真实文件的 mtime 就能定出年龄。
 */
class MemoryRecencyFactorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun memoryAged(days: Long): Memory {
        val file = tempFolder.newFile()
        file.setLastModified(System.currentTimeMillis() - days * DAY_MILLIS)
        return Memory(
            name = "aged_$days",
            description = "描述",
            scope = MemoryScope.GLOBAL,
            file = file,
            content = "正文"
        )
    }

    @Test
    fun withinAWeek_keepsFullWeight() {
        assertEquals(1.0, recencyFactor(memoryAged(1)), DELTA)
    }

    @Test
    fun withinAMonth_discountsToPointEightyFive() {
        assertEquals(0.85, recencyFactor(memoryAged(20)), DELTA)
    }

    @Test
    fun withinAQuarter_discountsToPointSeven() {
        assertEquals(0.7, recencyFactor(memoryAged(60)), DELTA)
    }

    @Test
    fun older_discountsToPointFive() {
        assertEquals(0.5, recencyFactor(memoryAged(200)), DELTA)
    }

    @Test
    fun missingFile_keepsFullWeight() {
        val memory = Memory(
            name = "no_file",
            description = "描述",
            scope = MemoryScope.GLOBAL,
            content = "正文"
        )
        assertEquals(1.0, recencyFactor(memory), DELTA)
    }

    @Test
    fun factorNeverIncreasesWithAge() {
        val factors = listOf(1L, 20L, 60L, 200L).map { recencyFactor(memoryAged(it)) }
        assertEquals(factors.sortedDescending(), factors)
    }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1_000L
        const val DELTA = 0.0001
    }
}
