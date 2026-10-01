package com.aharou.feature.settings.data.local

import android.content.Context
import com.aharou.core.net.RepoDataFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 内置/动态 provider 的展示预设：包含 name/type/baseUrl 及推荐与推广扩展属性。 */
@Serializable
data class ProviderPreset(
    val id: String,
    val name: String,
    /** 协议类型：OPENAI / ANTHROPIC / GEMINI。 */
    val type: String,
    /** Base URL，选中后自动填充，可在编辑页修改。 */
    val baseUrl: String,
    /** 关联的模型 id 列表（可选，选中后导入为可用模型）。 */
    val models: List<String> = emptyList(),
    /** 是否推荐/置顶 */
    val isRecommended: Boolean = false,
    /** 徽标/标签文案（如「推荐」、「赞助」） */
    val badge: String? = null,
    /** 供应商官网或推广注册链接 */
    val websiteUrl: String? = null,
    /** 特性/福利标签列表（如 ["注册送10元"]） */
    val tags: List<String> = emptyList()
)

/**
 * 官方及自定义 provider 预设库：
 * 采用双轨机制：
 * 1. 启动时由后台异步预热拉取（[refreshFromNetworkIfStale]），写磁盘并更新内存 [cached]；
 * 2. 任何 UI 读取（[loadOfficial]）均同步从「内存 -> 磁盘已下载文件 -> 内置 assets」读取，
 *    绝对不发起网络请求，毫秒级直接返回。
 */
object ProviderPresetLibrary {
    const val PROVIDERS_ASSET_FILE_NAME = "providers.json"
    const val REMOTE_PROVIDERS_PATH = "data/providers.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var cached: List<ProviderPreset>? = null

    @Volatile
    private var refreshAttemptedThisProcess = false

    /**
     * 仅供 App 启动阶段（AIEditorApp）在后台协程异步调用：
     * 走 RepoDataFetcher 测活拉取最新配置并持久化，失败完全静默。
     */
    suspend fun refreshFromNetworkIfStale(context: Context) = withContext(Dispatchers.IO) {
        if (refreshAttemptedThisProcess) return@withContext
        refreshAttemptedThisProcess = true

        val fetcher = RepoDataFetcher(context)
        // maxAgeMs = 0：跳过默认 12 小时保鲜期，每次启动条件请求对账，让清单改动立即生效。
        val remoteResult = runCatching { fetcher.fetch(REMOTE_PROVIDERS_PATH, maxAgeMs = 0L) }.getOrNull()
        val remoteContent = when (remoteResult) {
            is RepoDataFetcher.FetchResult.Success -> remoteResult.content
            is RepoDataFetcher.FetchResult.FallbackDiskCache -> remoteResult.content
            else -> null
        }

        if (!remoteContent.isNullOrBlank()) {
            val parsedRemote = runCatching {
                json.decodeFromString<List<ProviderPreset>>(remoteContent)
            }.getOrNull()
            if (!parsedRemote.isNullOrEmpty()) {
                cached = mergeWithBuiltIn(parsedRemote, loadFromAssets(context))
            }
        }
    }

    /**
     * UI 专用快速同步读取：
     * 顺序：内存缓存 -> 磁盘下载文件 -> assets 内置文件。
     * 纯本地 IO，绝对不发任何网络请求，秒级返回。
     */
    fun loadOfficial(context: Context): List<ProviderPreset> {
        cached?.let { return it }

        val builtIn = loadFromAssets(context)

        // 1. 尝试直接读取本地磁盘缓存文件（由 RepoDataFetcher 写入）
        val diskContent = RepoDataFetcher(context).readLocalCache(REMOTE_PROVIDERS_PATH)
        if (!diskContent.isNullOrBlank()) {
            val parsed = runCatching {
                json.decodeFromString<List<ProviderPreset>>(diskContent)
            }.getOrNull()
            if (!parsed.isNullOrEmpty()) {
                val merged = mergeWithBuiltIn(parsed, builtIn)
                cached = merged
                return merged
            }
        }

        // 2. 兜底读取内置 assets（必定存在，必定瞬间返回）
        cached = builtIn
        return builtIn
    }

    /**
     * 磁盘/远端预设与内置预设合并：以远端为主，内置里有而远端没有的补上。
     *
     * 远端缓存有 12 小时 TTL，而内置预设随包更新。若直接拿远端结果盖掉内置，
     * 新装的版本会暂时看不到新增预设（要等缓存过期才出现）——内置项按 id 补齐。
     */
    private fun mergeWithBuiltIn(
        remote: List<ProviderPreset>,
        builtIn: List<ProviderPreset>
    ): List<ProviderPreset> {
        val remoteIds = remote.mapTo(mutableSetOf()) { it.id }
        val missing = builtIn.filterNot { it.id in remoteIds }
        return (remote + missing).sortedWith(PRESET_ORDER)
    }

    private val PRESET_ORDER = compareByDescending<ProviderPreset> { it.isRecommended }
        .thenBy { it.name.lowercase() }

    private fun loadFromAssets(context: Context): List<ProviderPreset> = runCatching {
        val text = context.assets.open(PROVIDERS_ASSET_FILE_NAME).bufferedReader().use { it.readText() }
        val list = json.decodeFromString<List<ProviderPreset>>(text)
        list.sortedWith(PRESET_ORDER)
    }.getOrDefault(emptyList())
}
