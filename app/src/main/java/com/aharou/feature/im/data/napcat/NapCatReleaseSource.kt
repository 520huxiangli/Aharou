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

    /**
     * 最新版本从我们自己的 CNB 清单读，不再请求 GitHub。
     *
     * 之前这里要拉官方 install.sh 和 GitHub release 接口解析版本，国内直连必然超时，
     * 失败一次整个安装就中断。清单里已经有版本号与各包的 CNB 地址，直接取即可。
     */
    internal suspend fun latest(architecture: String): NapCatRelease = withContext(Dispatchers.IO) {
        require(architecture in setOf("arm64", "amd64"))
        val root = JSONObject(get(MANIFEST_URL))
        val napcatVersion = root.getString("napcatVersion")
        val qqVersion = root.getString("qqVersion")
        val assets = root.getJSONArray("assets")
        fun assetUrl(predicate: (String) -> Boolean): String? = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { predicate(it.optString("name")) }
            ?.optString("url")
            ?.takeIf { it.isNotBlank() }

        val qqUrl = assetUrl { it.startsWith("linuxqq_") && it.contains(architecture) }
            ?: error("CNB 清单里没有 $architecture 的 QQ 包")
        val napcatUrl = assetUrl { it.startsWith("NapCat") && it.endsWith(".zip") }
            ?: error("CNB 清单里没有 NapCat Shell 包")
        NapCatRelease(napcatVersion, qqVersion, qqUrl, napcatUrl)
    }

    private fun get(url: String): String {
        val builder = Request.Builder().url(url)
        if (url.startsWith("https://api.cnb.cool/")) {
            builder.header("Accept", "application/vnd.cnb.api+json")
        } else {
            builder.header("Accept", "application/json")
        }
        client.newCall(builder.build()).execute().use { response ->
            check(response.isSuccessful) { "NapCat 清单 HTTP ${response.code}" }
            val host = response.request.url.host
            check(host in setOf(CNB_HOST, "raw.githubusercontent.com", "api.github.com")) {
                "Unexpected metadata host: $host"
            }
            return response.body?.string() ?: error("Empty NapCat metadata")
        }
    }

    private companion object {
        const val CNB_HOST = "cnb.cool"
        const val MANIFEST_URL = "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/napcat/index.json"
    }
}
