package com.aharou.feature.agent.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蒸馏输出的拆条解析。
 *
 * 这段决定了「模型偶发跑偏时会不会丢内容」：拆不动的行必须整句当 fact 保住，
 * 拆对的必须把分类、关键词、待确认标记都落准。
 */
class DistilledEntryParserTest {

    @Test
    fun parsesTypeContentAndKeywords() {
        val entries = DistilledEntryParser.parse(
            "- [preference] 用户偏爱中文回复，不喜欢客套开头。{关键词: 中文回复, 客套, 语言偏好}"
        )

        assertEquals(1, entries.size)
        val entry = entries.single()
        assertEquals(MemoryType.PREFERENCE, entry.type)
        assertEquals("用户偏爱中文回复，不喜欢客套开头。", entry.content)
        assertEquals(listOf("中文回复", "客套", "语言偏好"), entry.keywords)
        assertTrue(entry.name.startsWith(DistilledEntryParser.NAME_PREFIX))
        assertFalse(entry.pending)
    }

    @Test
    fun missingTypeTag_fallsBackToFact() {
        val entry = DistilledEntryParser.parse("- 构建要用 JDK 17 而不是 11。{关键词: JDK, 构建}").single()

        assertEquals(MemoryType.FACT, entry.type)
        assertEquals("构建要用 JDK 17 而不是 11。", entry.content)
    }

    @Test
    fun missingKeywords_leavesEmptyList() {
        val entry = DistilledEntryParser.parse("- [workflow] 改动前先读 CLAUDE.md。").single()

        assertEquals(MemoryType.WORKFLOW, entry.type)
        assertEquals(emptyList<String>(), entry.keywords)
        assertEquals("改动前先读 CLAUDE.md。", entry.content)
    }

    /** 全角冒号是模型最常写的形态，必须和半角等价。 */
    @Test
    fun fullWidthKeywordColon_isAccepted() {
        val entry = DistilledEntryParser.parse("- [fact] 容器镜像走 cnb 同步。{关键词：镜像, cnb}").single()

        assertEquals(listOf("镜像", "cnb"), entry.keywords)
        assertEquals("容器镜像走 cnb 同步。", entry.content)
    }

    @Test
    fun conflictMarker_marksPending() {
        val entry = DistilledEntryParser.parse(
            "- [fact] 出包耗时 5 分钟（与另一条 8 分钟相冲突，待确认）。"
        ).single()

        assertTrue(entry.pending)
    }

    /** 模型跑偏写了标题、说明、空行时，只取真正的要点行，别把标题当条目。 */
    @Test
    fun ignoresHeadingsAndProseLines() {
        val entries = DistilledEntryParser.parse(
            """
            ## 蒸馏要点

            以下是本轮整理结果：

            - [preference] 主人要求全程中文。
            - [fact] 容器内 Gradle 需要显式 JAVA_HOME。

            以上，共 2 条。
            """.trimIndent()
        )

        assertEquals(2, entries.size)
        assertEquals(listOf("主人要求全程中文。", "容器内 Gradle 需要显式 JAVA_HOME。"), entries.map { it.content })
    }

    /** 同批内正文开头相同时只留一条：同名文件会互相覆盖，留下反而误导。 */
    @Test
    fun duplicateNames_areDropped() {
        val entries = DistilledEntryParser.parse(
            """
            - [fact] 用户偏爱中文回复并且不喜欢任何形式的客套。
            - [preference] 用户偏爱中文回复并且不喜欢任何形式的英文。
            """.trimIndent()
        )

        assertEquals(1, entries.size)
    }

    @Test
    fun nameIsPrefixPlusCleanBody() {
        val entry = DistilledEntryParser.parse("- [fact] 用 `BoundedLineReader` 读大文件，别用 readBytes。").single()

        assertEquals(DistilledEntryParser.NAME_PREFIX + "用BoundedLineRead", entry.name)
    }

    @Test
    fun mostlyEmptyInput_yieldsNothing() {
        assertEquals(emptyList<DistilledEntry>(), DistilledEntryParser.parse(""))
        assertEquals(emptyList<DistilledEntry>(), DistilledEntryParser.parse("无"))
    }

    @Test
    fun overlongContent_isTruncated() {
        val entry = DistilledEntryParser.parse("- [fact] " + "字".repeat(5_000)).single()

        assertEquals(2_000, entry.content.length)
    }
}
