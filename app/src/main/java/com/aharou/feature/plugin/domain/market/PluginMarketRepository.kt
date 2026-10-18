package com.aharou.feature.plugin.domain.market

import android.content.Context
import com.aharou.core.net.AppProxy
import com.aharou.core.util.FileLogger
import com.aharou.feature.agent.domain.skill.market.SkillRepoAccess
import com.aharou.feature.plugin.domain.PluginManager
import com.aharou.feature.plugin.domain.model.PluginManifest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 插件市场的读写：拉源里的索引、下载插件包、交给 [PluginManager] 安装、比对版本查更新。
 *
 * 索引地址的拼装复用技能市场那套 [SkillRepoAccess]（同一个仓库可能同时是技能源和插件源，
 * 没必要为插件再写一份平台差异），下载则走带全局代理的 OkHttp。
 *
 * 安装复用既有的导入→安装链路，**不另造一套**：`importPackage` 里已有 zip-slip 防护与清单校验。
 */
@Singleton
class PluginMarketRepository @Inject constructor(
    private val catalog: PluginMarketCatalog,
    private val pluginManager: PluginManager,
    @param:ApplicationContext private val context: Context,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .proxyAuthenticator(AppProxy.okHttpAuthenticator)
        .build()

    /** 并发上限：CDN 对同 IP 的并发请求会限流，一次打十来个会集体超时。 */
    private val fetchLimiter = Semaphore(permits = 4)

    /** 全部源（id → 定义），供 UI 展示来源。 */
    fun sources(): Map<String, PluginMarketSourceDef> = catalog.load().sources

    /** 拉某个源的插件列表。[failed] 用来区分「源里确实没有插件」与「没取到」。 */
    suspend fun listPlugins(sourceId: String): MarketListing = withContext(Dispatchers.IO) {
        val source = sources()[sourceId]
            ?: return@withContext MarketListing(emptyList(), failed = true)
        if (source.repo.isBlank()) return@withContext MarketListing(emptyList(), failed = true)

        val coord = SkillRepoAccess.Coord(source.host, source.repo, source.branch)
        val text = fetchLimiter.withPermit {
            httpGetFirst(SkillRepoAccess.fileUrls(coord, source.path))
        } ?: return@withContext MarketListing(emptyList(), failed = true)

        val pack = runCatching { json.decodeFromString<PluginPackData>(text) }.getOrNull()
            ?: return@withContext MarketListing(emptyList(), failed = true)
        MarketListing(pack.plugins)
    }

    /** 下载并安装一个市场插件；成功后返回它的清单，供 UI 报出名称与版本。 */
    suspend fun install(entry: PluginMarketEntry): Result<PluginManifest> = withContext(Dispatchers.IO) {
        runCatching {
            val url = entry.downloadUrl.ifBlank { error("这条市场条目没有下载地址") }
            val bytes = fetchLimiter.withPermit { httpGetBytes(listOf(url)) }
                ?: error("插件包下载失败")

            // 落 cacheDir 而不是插件目录：importPackage 期望一个完整文件，装完即删，别污染插件根目录
            val tmp = File(context.cacheDir, "plugin-market/${entry.id}.txplugin").apply {
                parentFile?.mkdirs()
                writeBytes(bytes)
            }
            try {
                val manifest = pluginManager.importPackage(tmp).getOrThrow()
                pluginManager.install(manifest.id).getOrThrow()
                manifest
            } finally {
                tmp.delete()
            }
        }.onFailure { FileLogger.e(TAG, "安装市场插件失败：${entry.id}", it) }
    }

    /** 已装插件的版本表（id → 版本），市场列表据此标「已安装 / 有新版」。 */
    suspend fun installedVersions(): Map<String, String> =
        pluginManager.listInstalled().associate { it.manifest.id to it.manifest.version }

    private suspend fun httpGetFirst(urls: List<String>): String? {
        for (url in urls) {
            val text = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                    .execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
            }.getOrNull()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private suspend fun httpGetBytes(urls: List<String>): ByteArray? {
        for (url in urls) {
            val bytes = runCatching {
                client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
                    .execute().use { resp -> if (resp.isSuccessful) resp.body?.bytes() else null }
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
        return null
    }

    /** 一个源的列表结果。[failed] 为 true 表示这次没取到（网络或格式问题），不代表源里没插件。 */
    data class MarketListing(
        val plugins: List<PluginMarketEntry>,
        val failed: Boolean = false
    )

    private companion object {
        const val TAG = "PluginMarketRepository"
        const val USER_AGENT = "aharou-android"
        val json = Json { ignoreUnknownKeys = true }
    }
}