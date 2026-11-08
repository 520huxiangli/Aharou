package com.aharou.feature.settings.presentation

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ContainerSourcesDefaultsTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `界面与设置对象初始默认均为国内`() {
        val ui = ContainerSourcesUiState()
        val settings = ContainerSourcesSettings()
        assertEquals(ContainerMirrorMode.CHINA, ui.mirror)
        assertEquals(ContainerDnsMode.ALI_DNSPOD, ui.dns)
        assertEquals(settings.mirror, ui.mirror)
        assertEquals(settings.dns, ui.dns)
        assertEquals("", settings.mirrorHost)
        assertFalse(ui.loaded)
    }

    @Test
    fun `空配置读取国内默认但已保存的全部合法选择保持原样`() = runBlocking {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir(): File = folder.root
        }
        val repository = ContainerSourcesRepository(context)
        assertEquals(ContainerSourcesSettings(ContainerMirrorMode.CHINA, "", ContainerDnsMode.ALI_DNSPOD), repository.read())

        for (mirror in ContainerMirrorMode.entries) {
            for (dns in ContainerDnsMode.entries) {
                val saved = ContainerSourcesSettings(mirror, "mirrors.huaweicloud.com", dns)
                repository.save(saved)
                assertEquals(saved, repository.read())
                assertEquals(saved, ContainerSourcesRepository(context).read())
            }
        }
    }
}
