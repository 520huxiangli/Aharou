package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.RemoteConnectionConfig
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.agent.domain.container.SftpConnectionSnapshot
import com.aharou.feature.agent.domain.container.SshHostKeyStore
import com.aharou.feature.agent.domain.container.SshHostKeyVerifier
import com.aharou.feature.agent.domain.container.SshPrivateKeyStore
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.workspace.domain.remote.RemoteAuth
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteSftpFileAccessTest {
    private val connection = spyk(RemoteSshConnection(
        mockk<SshHostKeyStore>(), mockk<SshHostKeyVerifier>(),
        mockk<SshPrivateKeyStore>(), mockk<ContainerInstaller>()
    ))
    private val workspace = mockk<WorkspaceRepository>()
    private val sftp = mockk<SFTPClient>(relaxed = true)
    private val config = RemoteConnectionConfig("example.invalid", username = "test", auth = RemoteAuth.Password("test"), remoteWorkspacePath = "/remote")

    private fun publish(config: RemoteConnectionConfig, home: String?) {
        RemoteSshConnection::class.java.getDeclaredField("config").apply { isAccessible = true }.set(connection, config)
        RemoteSshConnection::class.java.getDeclaredField("fileAccessSnapshot").apply { isAccessible = true }
            .set(connection, SftpConnectionSnapshot(config, home))
    }

    private fun access(home: String? = "/home/test"): RemoteSftpFileAccess {
        publish(config, home)
        every { workspace.currentPath() } returns "/remote/global"
        every { sftp.statExistence(any()) } returns null
        coEvery { connection.sftp() } returns sftp
        coEvery { connection.invalidateSftp() } just Runs
        return RemoteSftpFileAccess(connection, workspace)
    }

    @Test fun boundWorkspaceIgnoresGlobalSelectionForPathsAndDisplay() {
        val root = access()
        val a = root.forWorkspace("/remote/a")
        val b = root.forWorkspace("/remote/b")
        every { workspace.currentPath() } returns "/remote/changed"
        a.exists("~/workspace/file")
        b.exists("~/workspace/file")
        verify { sftp.statExistence("/remote/a/file") }
        verify { sftp.statExistence("/remote/b/file") }
        assertEquals("~/workspace/file", a.toDisplayPath("/remote/a/file"))
        assertEquals("~/workspace", a.parentPath("~/workspace/file"))
    }

    @Test fun sameConnectionCanFillHomeAfterBinding() {
        val a = access(home = null).forWorkspace("/remote/a")
        publish(config, "/home/test")
        a.exists("~/other")
        verify { sftp.statExistence("/home/test/other") }
    }

    @Test fun changedConnectionFailsBeforeAcquiringNewSftpClient() {
        val a = access().forWorkspace("/remote/a")
        publish(config.copy(host = "different.invalid"), "/different/home")
        try {
            a.writeFile("~/workspace/file", "new")
            fail("Expected changed connection failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("connection changed"))
        }
        coVerify(exactly = 0) { connection.sftp() }
    }

    @Test fun unconfiguredRemoteLegCanBeBoundWithoutNetwork() {
        val a = RemoteSftpFileAccess(connection, workspace).forWorkspace("/local/a")
        assertEquals("~/workspace/file", a.toDisplayPath("/local/a/file"))
        coVerify(exactly = 0) { connection.sftp() }
        try {
            a.writeFile("~/workspace/file", "new")
            fail("Expected unconfigured connection failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("rebind"))
        }
    }

    @Test fun bindingDoesNotWaitForInFlightSftpOperation() = runBlocking {
        val root = access()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) {
            connection.withSftpOperation(config) { entered.complete(Unit); release.await() }
        }
        entered.await()
        try {
            val bound = root.forWorkspace("/remote/a")
            assertEquals("~/workspace", bound.toDisplayPath("/remote/a"))
        } finally {
            release.complete(Unit)
            operation.await()
        }
    }

    @Test fun connectWaitsForCurrentFileOperationBeforeChangingConfiguration() = runBlocking {
        access()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) {
            connection.withSftpOperation(config) { entered.complete(Unit); release.await() }
        }
        entered.await()
        val change = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { connection.connect(config.copy(auth = RemoteAuth.Password(""))) }
        }
        try {
            assertFalse(change.isCompleted)
            assertEquals(config, connection.config)
        } finally {
            release.complete(Unit)
            operation.await()
        }
        assertTrue(change.await().isFailure)
        assertEquals(config, connection.config)
    }

    @Test fun boundCopiesShareConnectionOperationLock() {
        val root = access()
        val a = root.forWorkspace("/remote/a")
        val b = root.forWorkspace("/remote/b")
        val firstEntered = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        every { sftp.statExistence("/remote/a/file") } answers {
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
            null
        }
        every { sftp.statExistence("/remote/b/file") } answers { secondEntered.countDown(); null }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { a.exists("~/workspace/file") }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { secondStarted.countDown(); b.exists("~/workspace/file") }
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals(0L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
    }

    @Test fun writesUseDistinctExclusiveTemporaryFiles() {
        val a = access().forWorkspace("/remote/a")
        val opened = mutableListOf<String>()
        val modes = mutableListOf<Set<OpenMode>>()
        val file = mockk<RemoteFile>(relaxed = true)
        every { sftp.open(capture(opened), capture(modes)) } returns file
        a.writeBytes("~/workspace/file", byteArrayOf(1))
        a.writeBytes("~/workspace/file", byteArrayOf(2))
        assertEquals(2, opened.distinct().size)
        assertTrue(opened.all { it.startsWith("/remote/a/file.aicode-part-") })
        assertTrue(modes.all { OpenMode.EXCL in it && OpenMode.TRUNC !in it })
        verify(exactly = 0) { sftp.rm("/remote/a/file") }
    }

    @Test fun uploadFailureCleansOnlyOwnedTemporaryFile() {
        val a = access().forWorkspace("/remote/a")
        val opened = mutableListOf<String>()
        every { sftp.open(capture(opened), any()) } returns mockk<RemoteFile>(relaxed = true)
        val input = object : InputStream() { override fun read(): Int = throw IOException("input failed") }
        try {
            a.writeStream("~/workspace/file", input)
            fail("Expected upload failure")
        } catch (_: IOException) {
            verify { sftp.rm(opened.single()) }
            verify(exactly = 0) { sftp.rm("/remote/a/file") }
            verify(exactly = 0) { sftp.rename(any(), any()) }
        }
    }
}
