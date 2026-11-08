package com.aharou.feature.im.domain.napcat

import com.aharou.feature.im.data.repository.NapCatConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NapCatLoginRecoveryTest {
    @Test
    fun `默认状态未知且不冒充在线`() {
        val state = NapCatLoginState()
        assertEquals("unknown", state.status)
        assertFalse(state.loggedIn)
    }

    @Test
    fun `历史账号不能使消息桥就绪`() {
        val config = NapCatConfig(lastAccountId = "12345678", token = "local-test-token")
        assertFalse(config.ready)
    }

    @Test
    fun `不可用与停止状态可以保留历史账号而不在线`() {
        listOf("checking", "logged_out", "unavailable", "stopped").forEach { status ->
            val state = NapCatLoginState(accountId = "12345678", status = status)
            assertEquals("12345678", state.accountId)
            assertFalse(state.loggedIn)
        }
    }

    @Test
    fun `官方字符串账号列表仅唯一账号可供自动恢复`() {
        assertEquals("12345678", NapCatLoginState.recoveryAccounts(listOf("12345678")).singleOrNull())
        assertEquals(null, NapCatLoginState.recoveryAccounts(listOf("12345678", "87654321")).singleOrNull())
        assertEquals(null, NapCatLoginState.recoveryAccounts(emptyList()).singleOrNull())
    }

    @Test
    fun `重复历史记录不造成多个身份`() {
        assertEquals(listOf("12345678"), NapCatLoginState.recoveryAccounts(listOf("12345678", "12345678")))
    }

    @Test
    fun `拒绝哈希与非ASCII账号而不猜测身份`() {
        listOf("abcdef0123456789", "１２３４５６", "1234", "1".repeat(21)).forEach { account ->
            assertThrows(IllegalArgumentException::class.java) {
                NapCatLoginState.recoveryAccounts(listOf(account))
            }
        }
    }
}
