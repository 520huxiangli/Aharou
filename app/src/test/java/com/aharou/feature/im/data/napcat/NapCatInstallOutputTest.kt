package com.aharou.feature.im.data.napcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NapCatInstallOutputTest {
    @Test
    fun `普通安装输出逐条即时上报不等待命令结束`() {
        val received = mutableListOf<String>()
        val output = NapCatInstallOutput(received::add)
        output.append("Reading package lists...")
        assertEquals(listOf("Reading package lists..."), received)
        output.append("Setting up libgtk-3-0t64")
        assertEquals(2, received.size)
        assertTrue(output.text().contains("Setting up libgtk-3-0t64"))
    }

    @Test
    fun `带凭据和地址的日志保留事件但不泄露原值`() {
        val received = mutableListOf<String>()
        val output = NapCatInstallOutput(received::add)
        output.append("fetch https://example.invalid/file?token=test-value password=private-value failed")
        assertEquals(1, received.size)
        assertTrue(received.single().contains("fetch"))
        assertTrue(received.single().contains("failed"))
        listOf("example.invalid", "test-value", "private-value").forEach {
            assertFalse(received.single().contains(it))
            assertFalse(output.text().contains(it))
        }
    }

    @Test
    fun `去除终端控制码并忽略空行`() {
        val received = mutableListOf<String>()
        val output = NapCatInstallOutput(received::add)
        output.append("\u001B[31mUnpacking\u001B[0m")
        output.append("   ")
        assertEquals(listOf("Unpacking"), received)
    }

    @Test
    fun `内存尾部只保留最近二十条且单条限长`() {
        val received = mutableListOf<String>()
        val output = NapCatInstallOutput(received::add)
        repeat(25) { output.append("line-$it-end") }
        assertEquals(25, received.size)
        assertFalse(output.text().contains("line-4-end"))
        assertTrue(output.text().contains("line-5-end"))
        output.append("x".repeat(1000))
        assertEquals(600, received.last().length)
        assertTrue(output.text().length <= 1600)
    }
}
