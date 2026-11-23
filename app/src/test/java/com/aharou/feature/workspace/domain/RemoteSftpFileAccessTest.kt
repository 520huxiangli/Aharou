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
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
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
    private val lease = mockk<RemoteSshConnection.SftpLease>(relaxed = true) {
        every { client } returns sftp
    }
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
        coEvery { connection.acquireSftp() } returns lease
        coEvery { connection.invalidateSftpChannel(any()) } just Runs
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

    @Test fun changedConnectionFailsBeforeAcquiringNewChannel() {
        val a = access().forWorkspace("/remote/a")
        publish(config.copy(host = "different.invalid"), "/different/home")
        try {
            a.writeFile("~/workspace/file", "new")
            fail("Expected changed connection failure")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("connection changed"))
        }
        coVerify(exactly = 0) { connection.acquireSftp() }
    }

    @Test fun unconfiguredRemoteLegCanBeBoundWithoutNetwork() {
        val a = RemoteSftpFileAccess(connection, workspace).forWorkspace("/local/a")
        assertEquals("~/workspace/file", a.toDisplayPath("/local/a/file"))
        coVerify(exactly = 0) { connection.acquireSftp() }
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

    @Test fun connectNoLongerWaitsForInFlightFileOperation() = runBlocking {
        access()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operation = async(Dispatchers.IO) {
            connection.withSftpOperation(config) { entered.complete(Unit); release.await() }
        }
        entered.await()
        // 通道改为租约分配后不再有「让切连接排队」的全局锁：在途操作由连接身份校验
        // 与断开时清空整个池兜底，切连接本身不会被文件操作挡住。
        val change = try {
            withTimeout(5_000) {
                runCatching { connection.connect(config.copy(auth = RemoteAuth.Password(""))) }
            }
        } finally {
            release.complete(Unit)
        }
        operation.await()
        assertTrue(change.isFailure)
        assertEquals(config, connection.config)
    }

    @Test fun boundCopiesRunConcurrentlyOnSeparateChannels() {
        val root = access()
        val a = root.forWorkspace("/remote/a")
        val b = root.forWorkspace("/remote/b")
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        // 每条通道各自的 client：第二条能同时进入才说明池是按租约并行分配，而不是共用一条串行
        val clientA = mockk<SFTPClient>(relaxed = true)
        val clientB = mockk<SFTPClient>(relaxed = true)
        every { clientA.statExistence("/remote/a/file") } answers {
            firstEntered.countDown()
            check(releaseFirst.await(5, TimeUnit.SECONDS))
            null
        }
        every { clientB.statExistence("/remote/b/file") } answers { secondEntered.countDown(); null }
        val leaseA = mockk<RemoteSshConnection.SftpLease>(relaxed = true) { every { client } returns clientA }
        val leaseB = mockk<RemoteSshConnection.SftpLease>(relaxed = true) { every { client } returns clientB }
        coEvery { connection.acquireSftp() } returnsMany listOf(leaseA, leaseB)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> { a.exists("~/workspace/file") }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val second = pool.submit<Boolean> { b.exists("~/workspace/file") }
            assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            assertFalse(first.get(5, TimeUnit.SECONDS))
            assertFalse(second.get(5, TimeUnit.SECONDS))
        } finally {
            releaseFirst.countDown()
            pool.shutdownNow()
        }
        coVerify(exactly = 2) { connection.acquireSftp() }
        // 两条通道用完都还回池里，不占名额
        coVerify { leaseA.release() }
        coVerify { leaseB.release() }
    }

    @Test fun readLinesToleratesPartialRemoteReads() {
        val a = access().forWorkspace("/remote/a")
        val content = "alpha\nbeta\ngamma".toByteArray()
        every { sftp.statExistence("/remote/a/file") } returns FileAttributes.Builder()
            .withType(FileMode.Type.REGULAR).withSize(content.size.toLong()).build()
        val file = mockk<RemoteFile>(relaxed = true)
        // 每次只吐 3 字节：分块读取必须自己拼满，否则行会在半路被截断
        every { file.read(any(), any(), any(), any()) } answers {
            val offset = firstArg<Long>().toInt()
            val buf = secondArg<ByteArray>()
            val off = thirdArg<Int>()
            val len = minOf(arg<Int>(3), 3, content.size - offset)
            if (offset >= content.size) {
                -1
            } else {
                System.arraycopy(content, offset, buf, off, len)
                len
            }
        }
        every { sftp.open("/remote/a/file") } returns file
        assertEquals(listOf("alpha", "beta", "gamma"), a.readLines("~/workspace/file").toList())
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
