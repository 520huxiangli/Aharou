package com.aharou.feature.agent.domain.mention

import com.aharou.feature.workspace.domain.WorkspaceSearchEngine
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `@` 引用：查询词提取（邮箱规避）、候选模糊匹配排序、文件枚举结果映射。
 */
class FileMentionTest {

    private val engine = mockk<WorkspaceSearchEngine>(relaxed = true)
    private val repository = FileMentionRepository(engine)

    // ---- fileMentionQuery ----

    @Test
    fun query_plainMention() {
        assertEquals("src", fileMentionQuery("看下 @src"))
    }

    @Test
    fun query_emptyWhenJustAt() {
        assertEquals("", fileMentionQuery("@"))
        assertEquals("", fileMentionQuery("hello @"))
    }

    @Test
    fun query_pathWithSlash() {
        assertEquals("app/build.gradle.kts", fileMentionQuery("@app/build.gradle.kts"))
    }

    @Test
    fun query_afterNewlineCountsAsBoundary() {
        assertEquals("foo", fileMentionQuery("第一行\n@foo"))
    }

    @Test
    fun query_emailIsNotMention() {
        assertNull(fileMentionQuery("a@b.com")) // @ 前是字母 → 非引用
        assertNull(fileMentionQuery("联系 me@example.com"))
    }

    @Test
    fun query_stopsAtWhitespace() {
        // @ 之后已有空白，说明引用 token 结束，光标不在词里
        assertNull(fileMentionQuery("@src main"))
        assertNull(fileMentionQuery("email a@b.com then @foo bar"))
    }

    @Test
    fun query_takesLastValidMention() {
        assertEquals("bar", fileMentionQuery("see @foo and @bar"))
        // 最后一个 @ 是邮箱一部分（前面是字母），旧的 @foo 后面有空白 → 无
        assertNull(fileMentionQuery("@foo x@y.com"))
    }

    // ---- match ----

    @Test
    fun match_ranksPrefixOverNameOverContainsOverSubsequence() {
        val files = listOf(
            "m_a_i_n.log",   // 仅子序列命中 → 最后一档
            "remainder.kt",  // 路径含子串但文件名不以查询开头 → 第三档
            "src/mains.kt",  // 文件名以查询开头 → 第二档
            "main.dart"      // 路径以查询开头 → 第一档
        )
        assertEquals(
            listOf("main.dart", "src/mains.kt", "remainder.kt", "m_a_i_n.log"),
            repository.match(files, "main")
        )
    }

    @Test
    fun match_caseInsensitive() {
        assertEquals(listOf("App/MainViewModel.kt"), repository.match(listOf("App/MainViewModel.kt"), "mainview"))
    }

    @Test
    fun match_noHitReturnsEmpty() {
        assertTrue(repository.match(listOf("a.kt", "b.kt"), "zzz").isEmpty())
    }

    @Test
    fun match_blankQueryReturnsFilesUpToLimit() {
        val files = (1..40).map { "f$it.kt" }
        val result = repository.match(files, "")
        assertEquals(30, result.size)
        assertEquals("f1.kt", result.first())
    }

    @Test
    fun match_subsequenceOnly() {
        val result = repository.match(listOf("other.kt", "aXbXc.txt"), "abc")
        assertEquals(listOf("aXbXc.txt"), result)
    }

    // ---- loadFiles ----

    @Test
    fun loadFiles_success() = runTest {
        coEvery { engine.listWorkspaceFiles() } returns listOf("a.kt", "b/c.kt")
        assertEquals(LoadResult.Success(listOf("a.kt", "b/c.kt")), repository.loadFiles("/root/workspace"))
    }

    @Test
    fun loadFiles_nullListIsUnavailable() = runTest {
        coEvery { engine.listWorkspaceFiles() } returns null
        assertEquals(LoadResult.Unavailable, repository.loadFiles("/root/workspace"))
    }

    @Test
    fun loadFiles_blankProjectIsUnavailable() = runTest {
        assertEquals(LoadResult.Unavailable, repository.loadFiles(""))
    }
}
