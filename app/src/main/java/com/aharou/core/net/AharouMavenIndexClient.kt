package com.aharou.core.net

import android.content.Context
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Aharou 自建依赖索引：高频 Maven 坐标的版本快照，托管在 cnb 发布仓的 `maven-index` Release。
 *
 * 主仓库（maven.google.com 等）在国内不可达时会整批查不到版本，这份索引给 [DependencyChecker]
 * 当兜底——因此它**只在实时仓库全部失败后才被查询**：快照会滞后，拿它当主源会把「已是最新」判错。
 *
 * - 数据：`maven-index.json.gz`，结构 `{version, generatedAt, coordinates: {"group:artifact": {latest, release, versions}}}`
 * - 缓存：磁盘 TTL 24 小时（`filesDir/maven_index_cache`），内存里再留一份解析结果；
 * - 失败降级：拉不到就用磁盘旧缓存（不看 TTL），仍无则返回 null——**绝不抛异常打断依赖检查主流程**。
 */
@Singleton
class AharouMavenIndexClient @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * 查一个坐标的版本元数据；索引里没有、或索引整体不可用都返回 null。
     *
     * [forceRefresh] 为 true 时忽略磁盘 TTL 重新下载（撞上依赖检查的 refresh 参数）。
     */
    suspend fun lookup(group: String, artifact: String, forceRefresh: Boolean = false): MavenMetadataParser.Metadata? =
        withContext(Dispatchers.IO) {
            val index = index(forceRefresh) ?: return@withContext null
            index["$group:$artifact"]
        }

    private suspend fun index(forceRefresh: Boolean): Map<String, MavenMetadataParser.Metadata>? {
        if (!forceRefresh) memory?.let { return it }
        return lock.withLock {
            // 等锁期间可能已被别的协程填好
            if (!forceRefresh) memory?.let { return@withLock it }
            val loaded = downloadIndex() ?: readCachedIndex() ?: return@withLock null
            parsed(loaded)?.also { memory = it }
        }
    }

    /** 先走磁盘缓存（未过期），再联网；返回 gz 原始字节。 */
    private fun downloadIndex(): ByteArray? {
        val cacheFile = cacheFile()
        val fresh = cacheFile.isFile && System.currentTimeMillis() - cacheFile.lastModified() < CACHE_TTL_MS
        if (fresh) return runCatching { cacheFile.readBytes() }.getOrNull()

        return try {
            val request = Request.Builder()
                .url(INDEX_URL)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    FileLogger.d(TAG, "索引下载失败：HTTP ${response.code}")
                    return null
                }
                val bytes = response.body?.bytes() ?: return null
                runCatching {
                    cacheFile.parentFile?.mkdirs()
                    cacheFile.writeBytes(bytes)
                }
                bytes
            }
        } catch (e: Exception) {
            FileLogger.d(TAG, "索引下载异常：${e.message}")
            null
        }
    }

    /** 网络拿不到时的兜底：磁盘上那份旧的照用（不看 TTL）。 */
    private fun readCachedIndex(): ByteArray? {
        val cacheFile = cacheFile()
        if (!cacheFile.isFile) return null
        FileLogger.d(TAG, "索引改用磁盘旧缓存")
        return runCatching { cacheFile.readBytes() }.getOrNull()
    }

    private fun parsed(gz: ByteArray): Map<String, MavenMetadataParser.Metadata>? = runCatching {
        val text = GZIPInputStream(ByteArrayInputStream(gz)).bufferedReader().use { it.readText() }
        val root = json.parseToJsonElement(text).jsonObject
        if (root["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != INDEX_VERSION) {
            FileLogger.w(TAG, "索引版本不认识，忽略（期望 $INDEX_VERSION）")
            return null
        }
        val coordinates = root["coordinates"]?.jsonObject ?: return null
        coordinates.mapValues { (_, element) ->
            val entry = element.jsonObject
            MavenMetadataParser.Metadata(
                latest = entry["latest"]?.jsonPrimitive?.contentOrNull,
                release = entry["release"]?.jsonPrimitive?.contentOrNull,
                versions = entry["versions"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    .orEmpty()
            )
        }
    }.getOrElse {
        FileLogger.w(TAG, "索引解析失败：${it.message}")
        null
    }

    private fun cacheFile(): File = File(File(context.filesDir, CACHE_DIR_NAME), ASSET_NAME)

    @Volatile
    private var memory: Map<String, MavenMetadataParser.Metadata>? = null

    private val lock = Mutex()

    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        const val TAG = "AharouMavenIndexClient"
        const val INDEX_URL =
            "https://cnb.cool/huxiangli/aharou-releases/-/releases/download/maven-index/maven-index.json.gz"
        const val ASSET_NAME = "maven-index.json.gz"
        const val CACHE_DIR_NAME = "maven_index_cache"
        const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
        const val INDEX_VERSION = 1
        const val USER_AGENT = "aharou-dependency-checker"

        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .proxyAuthenticator(AppProxy.okHttpAuthenticator)
                .build()
        }
    }
}
