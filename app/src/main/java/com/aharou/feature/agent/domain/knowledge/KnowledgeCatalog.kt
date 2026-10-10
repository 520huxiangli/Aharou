package com.aharou.feature.agent.domain.knowledge

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
 * 知识库源清单：读取顺序「内存缓存 → 磁盘已下载文件 → 内置 assets」。
 *
 * 与技能市场（[com.aharou.feature.agent.domain.skill.market.SkillMarketCatalog]）、
 * `container-images.json` 同一套机制：拉本仓库的 `data/knowledge.json`（多 CDN 降级 + 磁盘缓存），
 * 加源、换地址只改仓库里那个 JSON，不必发版。
 */
@Singleton
class KnowledgeCatalog @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val customSourceRepository: KnowledgeSourceRepository
) {
    @Volatile
    private var cached: KnowledgeData? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /** 仅供 App 启动阶段后台协程调用：拉取仓库里的最新源清单并更新内存缓存。解析成功且源非空才采纳。 */
    suspend fun refreshFromNetworkIfStale() = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        // maxAgeMs = 0：跳过默认保鲜期，每次启动条件请求对账（内容未变时靠 ETag 回 304）。
        val remote = when (val result = runCatching {
            RepoDataFetcher(context).fetch(REMOTE_CATALOG_PATH, maxAgeMs = 0L)
        }.getOrNull()) {
            is RepoDataFetcher.FetchResult.Success -> result.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> result.content
            else -> null
        }
        if (remote.isNullOrBlank()) return@withContext

        val parsed = runCatching { json.decodeFromString<KnowledgeData>(remote) }.getOrNull()
        if (parsed != null && parsed.sources.isNotEmpty()) {
            cached = parsed
            FileLogger.d(TAG, "远端知识库源清单已更新：${parsed.sources.size} 个源")
        }
    }

    /** 同步读取：内存缓存 → 磁盘缓存 → assets 内置，再叠加用户自定义源。纯本地 IO。 */
    fun load(): KnowledgeData {
        val base = loadBase()
        val custom = customSourceRepository.all()
        return if (custom.isEmpty()) base else base.copy(sources = base.sources + custom)
    }

    private fun loadBase(): KnowledgeData {
        cached?.let { return it }

        val diskRaw = runCatching {
            RepoDataFetcher(context).readLocalCache(REMOTE_CATALOG_PATH)
        }.getOrNull()
        if (!diskRaw.isNullOrBlank()) {
            val parsed = runCatching { json.decodeFromString<KnowledgeData>(diskRaw) }.getOrNull()
            if (parsed != null && parsed.sources.isNotEmpty()) {
                cached = parsed
                return parsed
            }
        }

        val raw = runCatching {
            context.assets.open(ASSET_FILE).bufferedReader().use { it.readText() }
        }.getOrNull()
        val parsed = raw?.let { runCatching { json.decodeFromString<KnowledgeData>(it) }.getOrNull() }
        val data = parsed ?: DEFAULT
        cached = data
        return data
    }

    private companion object {
        const val TAG = "KnowledgeCatalog"
        const val ASSET_FILE = "knowledge.json"

        /** 统一拉取器中的仓库相对路径：本仓库维护的知识库源清单。 */
        const val REMOTE_CATALOG_PATH = "data/knowledge.json"

        val json = Json { ignoreUnknownKeys = true }

        /** 网络与 assets 都拿不到时的兜底：只保留第一方共享知识库。 */
        val DEFAULT = KnowledgeData(
            sources = mapOf(
                "aharou-kb" to KnowledgeSourceDef(
                    repo = "520huxiangli/aharou-kb",
                    branch = "master",
                    path = "",
                    name = mapOf("zh" to "Aharou 共享知识库", "en" to "Aharou Knowledge Base")
                )
            )
        )
    }
}
