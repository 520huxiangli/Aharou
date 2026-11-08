package com.aharou.core.util

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DocumentOutputTest {
    private val resolver = mockk<ContentResolver>()
    private val uri = Uri.parse("content://test.documents/export")

    private class TrackedOutput(private val failOnClose: Boolean = false) : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            if (failOnClose) throw IOException("close failed")
            super.close()
        }
    }

    @Test
    fun `正常打开的流交给调用方而不提前关闭`() = runBlocking {
        val output = TrackedOutput()
        every { resolver.openOutputStream(uri) } returns output

        assertSame(output, resolver.openDocumentOutput(uri))
        assertFalse(output.closed)
        verify(exactly = 1) { resolver.openOutputStream(uri) }
        output.close()
    }

    @Test
    fun `文件授权失效时返回失败而不是抛出权限异常`() = runBlocking {
        every { resolver.openOutputStream(uri) } throws SecurityException("URI grant revoked")
        assertNull(resolver.openDocumentOutput(uri))
    }

    @Test
    fun `目标不存在或提供方读写失败时返回失败`() = runBlocking {
        for (failure in listOf(FileNotFoundException("missing"), IOException("provider failed"))) {
            every { resolver.openOutputStream(uri) } throws failure
            assertNull(resolver.openDocumentOutput(uri))
        }
    }

    @Test
    fun `提供方返回空流时保留失败语义`() = runBlocking {
        every { resolver.openOutputStream(uri) } returns null
        assertNull(resolver.openDocumentOutput(uri))
    }

    @Test
    fun `打开过程取消时不吞掉取消异常`() {
        every { resolver.openOutputStream(uri) } throws CancellationException("opening cancelled")

        val failure = assertThrows(CancellationException::class.java) {
            runBlocking { resolver.openDocumentOutput(uri) }
        }

        assertEquals("opening cancelled", failure.message)
    }

    @Test
    fun `流打开后取消会关闭未交付的流且关闭失败不覆盖取消`() {
        for (failOnClose in listOf(false, true)) {
            val output = TrackedOutput(failOnClose)
            var delivered = false
            val failure = assertThrows(CancellationException::class.java) {
                runBlocking {
                    val owner = coroutineContext.job
                    every { resolver.openOutputStream(uri) } answers {
                        owner.cancel(CancellationException("delivery cancelled"))
                        output
                    }
                    resolver.openDocumentOutput(uri)
                    delivered = true
                }
            }
            assertEquals("delivery cancelled", failure.message)
            assertFalse(delivered)
            assertTrue(output.closed)
        }
    }
}
