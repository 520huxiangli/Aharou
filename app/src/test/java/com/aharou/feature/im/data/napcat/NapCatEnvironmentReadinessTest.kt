package com.aharou.feature.im.data.napcat

import android.app.Application
import android.content.Context
import com.aharou.feature.agent.domain.container.ContainerInstaller
import com.aharou.feature.agent.domain.container.ContainerProfile
import com.aharou.feature.agent.domain.container.RootfsSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NapCatEnvironmentReadinessTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `只检查专用Debian不依赖NapCat程序清单也不触发安装`() {
        val context = mockk<Context>()
        every { context.filesDir } returns temporary.root
        val container = mockk<ContainerInstaller>()
        val profile = slot<ContainerProfile>()
        every { container.isInstalledFor(capture(profile)) } returnsMany listOf(false, true)
        val installer = NapCatPackageInstaller(context, mockk(), container, mockk(), mockk(), mockk(), mockk())

        assertFalse(installer.isEnvironmentReady())
        assertTrue(installer.isEnvironmentReady())
        assertEquals("napcat-debian", profile.captured.id)
        assertFalse(profile.captured.isBuiltin)
        assertTrue(profile.captured.rootfsSource is RootfsSource.LocalFile)
        assertEquals("", installer.installedVersion())
        assertFalse(File(temporary.root, "aharou-global/napcat/installed.json").exists())
        verify(exactly = 2) { container.isInstalledFor(any()) }
        io.mockk.confirmVerified(container)
    }
}
