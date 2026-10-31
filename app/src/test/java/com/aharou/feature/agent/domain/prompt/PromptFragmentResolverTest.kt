package com.aharou.feature.agent.domain.prompt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * `prompts.custom/` 片段解析规则：两位数字身份、扫描排序去重、`.no-builtin` 判定、静态基线合并。
 */
class PromptFragmentResolverTest {

    private val dir: File = Files.createTempDirectory("prompt-resolver-test").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun parseNumber_两位数字前缀_取值() {
        assertEquals(0, PromptFragmentResolver.parseNumber("00-identity.md"))
        assertEquals(5, PromptFragmentResolver.parseNumber("05-我的规则.md"))
        assertEquals(50, PromptFragmentResolver.parseNumber("50-safety.md"))
        assertEquals(99, PromptFragmentResolver.parseNumber("99-any.md"))
    }

    @Test
    fun parseNumber_非两位数字前缀_返回null() {
        assertNull(PromptFragmentResolver.parseNumber("notes.md"))
        assertNull("一位数不算", PromptFragmentResolver.parseNumber("0-x.md"))
        assertNull("四位数不算", PromptFragmentResolver.parseNumber("2024-x.md"))
        assertNull("名称不能为空", PromptFragmentResolver.parseNumber("10-.md"))
        assertNull("子目录不算顶层片段", PromptFragmentResolver.parseNumber("agent/title-generator.md"))
        assertNull(PromptFragmentResolver.parseNumber(".no-builtin"))
    }

    @Test
    fun numberedFragments_按数字升序_过滤非匹配文件() {
        File(dir, "50-安全.md").writeText("a")
        File(dir, "05-开头.md").writeText("b")
        File(dir, "notes.md").writeText("c")
        File(dir, "agent").apply { mkdirs() }
        File(dir, "agent/title-generator.md").writeText("d")
        File(dir, ".no-builtin").writeText("")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(listOf(5, 50), result.map { it.first })
        assertTrue(result.all { it.second.isFile })
    }

    @Test
    fun numberedFragments_同数字_取字典序首个() {
        File(dir, "50-zzz.md").writeText("z")
        File(dir, "50-aaa.md").writeText("a")

        val result = PromptFragmentResolver.numberedFragments(dir)

        assertEquals(1, result.size)
        assertEquals("50-aaa.md", result.first().second.name)
    }

    @Test
    fun numberedFragments_目录不存在_返回空() {
        assertTrue(PromptFragmentResolver.numberedFragments(File(dir, "missing")).isEmpty())
    }

    @Test
    fun isBuiltinDisabled_仅精确名命中() {
        File(dir, ".DS_Store").writeText("x")
        assertFalse(PromptFragmentResolver.isBuiltinDisabled(dir))

        File(dir, ".no-builtin").writeText("")
        assertTrue(PromptFragmentResolver.isBuiltinDisabled(dir))
    }

    @Test
    fun disabledNumbers_只认数字文件名_忽略其余() {
        File(dir, ".disabled").mkdirs()
        File(dir, ".disabled/60").writeText("")
        File(dir, ".disabled/05").writeText("")
        File(dir, ".disabled/README.md").writeText("x")

        val result = PromptFragmentResolver.disabledNumbers(dir)

        assertEquals(setOf(60, 5), result)
    }

    @Test
    fun disabledNumbers_没有标记目录_返回空() {
        assertTrue(PromptFragmentResolver.disabledNumbers(dir).isEmpty())
    }

    @Test
    fun setDisabled_开启写入关闭标记_关闭删除标记() {
        assertTrue(PromptFragmentResolver.setDisabled(dir, 60, true))
        val marker = File(dir, ".disabled/60")
        assertTrue("编号按两位数字命名", marker.isFile)
        assertEquals(setOf(60), PromptFragmentResolver.disabledNumbers(dir))

        assertTrue(PromptFragmentResolver.setDisabled(dir, 60, false))
        assertFalse(marker.exists())
        assertTrue("重复删除不报错", PromptFragmentResolver.setDisabled(dir, 60, false))
        assertTrue(PromptFragmentResolver.disabledNumbers(dir).isEmpty())
    }

    @Test
    fun disabledFileNames_只认非数字文件名() {
        File(dir, ".disabled").mkdirs()
        File(dir, ".disabled/60").writeText("")
        File(dir, ".disabled/plan-mode.md").writeText("")

        assertEquals(setOf("plan-mode.md"), PromptFragmentResolver.disabledFileNames(dir))
        assertEquals(
            "两种标记共存时不互相干扰",
            setOf(60),
            PromptFragmentResolver.disabledNumbers(dir)
        )
    }

    @Test
    fun setNamedDisabled_写入与清除() {
        assertTrue(PromptFragmentResolver.setNamedDisabled(dir, "agent/plan-mode.md", true))
        val marker = File(dir, ".disabled/plan-mode.md")
        assertTrue("标记只用文件名，不带 agent/ 前缀", marker.isFile)
        assertEquals(setOf("plan-mode.md"), PromptFragmentResolver.disabledFileNames(dir))

        assertTrue(PromptFragmentResolver.setNamedDisabled(dir, "agent/plan-mode.md", false))
        assertFalse(marker.exists())
    }

    @Test
    fun mergeStatic_覆盖与新增片段按数字合并() {
        val custom5 = File(dir, "05-新增.md")
        val custom50 = File(dir, "50-自定义安全.md")
        val custom80 = File(dir, "80-结尾.md")
        val custom = listOf(5 to custom5, 50 to custom50, 80 to custom80)

        val merged = PromptFragmentResolver.mergeStatic(listOf(0, 10, 50), custom)

        assertEquals(listOf(0, 5, 10, 50, 80), merged.map { it.first })
        assertNull("内置数字无自定义文件时用默认内容", merged[0].second)
        assertEquals(custom5, merged[1].second)
        assertEquals(custom50, merged[3].second)
        assertEquals(custom80, merged[4].second)
    }
}