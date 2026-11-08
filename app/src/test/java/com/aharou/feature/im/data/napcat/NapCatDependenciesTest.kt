package com.aharou.feature.im.data.napcat

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatDependenciesTest {
    @Test
    fun `完整探测成功才允许跳过apt`() = runBlocking {
        var calls = 0
        val ready = NapCatDependencies.isReady { program, args ->
            calls++
            assertEquals("/bin/bash", program)
            assertEquals(listOf("-c", NapCatDependencies.probeScript, "napcat-dependencies"), args.take(3))
            assertEquals(NapCatDependencies.packages, args.drop(3))
        }
        assertEquals(1, calls)
        assertTrue(ready)
    }

    @Test
    fun `非零退出或进程创建失败均不能跳过apt`() = runBlocking {
        assertFalse(NapCatDependencies.isReady { _, _ -> error("dpkg-query failed") })
        assertFalse(NapCatDependencies.isReady { _, _ -> throw IOException("Cannot start probe") })
    }

    @Test
    fun `取消探测必须上抛而不是继续安装`() {
        val cancellation = CancellationException("cancelled")
        val thrown = assertThrows(CancellationException::class.java) {
            runBlocking {
                NapCatDependencies.isReady { _, _ -> throw cancellation }
            }
        }
        assertSame(cancellation, thrown)
    }

    @Test
    fun `探测全部25个包的ii状态和运行命令而不是marker`() {
        assertEquals(25, NapCatDependencies.packages.size)
        assertEquals(25, NapCatDependencies.packages.distinct().size)
        assertTrue(NapCatDependencies.packages.containsAll(listOf("xvfb", "xauth", "dpkg", "fonts-noto-cjk")))
        val script = NapCatDependencies.probeScript
        assertTrue(script.contains("/usr/bin/dpkg-query -W"))
        assertTrue(script.contains("\${db:Status-Abbrev}"))
        assertTrue(script.contains("[ \"\$status\" = 'ii ' ] || exit 1"))
        assertTrue(script.contains("[ -x \"\$executable\" ] || exit 1"))
        listOf("/usr/bin/Xvfb", "/usr/bin/xvfb-run", "/usr/bin/xauth", "/usr/bin/dpkg-deb", "/usr/bin/tar", "/usr/bin/unzip")
            .forEach { assertTrue(script.contains(it)) }
        assertFalse(script.contains("preinstalled.json"))
    }
}
