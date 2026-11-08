package com.aharou.feature.agent.domain.container

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ContainerDoctorProfileTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val engine = mockk<LinuxContainerEngine>()
    private val doctor = ContainerDoctor(engine, mockk<RuntimeProcessStore>(), mockk<Context>())
    private val napcatProfile = ContainerProfile(
        id = "napcat",
        name = "NapCat",
        rootfsSource = RootfsSource.LocalFile("file:///napcat-rootfs.tar.gz"),
        shellPath = "/bin/sh",
        isBuiltin = false,
    )

    @Test
    fun `NapCat安装探针使用传入配置而非默认容器`() = runTest {
        val rootfs = completeRootfs()
        every { engine.startProotProcess("true", null, napcatProfile) } returns exitedProcess(0)

        val verdict = doctor.verifyRootfs(rootfs, napcatProfile)

        assertEquals(RootfsVerdict.Ready, verdict)
        verify(exactly = 1) { engine.startProotProcess("true", null, napcatProfile) }
        verify(exactly = 0) { engine.startProotProcess(any(), any(), null) }
        verify(exactly = 0) { engine.startProotProcess(any(), any(), ContainerProfile.BUILTIN_ALPINE) }
    }

    @Test
    fun `NapCat探针失败时不以默认容器成功冒充就绪`() = runTest {
        val rootfs = completeRootfs()
        every { engine.startProotProcess("true", null, napcatProfile) } returns exitedProcess(9)
        every { engine.startProotProcess("true", null, null) } returns exitedProcess(0)
        every { engine.startProotProcess("true", null, ContainerProfile.BUILTIN_ALPINE) } returns exitedProcess(0)

        val verdict = doctor.verifyRootfs(rootfs, napcatProfile)

        assertEquals(RootfsVerdict.BootFailed("exit=9"), verdict)
        verify(exactly = 1) { engine.startProotProcess("true", null, napcatProfile) }
        verify(exactly = 0) { engine.startProotProcess(any(), any(), null) }
        verify(exactly = 0) { engine.startProotProcess(any(), any(), ContainerProfile.BUILTIN_ALPINE) }
    }

    @Test
    fun `目标rootfs布局缺失时不启动任何容器`() = runTest {
        val rootfs = temporaryFolder.newFolder("rootfs_napcat")

        val verdict = doctor.verifyRootfs(rootfs, napcatProfile)

        assertEquals(RootfsVerdict.LayoutMissing(listOf("bin/sh", "etc/os-release")), verdict)
        verify(exactly = 0) { engine.startProotProcess(any(), any(), any()) }
    }

    private fun completeRootfs(): File = temporaryFolder.newFolder("rootfs_napcat").apply {
        File(this, "bin").mkdirs()
        File(this, "etc").mkdirs()
        File(this, "bin/sh").writeText("")
        File(this, "etc/os-release").writeText("ID=debian\n")
    }

    private fun exitedProcess(exitCode: Int): Process = mockk {
        every { isAlive } returns false
        every { inputStream } returns ByteArrayInputStream(byteArrayOf())
        every { waitFor() } returns exitCode
    }
}
