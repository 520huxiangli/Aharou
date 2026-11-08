package com.aharou.feature.im.data.napcat

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NapCatCommandOutputTest {
    @Test
    fun `主进程已退出且管道未EOF时不再阻塞读取`() = runTest {
        val stream = object : InputStream() {
            override fun available(): Int = 0
            override fun read(): Int = error("不应等待管道EOF")
        }
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { false }, lines::add)
        assertTrue(lines.isEmpty())
    }

    @Test
    fun `进程还活着但无输出时取消可结束读取`() = runTest {
        val stream = object : InputStream() {
            override fun available(): Int = 0
            override fun read(): Int = error("不应执行阻塞读")
        }
        val reader = launch { readNapCatCommandOutput(stream, { true }) {} }
        runCurrent()
        assertTrue(reader.isActive)
        reader.cancelAndJoin()
        assertTrue(reader.isCancelled)
    }

    @Test
    fun `退出后排空现有输出并保留末尾未换行内容`() = runTest {
        val stream = ByteArrayInputStream("正在安装\r进度更新\n完成".toByteArray())
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { false }, lines::add)
        assertEquals(listOf("正在安装", "进度更新", "完成"), lines)
    }

    @Test
    fun `跨读取边界的中文字符不会被截断`() = runTest {
        val source = "阶段一\n安装依赖".toByteArray()
        val stream = object : ByteArrayInputStream(source) {
            override fun available(): Int = minOf(1, super.available())
        }
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { false }, lines::add)
        assertEquals(listOf("阶段一", "安装依赖"), lines)
    }

    @Test
    fun `检查进程退出时刚到达的尾输出仍会被排空`() = runTest {
        var ready = false
        val stream = object : ByteArrayInputStream("last line\n".toByteArray()) {
            override fun available(): Int = if (ready) super.available() else 0
        }
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { ready = true; false }, lines::add)
        assertEquals(listOf("last line"), lines)
    }

    @Test
    fun `长中文行截断不产生半个字符`() = runTest {
        val stream = ByteArrayInputStream(("中".repeat(2000) + "\nnext\n").toByteArray())
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { false }, lines::add)
        assertEquals("中".repeat(1365), lines.first())
        assertEquals("next", lines.last())
    }

    @Test
    fun `没有换行的长输出有界并继续接收下一行`() = runTest {
        val stream = ByteArrayInputStream(("x".repeat(20000) + "\nnext\n").toByteArray())
        val lines = mutableListOf<String>()
        readNapCatCommandOutput(stream, { false }, lines::add)
        assertEquals(4096, lines.first().length)
        assertEquals("next", lines.last())
        assertFalse(lines.first().contains("next"))
    }
}
