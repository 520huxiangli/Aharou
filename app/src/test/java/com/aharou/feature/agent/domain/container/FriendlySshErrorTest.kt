package com.aharou.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSH 连接异常 → 友好提示文案的翻译逻辑（[friendlySshError]）。
 */
class FriendlySshErrorTest {

    @Test
    fun connection_refused() {
        assertEquals(
            "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确",
            friendlySshError(RuntimeException("ECONNREFUSED (Connection refused)"))
        )
        assertEquals(
            "无法连接到 SSH 服务器（连接被拒绝），请确认远程服务器已启动且端口正确",
            friendlySshError(RuntimeException("Connection refused: 10.0.0.2:22"))
        )
    }

    @Test
    fun unknown_host() {
        assertEquals(
            "无法连接到 SSH 服务器（未知主机），请检查主机地址",
            friendlySshError(RuntimeException("UnknownHostException: foo.example.com"))
        )
        assertEquals(
            "无法连接到 SSH 服务器（未知主机），请检查主机地址",
            friendlySshError(RuntimeException("unknown host foo"))
        )
    }

    @Test
    fun auth_failed() {
        assertEquals(
            "SSH 认证失败，请检查用户名和密码",
            friendlySshError(RuntimeException("Auth fail"))
        )
        assertEquals(
            "SSH 认证失败，请检查用户名和密码",
            friendlySshError(RuntimeException("authentication failed"))
        )
    }

    @Test
    fun network_unreachable() {
        assertEquals(
            "网络不可达，请检查网络连接",
            friendlySshError(RuntimeException("Network is unreachable"))
        )
    }

    @Test
    fun timeout() {
        assertEquals(
            "连接 SSH 服务器超时，请检查网络或服务器状态",
            friendlySshError(RuntimeException("SocketTimeoutException: connect timed out"))
        )
        assertEquals(
            "连接 SSH 服务器超时，请检查网络或服务器状态",
            friendlySshError(RuntimeException("timed out"))
        )
    }

    @Test
    fun not_connected() {
        assertEquals(
            "SSH 未连接，请等待连接恢复或在设置中检查配置",
            friendlySshError(RuntimeException("SSH 未连接"))
        )
    }

    @Test
    fun unknown_error_falls_back() {
        assertEquals("SSH 连接失败: boom", friendlySshError(RuntimeException("boom")))
        assertEquals("SSH 连接失败: 未知错误", friendlySshError(RuntimeException()))
    }
}
