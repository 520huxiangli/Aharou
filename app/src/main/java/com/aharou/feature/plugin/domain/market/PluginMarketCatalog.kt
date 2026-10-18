package com.aharou.feature.plugin.domain.market

import android.content.Context
import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 插件市场源清单：读取顺序「内存缓存 → 磁盘已下载文件 → 内置 assets」。
 *
 * 与技能市场（[com.aharou.feature.agent.domain.skill.market.SkillMarketCatalog]）、知识库、
 * `container-images.json` 是同一套机制：拉本仓库的 `data/plugins.json`（多 CDN 降级 + 磁盘缓存），
 * 加源、换地址只改仓库里那个 JSON，不必发版。
 */
@Singleton
class PluginMarketCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    @Volatile
    private var cached: PluginMarketData? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 仅供 App 启动阶段后台协程调用：拉最新源清单。解析成功且源非空才采纳，失败静默。 */
    suspend fun refreshFromNetworkIfStale() = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        // maxAgeMs = 0：跳过保鲜期，每次启动条件请求对账（内容没变时靠 ETag 回 304）
        val remote = when (val result = runCatching {
            RepoDataFetcher(context).fetch(REMOTE_CATALOG_PATH, maxAgeMs = 0L)
        }.getOrNull()) {
            is RepoDataFetcher.FetchResult.Success -> result.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
            else -> null
        }
        if (remote.isNullOrBlank()) return@withContext

        val parsed = runCatching { json.decodeFromString<PluginMarketData>(remote) }.getOrNull()
        if (parsed != null && parsed.sources.isNotEmpty()) {
            cached = parsed
            FileLogger.d(TAG, "远端插件市场源清单已更新：${parsed.sources.size} 个源")
        }
    }

    /** 同步读取：内存缓存 → 磁盘缓存 → assets 内置。纯本地 IO。 */
    fun load(): PluginMarketData {
        cached?.let { return it }

        val diskRaw = runCatching {
            RepoDataFetcher(context).readLocalCache(REMOTE_CATALOG_PATH)
        }.getOrNull()
        if (!diskRaw.isNullOrBlank()) {
            val parsed = runCatching { json.decodeFromString<PluginMarketData>(diskRaw) }.getOrNull()
            if (parsed != null && parsed.sources.isNotEmpty()) {
                cached = parsed
                return parsed
            }
        }

        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return PluginMarketData()
        val parsed = runCatching { json.decodeFromString<PluginMarketData>(raw) }.getOrNull()
        val data = parsed ?: PluginMarketData()
        cached = data
        return data
    }

    private companion object {
        const val TAG = "PluginMarketCatalog"
        const val ASSET_FILE = "plugins-market.json"

        /** 统一拉取器中的仓库相对路径：本仓库维护的插件市场源清单。 */
        const val REMOTE_CATALOG_PATH = "data/plugins.json"

        val json = Json { ignoreUnknownKeys = true }
    }
}