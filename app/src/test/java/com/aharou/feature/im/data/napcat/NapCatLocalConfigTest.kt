package com.aharou.feature.im.data.napcat

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json 在纯 JVM 单测里是桩实现（optJSONObject 恒为 null），必须跑在 Robolectric 下。
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class NapCatLocalConfigTest {
    @Test
    fun `登录后自动生成的空配置判定为不可用`() {
        val empty = JSONObject("""{"network":{"httpServers":[],"websocketServers":[],"httpClients":[],"websocketClients":[]}}""")
        assertFalse(NapCatLocalConfig.hasEnabledServer(empty))
    }

    @Test
    fun `模板里的 HTTP 与 WebSocket 服务端判定为可用`() {
        val template = JSONObject(
            """{"network":{"httpServers":[{"name":"aharou-http","enable":true,"port":3001}],""" +
                """"websocketServers":[{"name":"aharou-ws","enable":true,"port":3002}]}}"""
        )
        assertTrue(NapCatLocalConfig.hasEnabledServer(template))
    }

    @Test
    fun `只认启用状态的服务端`() {
        val disabled = JSONObject("""{"network":{"httpServers":[{"name":"aharou-http","enable":false}]}}""")
        assertFalse(NapCatLocalConfig.hasEnabledServer(disabled))
    }

    @Test
    fun `缺少 network 或服务端数组时判定为不可用`() {
        assertFalse(NapCatLocalConfig.hasEnabledServer(JSONObject("{}")))
        assertFalse(NapCatLocalConfig.hasEnabledServer(JSONObject("""{"network":{}}""")))
    }
}
