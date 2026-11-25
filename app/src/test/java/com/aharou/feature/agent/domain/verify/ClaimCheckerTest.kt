package com.aharou.feature.agent.domain.verify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaimCheckerTest {

    private fun evidence(excerpt: String, toolName: String = "bash") = Evidence(
        toolName = toolName,
        recordedAtMillis = 0L,
        argsSummary = "",
        excerpt = excerpt,
        truncated = false
    )

    @Test
    fun quotedTextFoundInEvidencePasses() {
        val result = ClaimChecker.check(
            answer = "已完成构建，输出显示\"构建成功，产物已生成\"。",
            evidence = listOf(evidence("构建成功，产物已生成\n耗时 12 秒"))
        )

        assertEquals(1, result.checked)
        assertTrue(result.ungrounded.isEmpty())
        assertTrue(result.passed)
    }

    @Test
    fun quotedTextMissingIsReported() {
        val result = ClaimChecker.check(
            answer = "测试结论是\"全部用例均已通过\"。",
            evidence = listOf(evidence("12 passed, 0 failed"))
        )

        assertEquals(1, result.checked)
        assertEquals(1, result.ungrounded.size)
        assertEquals(ClaimKind.QUOTE, result.ungrounded.single().kind)
        assertEquals("全部用例均已通过", result.ungrounded.single().text)
    }

    @Test
    fun chineseCurlyQuotesAreChecked() {
        val result = ClaimChecker.check(
            answer = "依赖“已经下载完成”，可以开始编译。",
            evidence = listOf(evidence("正在解析依赖"))
        )

        assertEquals(1, result.checked)
        assertEquals(ClaimKind.QUOTE, result.ungrounded.single().kind)
    }

    @Test
    fun fencedCodeBlockIsSkipped() {
        val result = ClaimChecker.check(
            answer = "示例如下：\n```kotlin\nval s = \"这段不在证据里\"\n```\n说明如上。",
            evidence = listOf(evidence("ok"))
        )

        assertEquals(0, result.checked)
        assertTrue(result.ungrounded.isEmpty())
    }

    @Test
    fun unclosedFenceSkipsRemainingText() {
        val result = ClaimChecker.check(
            answer = "结论：\n```kotlin\nval s = \"不存在\"\n",
            evidence = listOf(evidence("ok"))
        )

        assertEquals(0, result.checked)
        assertTrue(result.ungrounded.isEmpty())
    }

    @Test
    fun emptyEvidenceSkipsInsteadOfReportingEverything() {
        val result = ClaimChecker.check(
            answer = "见 app/src/main/java/com/aharou/Foo.kt 与 https://example.com/doc 。",
            evidence = emptyList()
        )

        assertEquals(0, result.checked)
        assertTrue(result.ungrounded.isEmpty())
        assertTrue(result.note.contains("跳过"))
    }

    @Test
    fun pathsAreCheckedAgainstEvidence() {
        val result = ClaimChecker.check(
            answer = "改动落在 app/src/main/java/com/aharou/Foo.kt，另见 app/src/main/java/com/aharou/Bar.kt。",
            evidence = listOf(evidence("已写入 app/src/main/java/com/aharou/Foo.kt"))
        )

        assertEquals(2, result.checked)
        assertEquals(1, result.ungrounded.size)
        assertEquals(ClaimKind.PATH, result.ungrounded.single().kind)
        assertEquals("app/src/main/java/com/aharou/Bar.kt", result.ungrounded.single().text)
    }

    @Test
    fun urlsAreCheckedAgainstEvidence() {
        val result = ClaimChecker.check(
            answer = "参考 https://example.com/a?b=1 的说法。",
            evidence = listOf(evidence("没有链接"))
        )

        assertEquals(1, result.checked)
        assertEquals(ClaimKind.URL, result.ungrounded.single().kind)
        assertEquals("https://example.com/a?b=1", result.ungrounded.single().text)
    }

    @Test
    fun quotedToolNameIsSkipped() {
        val result = ClaimChecker.check(
            answer = "用\"readFile\"读取即可。",
            evidence = listOf(evidence("ok", toolName = "readFile"))
        )

        assertEquals(0, result.checked)
        assertTrue(result.ungrounded.isEmpty())
    }

    @Test
    fun quotedTextWithEllipsisIsSkipped() {
        val result = ClaimChecker.check(
            answer = "错误信息是\"读取失败…请重试\"。",
            evidence = listOf(evidence("读取失败：网络超时，请重试"))
        )

        assertEquals(0, result.checked)
    }

    @Test
    fun blankAnswerIsNotChecked() {
        val result = ClaimChecker.check("   ", listOf(evidence("ok")))

        assertEquals(0, result.checked)
        assertTrue(result.ungrounded.isEmpty())
    }
}
