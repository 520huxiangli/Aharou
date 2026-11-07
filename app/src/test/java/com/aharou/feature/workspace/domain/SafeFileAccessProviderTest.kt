package com.aharou.feature.workspace.domain

import com.aharou.core.util.FileLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SafeFileAccessProviderTest {
    private val delegate = mockk<FileAccessProvider>()
    private val failure = IOException("write rejected")

    @Before fun prepare() {
        mockkObject(FileLogger)
        every { FileLogger.w(any(), any()) } returns Unit
    }
    @After fun cleanup() { unmockkObject(FileLogger) }

    @Test fun allMutationsPropagateOriginalFailure() {
        every { delegate.writeFile(any(), any(), any(), any()) } throws failure
        every { delegate.writeBytes(any(), any(), any()) } throws failure
        every { delegate.writeStream(any(), any(), any()) } throws failure
        every { delegate.delete(any()) } throws failure
        every { delegate.deleteRecursively(any()) } throws failure
        every { delegate.rename(any(), any()) } throws failure
        every { delegate.copy(any(), any(), any()) } throws failure
        every { delegate.move(any(), any(), any()) } throws failure
        every { delegate.mkdirs(any()) } throws failure
        val safe = SafeFileAccessProvider(delegate)
        val actions: List<() -> Unit> = listOf(
            { safe.writeFile("a", "text") },
            { safe.writeBytes("a", byteArrayOf(1)) },
            { safe.writeStream("a", ByteArrayInputStream(byteArrayOf(1))); Unit },
            { safe.delete("a") },
            { safe.deleteRecursively("a") },
            { safe.rename("a", "b") },
            { safe.copy("a", "b") },
            { safe.move("a", "b") },
            { safe.mkdirs("a") }
        )
        actions.forEach { action ->
            try {
                action()
                fail("Expected original mutation failure")
            } catch (error: IOException) {
                assertSame(failure, error)
            }
        }
    }

    @Test fun readsRetainExistingFallbacks() {
        every { delegate.readFile(any()) } throws failure
        every { delegate.readLines(any()) } throws failure
        every { delegate.readBytes(any()) } throws failure
        every { delegate.exists(any()) } throws failure
        every { delegate.listFiles(any()) } throws failure
        val safe = SafeFileAccessProvider(delegate)
        assertEquals("", safe.readFile("a"))
        assertTrue(safe.readLines("a").none())
        assertTrue(safe.readBytes("a").isEmpty())
        assertEquals(false, safe.exists("a"))
        assertTrue(safe.listFiles("a").isEmpty())
    }

    @Test fun workspaceBindingRetainsSafeWrapper() {
        val bound = mockk<FileAccessProvider>()
        every { delegate.forWorkspace("/a") } returns bound
        every { bound.readFile("~/workspace/a") } throws failure
        every { bound.writeFile(any(), any(), any(), any()) } throws failure
        val safe = SafeFileAccessProvider(delegate).forWorkspace("/a")
        assertTrue(safe is SafeFileAccessProvider)
        assertEquals("", safe.readFile("~/workspace/a"))
        try {
            safe.writeFile("~/workspace/a", "new")
            fail("Expected write failure")
        } catch (error: IOException) {
            assertSame(failure, error)
        }
        verify(exactly = 1) { delegate.forWorkspace("/a") }
    }

    @Test fun blankBindingKeepsOriginalWrapper() {
        val safe = SafeFileAccessProvider(delegate)
        assertSame(safe, safe.forWorkspace(" "))
    }
}
