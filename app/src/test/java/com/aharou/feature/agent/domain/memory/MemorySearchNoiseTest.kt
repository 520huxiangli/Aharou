package com.aharou.feature.agent.domain.memory

import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 检索噪音：中文整词几乎不会原样出现在条目里，能对上的经常只有 bigram 碎片
 * （「完全生僻词」撞上「完全」）。这类碎片命中不该算作「找到了」。
 */
class MemorySearchNoiseTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val repository = MemoryRepository(
        globalMemorySource = mockk(relaxed = true),
        executionModeHolder = mockk(relaxed = true),
        containerInstaller = mockk(relaxed = true),
        projectAicodeRoot = mockk(relaxed = true),
        memoryUsageStore = mockk(relaxed = true),
    )

    private fun memoryOf(name: String, description: String, content: String): Memory =
        Memory(
            name = name,
            description = description,
            scope = MemoryScope.GLOBAL,
            file = tempFolder.newFile("$name.md"),
            content = content
        )

    @Test
    fun `只有 bigram 碎片命中时不返回结果`() {
        val noise = memoryOf("noise", "无关条目", "这里完全按计划执行")
        val result = repository.searchMemories("zzqvmpplwk 完全生僻词", listOf(noise), 8)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `原始词命中时照常返回`() {
        val target = memoryOf("target", "容器构建说明", "PRoot 相关")
        val result = repository.searchMemories("PRoot", listOf(target), 8)
        assertEquals(listOf("target"), result.map { it.name })
    }

    @Test
    fun `原始词命中优先于纯碎片命中`() {
        val fragment = memoryOf("fragment", "描述", "完全按计划执行")
        val exact = memoryOf("exact", "描述", "记忆同步的周期设置")
        val result = repository.searchMemories("记忆同步", listOf(fragment, exact), 8)
        assertEquals(listOf("exact"), result.map { it.name })
    }

    @Test
    fun `命中更多的条目排在前面`() {
        val two = memoryOf("two", "描述", "回滚到最近一次归档，undo 语义")
        val one = memoryOf("one", "描述", "归档快照")
        val result = repository.searchMemories("undo 归档", listOf(one, two), 8)
        assertEquals(listOf("two", "one"), result.map { it.name })
    }
}
