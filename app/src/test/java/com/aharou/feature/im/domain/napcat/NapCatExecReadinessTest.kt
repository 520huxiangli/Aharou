package com.aharou.feature.im.domain.napcat

import android.app.Application
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.LinuxContainerEngine
import com.aharou.feature.agent.domain.container.TrackedStdioProcess
import com.aharou.feature.im.data.napcat.NapCatPackageInstaller
import io.mockk.Called
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NapCatExecReadinessTest {
    private val installer = mockk<NapCatPackageInstaller>()
    private val engine = mockk<LinuxContainerEngine>()
    private val profile = mockk<ContainerProfile>()
    private val command = "cat /etc/resolv.conf"

    private fun runtime(version: String, ready: Boolean): NapCatRuntimeManager {
        every { installer.installedVersion() } returns version
        every { installer.isEnvironmentReady() } returns ready
        every { installer.profile() } returns profile
        return NapCatRuntimeManager(installer, engine, mockk(), mockk(), mockk())
    }

    private fun prepareProcess() {
        val process = mockk<Process>()
        every { process.inputStream } returns ByteArrayInputStream("nameserver 223.5.5.5\n".toByteArray())
        every { process.errorStream } returns ByteArrayInputStream(byteArrayOf())
        every { process.outputStream } returns ByteArrayOutputStream()
        every { process.isAlive } returns false
        every { process.waitFor() } returns 0
        every { process.exitValue() } returns 0
        every { process.destroyForcibly() } returns process
        every {
            engine.startTrackedStdioProcess(
                "/bin/bash", listOf("-c", command), null, "napcat:exec",
                profile = profile, inheritUserConfig = false
            )
        } returns TrackedStdioProcess(process, null) {}
    }

    @Test
    fun `Debian已就绪但NapCat未装完仍可执行诊断`() = runBlocking {
        val runtime = runtime("", true)
        prepareProcess()

        assertEquals("nameserver 223.5.5.5\n", runtime.exec(command))
        verify(exactly = 1) {
            engine.startTrackedStdioProcess(
                "/bin/bash", listOf("-c", command), null, "napcat:exec",
                profile = profile, inheritUserConfig = false
            )
        }
        assertEquals("not_installed", runtime.state.value.phase)
        assertEquals("", runtime.state.value.version)
    }

    @Test
    fun `完整安装后的诊断行为不变`() = runBlocking {
        val runtime = runtime("4.18.42", true)
        prepareProcess()

        assertEquals("nameserver 223.5.5.5\n", runtime.exec(command))
        assertEquals("installed", runtime.state.value.phase)
    }

    @Test
    fun `Debian缺失时无论是否残留程序版本都不启动进程`() {
        for (version in listOf("", "4.18.42")) {
            val runtime = runtime(version, false)
            val failure = assertThrows(IllegalStateException::class.java) {
                runBlocking { runtime.exec(command) }
            }
            assertTrue(failure.message.orEmpty().contains("Debian environment is not ready"))
        }
        verify { engine wasNot Called }
    }
}
