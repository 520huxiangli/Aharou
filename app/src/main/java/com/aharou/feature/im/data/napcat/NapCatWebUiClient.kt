package com.aharou.feature.im.data.napcat

import java.net.Proxy
import com.aharou.feature.im.data.repository.NapCatSettingsRepository
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
class NapCatWebUiClient @Inject constructor(private val settings: NapCatSettingsRepository) {
    private val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build()
    private var credential = ""
    private var bearer = ""
    private var authenticatedAt = 0L
    private val mutex = Mutex()

    fun reset() { credential = ""; bearer = "" }

    suspend fun call(token: String, path: String, data: JSONObject = JSONObject()): JSONObject =
        withContext(Dispatchers.IO) { mutex.withLock {
            require(path.startsWith("/api/"))
            val base = settings.current().webUiUrl.toHttpUrl()
            require(base.scheme == "http" && base.host in setOf("127.0.0.1", "localhost", "::1") && base.username.isEmpty() && base.password.isEmpty()) { "NapCat WebUI must use a local HTTP address" }
            val endpoint = base.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().removeSuffix("/")
            if (credential != token + endpoint || bearer.isEmpty() || System.currentTimeMillis() - authenticatedAt > 50 * 60 * 1000L) {
                val auth = post(endpoint, "/api/auth/login", JSONObject().put("hash", digest("SHA-256", "$token.napcat")), "", token)
                bearer = auth.getJSONObject("data").getString("Credential")
                credential = token + endpoint
                authenticatedAt = System.currentTimeMillis()
            }
            post(endpoint, path, data, bearer, token)
        } }

    private fun post(endpoint: String, path: String, body: JSONObject, auth: String, token: String): JSONObject {
        val request = Request.Builder().url("$endpoint$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .apply { if (auth.isNotEmpty()) header("Authorization", "Bearer $auth") }.build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                if (response.code == 401 || response.code == 403) reset()
                error("NapCat WebUI HTTP ${response.code}")
            }
            val result = JSONObject(response.body?.string() ?: error("NapCat WebUI empty response"))
            val code = result.optInt("code", -1)
            check(code == 0) {
                var detail = result.optString("message").ifBlank { result.optString("msg") }
                listOf(auth, bearer, token)
                    .filter { it.isNotBlank() }.forEach { detail = detail.replace(it, "[redacted]") }
                body.keys().forEach { key ->
                    if (key in setOf("hash", "password", "passwordMd5", "ticket", "randstr", "uin")) {
                        body.optString(key).takeIf { it.isNotBlank() }?.let { detail = detail.replace(it, "[redacted]") }
                    }
                }
                detail = detail.replace(Regex("https?://[^\\s]+"), "[URL redacted]")
                    .replace(Regex("(?i)(token|credential|password|authorization)\\s*[=:]\\s*[^\\s,;]+"), "$1=[redacted]")
                    .replace(Regex("(?<!\\d)\\d{5,20}(?!\\d)"), "[account redacted]")
                    .take(400)
                "NapCat WebUI $path rejected (code=$code)${if (detail.isBlank()) "" else ": $detail"}"
            }
            return result
        }
    }

    companion object {
        fun digest(algorithm: String, value: String): String = MessageDigest.getInstance(algorithm)
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
