package com.aharou.feature.terminal.domain.font

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class TerminalFontManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()
    private val context = mockk<Context>()
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://test.documents/font.ttf")

    @Before
    fun setUp() {
        every { context.filesDir } returns tempFolder.root
        every { context.contentResolver } returns resolver
        every { resolver.query(uri, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("demo.ttf")) }
        }
        mockkStatic(Typeface::class)
        every { Typeface.createFromFile(any<File>()) } returns mockk()
    }

    @After
    fun tearDown() {
        unmockkStatic(Typeface::class)
    }

    private fun existingFont(): File = File(TerminalFontManager.fontsDir(context), "demo.ttf").apply {
        writeText("original font")
    }

    private fun assertOriginalPreserved(file: File) {
        assertEquals("original font", file.readText())
        assertEquals(listOf("demo.ttf"), file.parentFile!!.listFiles()!!.map { it.name })
    }

    @Test
    fun `文件名查询权限失效返回失败且不删除已有字体`() {
        val original = existingFont()
        every { resolver.query(uri, any(), null, null, null) } throws SecurityException("query denied")

        assertNull(TerminalFontManager.importFont(context, uri))
        assertOriginalPreserved(original)
        verify(exactly = 0) { resolver.openInputStream(any()) }
    }

    @Test
    fun `打开输入流权限失效返回失败且不删除已有字体`() {
        val original = existingFont()
        every { resolver.openInputStream(uri) } throws SecurityException("read denied")

        assertNull(TerminalFontManager.importFont(context, uri))
        assertOriginalPreserved(original)
    }

    @Test
    fun `提供方返回空输入流时保留已有字体`() {
        val original = existingFont()
        every { resolver.openInputStream(uri) } returns null

        assertNull(TerminalFontManager.importFont(context, uri))
        assertOriginalPreserved(original)
    }

    @Test
    fun `复制中途失败清理临时文件并保留已有字体`() {
        val original = existingFont()
        var closed = false
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            private var emitted = false
            override fun read(): Int = throw IOException("read failed")
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (emitted) throw IOException("read failed after partial data")
                emitted = true
                buffer[offset] = 1
                return 1
            }
            override fun close() { closed = true }
        }

        assertNull(TerminalFontManager.importFont(context, uri))
        assertOriginalPreserved(original)
        assertTrue(closed)
    }

    @Test
    fun `字体校验失败不替换已有字体`() {
        val original = existingFont()
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream("invalid font".toByteArray())
        every { Typeface.createFromFile(any<File>()) } throws RuntimeException("invalid font")

        assertNull(TerminalFontManager.importFont(context, uri))
        assertOriginalPreserved(original)
    }

    @Test
    fun `有效字体完成校验后替换并清除临时文件`() {
        val original = existingFont()
        every { resolver.openInputStream(uri) } returns ByteArrayInputStream("new font".toByteArray())

        val imported = TerminalFontManager.importFont(context, uri)

        assertEquals(TerminalFont(original.absolutePath, "demo"), imported)
        assertEquals("new font", original.readText())
        assertEquals(listOf("demo.ttf"), original.parentFile!!.listFiles()!!.map { it.name })
        verify(exactly = 1) { Typeface.createFromFile(any<File>()) }
    }

    @Test
    fun `取消导入时清理临时文件保留旧字体并传播取消`() {
        val original = existingFont()
        every { resolver.openInputStream(uri) } returns object : InputStream() {
            override fun read(): Int = throw CancellationException("import cancelled")
        }

        assertThrows(CancellationException::class.java) { TerminalFontManager.importFont(context, uri) }
        assertOriginalPreserved(original)
    }

    @Test
    fun `不支持的扩展名不读取文件也不创建字体目录`() {
        every { resolver.query(uri, any(), null, null, null) } answers {
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME)).apply { addRow(arrayOf("demo.txt")) }
        }

        assertNull(TerminalFontManager.importFont(context, uri))
        assertFalse(File(tempFolder.root, "terminal_fonts").exists())
        verify(exactly = 0) { resolver.openInputStream(any()) }
    }
}
