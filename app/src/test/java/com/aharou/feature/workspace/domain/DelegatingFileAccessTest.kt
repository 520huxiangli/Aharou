package com.aharou.feature.workspace.domain

import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.ContainerProfileResolver
import com.aharou.feature.agent.domain.container.RemoteSshConnection
import com.aharou.feature.workspace.data.repository.WorkspaceRepository
import com.aharou.feature.settings.data.repository.ExecutionMode
import com.aharou.feature.settings.data.repository.ExecutionModeHolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegatingFileAccessTest {
    private val mode = ExecutionModeHolder()
    private val resolver = mockk<ContainerProfileResolver>()
    private val local = mockk<LocalFileAccess>()
    private val remote = mockk<RemoteSftpFileAccess>()

    @Test fun remoteWorkspaceBindsBothLegsAndKeepsSafeWrapper() {
        val boundLocal = mockk<FileAccessProvider>()
        val boundRemote = mockk<FileAccessProvider>()
        every { local.forWorkspace("/a") } returns boundLocal
        every { remote.forWorkspace("/a") } returns boundRemote
        every { resolver.forWorkspace("/a") } returns ContainerProfile.BUILTIN_ALPINE.copy(mode = ExecutionMode.REMOTE_SSH)
        every { boundRemote.readFile("~/workspace/file") } returns "workspace a"
        val access = DelegatingFileAccess(mode, resolver, local, remote).forWorkspace("/a")
        mode.setMode(ExecutionMode.LOCAL_PROOT)
        every { resolver.forWorkspace("/a") } returns ContainerProfile.BUILTIN_ALPINE
        assertEquals("workspace a", access.readFile("~/workspace/file"))
        verify(exactly = 1) { local.forWorkspace("/a") }
        verify(exactly = 1) { remote.forWorkspace("/a") }
        val remoteLeg = DelegatingFileAccess::class.java.getDeclaredField("remoteLeg").apply { isAccessible = true }
        assertTrue(remoteLeg.get(access) is SafeFileAccessProvider)
    }

    @Test fun localWorkspaceStillUsesBoundLocalLeg() {
        val boundLocal = mockk<FileAccessProvider>()
        every { local.forWorkspace("/a") } returns boundLocal
        every { remote.forWorkspace("/a") } returns mockk<FileAccessProvider>()
        every { resolver.forWorkspace("/a") } returns ContainerProfile.BUILTIN_ALPINE
        every { boundLocal.readFile("~/workspace/file") } returns "local a"
        mode.setMode(ExecutionMode.REMOTE_SSH)
        val access = DelegatingFileAccess(mode, resolver, local, remote).forWorkspace("/a")
        every { resolver.forWorkspace("/a") } returns ContainerProfile.BUILTIN_ALPINE.copy(mode = ExecutionMode.REMOTE_SSH)
        assertEquals("local a", access.readFile("~/workspace/file"))
    }

    @Test fun localBindingDoesNotRequireSshConfiguration() {
        val connection = RemoteSshConnection(mockk(), mockk(), mockk(), mockk())
        val remoteWithoutConfiguration = RemoteSftpFileAccess(connection, mockk<WorkspaceRepository>())
        val boundLocal = mockk<FileAccessProvider>()
        every { local.forWorkspace("/local/a") } returns boundLocal
        every { boundLocal.readFile("~/workspace/file") } returns "local only"
        every { resolver.forWorkspace("/local/a") } returns ContainerProfile.BUILTIN_ALPINE
        val access = DelegatingFileAccess(mode, resolver, local, remoteWithoutConfiguration).forWorkspace("/local/a")
        assertEquals("local only", access.readFile("~/workspace/file"))
    }

    @Test fun blankWorkspaceKeepsUnboundAccessor() {
        val access = DelegatingFileAccess(mode, resolver, local, remote)
        assertSame(access, access.forWorkspace(" "))
    }
}
