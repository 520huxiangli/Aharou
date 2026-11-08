package com.aharou.feature.im.data.napcat

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

internal data class NapCatRelease(val version: String, val qqVersion: String, val qqUrl: String, val napcatUrl: String)

@Singleton
class NapCatReleaseSource @Inject constructor(baseClient: OkHttpClient) {
    private val client = baseClient.newBuilder().callTimeout(60, TimeUnit.SECONDS).build()

    internal fun bundled(architecture: String) = NapCatRelease(
        "v4.18.42", "3.2.34-53644",
        "https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_$architecture.deb",
        "https://github.com/NapNeko/NapCatQQ/releases/download/v4.18.42/NapCat.Shell.zip"
    )

    internal suspend fun latest(architecture: String): NapCatRelease = withContext(Dispatchers.IO) {
        require(architecture in setOf("arm64", "amd64"))
        val script = get("https://raw.githubusercontent.com/NapNeko/NapCat-Installer/main/script/install.sh")
        val qqVersion = Regex("linuxqq_target_version=\"([0-9]+\\.[0-9]+\\.[0-9]+-[0-9]+)\"")
            .find(script)?.groupValues?.get(1) ?: error("Official QQ version format changed")
        val fileName = "linuxqq_${qqVersion}_$architecture.deb"
        check(script.contains("qq_remote_file=\"$fileName\"")) { "Official installer does not declare the compatible QQ package" }
        val prefix = Regex("qq_download_url=\"(https://qqdl\\.gtimg\\.cn/qqfile/QQNT/[A-Za-z0-9/._-]+/)\\$\\{qq_remote_file}")
            .find(script)?.groupValues?.get(1) ?: error("Official QQ download format changed")
        check(script.contains("https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip")) {
            "Official NapCat compatibility source changed"
        }
        val release = JSONObject(get("https://api.github.com/repos/NapNeko/NapCatQQ/releases/latest"))
        check(!release.optBoolean("prerelease") && !release.optBoolean("draft")) { "No stable NapCat release" }
        val tag = release.getString("tag_name")
        check(tag.matches(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+"))) { "Official NapCat tag format changed" }
        val url = "https://github.com/NapNeko/NapCatQQ/releases/download/$tag/NapCat.Shell.zip"
        val assets = release.getJSONArray("assets")
        check((0 until assets.length()).any { assets.getJSONObject(it).optString("browser_download_url") == url }) {
            "Official NapCat Shell asset is missing"
        }
        NapCatRelease(tag, qqVersion, prefix + fileName, url)
    }

    private fun get(url: String): String {
        client.newCall(Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()).execute().use { response ->
            check(response.isSuccessful) { "Official NapCat metadata HTTP ${response.code}" }
            val finalHost = response.request.url.host
            check(finalHost in setOf("raw.githubusercontent.com", "api.github.com")) { "Unexpected metadata redirect" }
            return response.body?.string() ?: error("Empty official NapCat metadata")
        }
    }
}
