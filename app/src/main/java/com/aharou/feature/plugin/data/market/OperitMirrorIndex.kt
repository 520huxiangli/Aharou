package com.aharou.feature.plugin.data.market

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Operit 插件的 CNB 镜像索引。
 *
 * 为什么要有这一层：Operit 的插件资产挂在各家 GitHub Release 上，国内直连经常几十秒才超时，
 * 装一个插件要等半天。镜像仓库 `cnb.cool/huxiangli/aharou-plugins` 由云端定时任务同步，
 * 同一份文件在 CNB 上取通常一瞬间就好，所以下载时先问这里。
 *
 * 索引只是加速手段：拉不到、解析失败、文件不在索引里，全部安静地返回 null，
 * 由调用方回退 GitHub，不让镜像的可用性影响能不能装插件。
 */
@Singleton
class OperitMirrorIndex @Inject constructor(
    @param:ApplicationContext private val context: Context,
    client: OkHttpClient
) {
    private val http = client.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .cache(null)
        .build()

    private val cacheFile = File(File(context.filesDir, CACHE_DIR), "operit-mirror-index.json")

    @Volatile
    private var assetToPath: Map<String, String>? = null

    private val loadMutex = Mutex()

    /** assetName 在镜像里对应的下载地址；没有就返回 null，调用方回退 GitHub。 */
    suspend fun resolveUrl(assetName: String): String? {
        if (assetName.isBlank()) return null
        val path = index()[assetName] ?: return null
        val encoded = path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return RAW_BASE + encoded
    }

    private suspend fun index(): Map<String, String> {
        assetToPath?.let { return it }
        return loadMutex.withLock {
            assetToPath?.let { return@withLock it }
            val loaded = readCache() ?: runCatching { fetch() }.getOrElse { error ->
                FileLogger.w(TAG, "镜像索引拉取失败，本次全部走 GitHub", error)
                emptyMap()
            }
            assetToPath = loaded
            loaded
        }
    }

    /** 索引按天更新，本地留一天；过期后下次下载会重新拉。 */
    private fun readCache(): Map<String, String>? {
        if (!cacheFile.isFile) return null
        val age = System.currentTimeMillis() - cacheFile.lastModified()
        if (age > CACHE_TTL_MS) return null
        return runCatching { parse(cacheFile.readText()) }.getOrNull()
    }

    private suspend fun fetch(): Map<String, String> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(INDEX_URL).build()
        val text = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
            response.body?.string() ?: throw IllegalStateException("空响应")
        }
        val parsed = parse(text)
        cacheFile.parentFile?.mkdirs()
        runCatching { cacheFile.writeText(text) }
        parsed
    }

    private fun parse(text: String): Map<String, String> {
        val payload = JSON.decodeFromString<MirrorIndexDto>(text)
        val map = HashMap<String, String>(payload.items.size)
        for (item in payload.items) {
            val name = item.assetName ?: continue
            val path = item.path ?: continue
            if (item.available == false) continue
            map[name] = path
        }
        return map
    }

    @Serializable
    private data class MirrorIndexDto(
        val items: List<MirrorItemDto> = emptyList()
    )

    @Serializable
    private data class MirrorItemDto(
        val assetName: String? = null,
        val path: String? = null,
        val available: Boolean? = null
    )

    private companion object {
        const val TAG = "OperitMirrorIndex"
        const val CACHE_DIR = "plugin-market"
        const val CACHE_TTL_MS = 24L * 60 * 60 * 1000
        const val INDEX_URL =
            "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/mirror/operit/index.json"
        const val RAW_BASE =
            "https://cnb.cool/huxiangli/aharou-plugins/-/git/raw/main/"

        val JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
