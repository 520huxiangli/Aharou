package com.aharou.feature.im.data.napcat

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class NapCatLocalConnection(val httpUrl: String, val websocketUrl: String, val webUiUrl: String, val token: String, val webUiToken: String)

internal object NapCatLocalConfig {
    fun connection(web: JSONObject, oneBot: JSONObject): NapCatLocalConnection {
        val webPort = localPort(web)
        val network = oneBot.getJSONObject("network")
        val http = server(network.optJSONArray("httpServers"), "HTTP", "aharou-http")
        val ws = server(network.optJSONArray("websocketServers"), "WebSocket", "aharou-ws")
        val token = http.optString("token")
        check(token.isNotBlank() && token == ws.optString("token")) { "NapCat HTTP and WebSocket must use the same non-empty token" }
        val webToken = web.optString("token")
        check(webToken.isNotBlank()) { "NapCat WebUI token is empty" }
        return NapCatLocalConnection("http://${localAddress(http)}:${localPort(http)}", "ws://${localAddress(ws)}:${localPort(ws)}",
            "http://${localAddress(web)}:$webPort", token, webToken)
    }

    fun accountFile(configDir: File, account: String): File {
        require(account.matches(Regex("[0-9]{5,20}")))
        return File(configDir, "onebot11_$account.json")
    }

    /** OneBot 配置的 network 下是否已有启用的 HTTP 或 WebSocket 服务端。 */
    fun hasEnabledServer(oneBot: JSONObject): Boolean {
        val network = oneBot.optJSONObject("network") ?: return false
        for (key in listOf("httpServers", "websocketServers")) {
            val array = network.optJSONArray(key) ?: continue
            for (index in 0 until array.length()) {
                val server = array.optJSONObject(index) ?: continue
                if (server.optBoolean("enable", true)) return true
            }
        }
        return false
    }

    fun update(field: String, value: String, web: JSONObject, oneBot: JSONObject) {
        val network = oneBot.getJSONObject("network")
        when (field) {
            "base_url" -> updateAddress(server(network.optJSONArray("httpServers"), "HTTP", "aharou-http"), value, "http")
            "websocket_url" -> updateAddress(server(network.optJSONArray("websocketServers"), "WebSocket", "aharou-ws"), value, "ws")
            "web_ui_url" -> updateAddress(web, value, "http")
            "token" -> {
                require(value.isNotBlank()) { "NapCat token must not be empty" }
                server(network.optJSONArray("httpServers"), "HTTP", "aharou-http").put("token", value)
                server(network.optJSONArray("websocketServers"), "WebSocket", "aharou-ws").put("token", value)
            }
            "web_ui_token" -> {
                require(value.isNotBlank()) { "NapCat WebUI token must not be empty" }
                web.put("token", value)
            }
            else -> error("Unknown NapCat connection field")
        }
    }

    private fun updateAddress(server: JSONObject, value: String, scheme: String) {
        val uri = java.net.URI(value.trim())
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")
        require(uri.scheme == scheme && host in setOf("127.0.0.1", "localhost", "::1") && uri.userInfo == null &&
            uri.query == null && uri.fragment == null && uri.path.orEmpty() in setOf("", "/")) { "NapCat connection must be a local $scheme address" }
        val port = if (uri.port == -1) 80 else uri.port
        require(port in 1..65535) { "Invalid NapCat port" }
        server.put("host", host).put("port", port)
    }

    private val localHosts = setOf("127.0.0.1", "localhost", "0.0.0.0", "::", "::1", "[::]", "[::1]")

    private fun server(array: JSONArray?, kind: String, managedName: String): JSONObject {
        val available = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }.filter {
            it.optBoolean("enable") && it.optString("host", "::") in localHosts && it.optInt("port") in 1..65535
        }
        return available.firstOrNull { it.optString("name") == managedName } ?: available.firstOrNull()
            ?: error("Enable a locally accessible NapCat $kind server in WebUI")
    }

    private fun localAddress(server: JSONObject): String {
        val host = server.optString("host", "::")
        check(host in localHosts) { "NapCat endpoint is not reachable through loopback" }
        return if (':' in host) "[::1]" else "127.0.0.1"
    }

    private fun localPort(server: JSONObject): Int {
        val port = server.optInt("port")
        check(port in 1..65535) { "Invalid NapCat local server port" }
        return port
    }
}
