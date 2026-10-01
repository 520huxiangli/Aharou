package com.aharou.feature.agent.domain.skill.market

import android.content.Context
import com.aharou.core.net.RepoDataFetcher
import com.aharou.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能市场源清单：读取顺序「内存缓存 → 磁盘已下载文件 → 内置 assets」。
 *
 * 与 `container-images.json` 同一套机制：拉本仓库的 `data/skills.json`（多 CDN 降级 + 磁盘缓存），
 * 加源、换地址只改仓库里那个 JSON，不必发版。网络预热由 [refreshFromNetworkIfStale] 在启动阶段
 * 后台执行，任何失败都静默。
 */
@Singleton
class SkillMarketCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customSourceRepository: SkillMarketSourceRepository
) {
    @Volatile
    private var cached: SkillMarketData? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /**
     * 仅供 App 启动阶段后台协程调用：拉取仓库里的最新源清单并更新内存缓存。
     * 解析成功且源列表非空才采纳；失败静默，UI 仍走磁盘缓存或内置 assets。
     */
    suspend fun refreshFromNetworkIfStale() = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        // maxAgeMs = 0：跳过默认 12 小时保鲜期，每次启动条件请求对账，让清单改动立即生效
        // （内容未变时靠 ETag 回 304）。
        val remote = when (val result = runCatching {
            RepoDataFetcher(context).fetch(REMOTE_CATALOG_PATH, maxAgeMs = 0L)
        }.getOrNull()) {
            is RepoDataFetcher.FetchResult.Success -> result.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
            else -> null
        }
        if (remote.isNullOrBlank()) return@withContext

        val parsed = runCatching { json.decodeFromString<SkillMarketData>(remote) }.getOrNull()
        if (parsed != null && parsed.sources.isNotEmpty()) {
            cached = parsed
            FileLogger.d(TAG, "远端技能市场源清单已更新：${parsed.sources.size} 个源")
        }
    }

    /** UI 专用快速同步读取：内存缓存 → 磁盘缓存 → assets 内置，再叠加用户自定义源。纯本地 IO。 */
    fun load(): SkillMarketData {
        val base = loadBase()
        val custom = customSourceRepository.all()
        return if (custom.isEmpty()) base else base.copy(sources = base.sources + custom)
    }

    private fun loadBase(): SkillMarketData {
        cached?.let { return it }

        val diskRaw = runCatching {
            RepoDataFetcher(context).readLocalCache(REMOTE_CATALOG_PATH)
        }.getOrNull()
        if (!diskRaw.isNullOrBlank()) {
            val parsed = runCatching { json.decodeFromString<SkillMarketData>(diskRaw) }.getOrNull()
            if (parsed != null && parsed.sources.isNotEmpty()) {
                cached = parsed
                return parsed
            }
        }

        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull() ?: return SkillMarketData()
        val parsed = runCatching { json.decodeFromString<SkillMarketData>(raw) }.getOrNull()
        val data = parsed ?: SkillMarketData()
        cached = data
        return data
    }

    private companion object {
        const val TAG = "SkillMarketCatalog"
        const val ASSET_FILE = "skills-market.json"

        /** 统一拉取器中的仓库相对路径：本仓库维护的技能市场源清单。 */
        const val REMOTE_CATALOG_PATH = "data/skills.json"

        val json = Json { ignoreUnknownKeys = true }
    }
}
